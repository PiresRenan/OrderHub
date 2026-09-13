import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Check the generated string keywords in both ECMAScript regex modes. This is
// a portability regression, not a replacement for the full HTTP/contract suite.
const document = JSON.parse(readFileSync(process.argv[2] ?? 'target/contracts/openapi.json', 'utf8'));
const schemas = document.components.schemas;
let checks = 0;
function verify(component, field, cases, rawMaximum) {
  const schema = schemas[component].properties[field];
  assert.equal(schema.maxLength, rawMaximum, `${component}.${field} raw bound`);
  assert.ok(schema.pattern, `${component}.${field} pattern`);
  for (const flags of ['', 'u']) {
    const pattern = new RegExp(schema.pattern, flags);
    for (const [value, expected] of cases) {
      const accepted = value === null
        ? [].concat(schema.type).includes('null')
        : [...value].length >= (schema.minLength ?? 0)
          && [...value].length <= (schema.maxLength ?? Infinity)
          && pattern.test(value);
      assert.equal(accepted, expected, `${component}.${field}, flags=${flags || 'legacy'}, codePoints=${value === null ? 'null' : [...value].length}`);
      checks++;
    }
  }
}

verify('AdministrativeNameRequest', 'name', [
  ['', false], [' ', false], ['a', true], ['😀'.repeat(120), true],
  [' ' + '😀'.repeat(120) + ' ', true], ['😀'.repeat(121), false],
  ['\t' + 'a'.repeat(120) + '\n', true], ['\u00a0' + 'a'.repeat(120), false],
], undefined);

const names = [[' ', false], ['a'.repeat(160), true], ['😀'.repeat(160), true],
  ['😀'.repeat(161), false], [' ' + 'a'.repeat(159) + ' ', false], ['\u00a0a\u00a0', true]];
for (const component of ['CatalogProductCreate', 'CatalogProductUpdate', 'CatalogCategoryCreate', 'CatalogCategoryUpdate']) {
  verify(component, 'name', names, 160);
}
for (const [components, field, maximum] of [
  [['CatalogProductCreate', 'CatalogProductUpdate'], 'brand', 120],
  [['CatalogVariantCreate', 'CatalogVariantUpdate'], 'displayName', 160],
]) {
  const cases = [[null, true], ['', false], [' ', false],
    [' ' + '😀'.repeat(maximum) + ' ', true], ['😀'.repeat(maximum + 1), false],
    ['\u00a0' + 'a'.repeat(maximum) + '\u00a0', true], ['\tvalue', false], ['a\nb', false], ['a\u0085', false]];
  for (const component of components) verify(component, field, cases, undefined);
}
for (const [components, field, maximum] of [
  [['CatalogVariantCreate', 'CatalogVariantUpdate', 'CatalogVariantView', 'CatalogVariantSummary'], 'sku', 64],
  [['CatalogVariantCreate', 'CatalogVariantUpdate', 'CatalogVariantView'], 'mpn', 70],
  [['ProductVariantAttribute'], 'value', 256],
]) {
  const cases = [['', false], [' ', false], ['value', true], ['a b', true],
    ['😀'.repeat(maximum), true], ['😀'.repeat(maximum + 1), false],
    [' value', false], ['value\u00a0', false], ['a\nb', false]];
  for (const component of components) verify(component, field, cases, maximum);
}
for (const [component, field, value] of [
  ['CatalogProductCreate', 'slug', 'valid-slug'],
  ['ProductVariantAttribute', 'key', 'color'],
  ['CatalogPriceView', 'currencyCode', 'BRL'],
  ['InventoryMovement', 'reason', 'LOCAL_RECEIPT'],
]) {
  for (const flags of ['', 'u']) {
    const pattern = new RegExp(schemas[component].properties[field].pattern, flags);
    assert.equal(pattern.test(value), true);
    assert.equal(pattern.test(value + '\n'), false, `${component}.${field} must consume final newline`);
    checks += 2;
  }
}
console.log(`PASS: ${checks} generated OpenAPI string cases in ECMAScript legacy and Unicode modes`);
