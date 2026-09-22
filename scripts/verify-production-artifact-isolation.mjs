// Verifies that the final OrderHub production JAR contains no development-seed fixture.
// Usage: node scripts/verify-production-artifact-isolation.mjs [path/to/orderhub-<version>.jar]
// Without an argument the single repackaged target/orderhub-*.jar is used. Exit 1 lists every violation.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { inflateRawSync } from 'node:zlib';

const FIXTURE_PACKAGE = 'BOOT-INF/classes/io/github/piresrenan/orderhub/development/';
// Fixture class names; legitimate production classes such as DevelopmentDocumentationSecurityConfiguration do not match.
const FIXTURE_CLASS = /(^|\/)(LocalDevelopmentApplication|DevelopmentConfiguration|DevelopmentIssuer|DevelopmentSeed[A-Za-z0-9]*|SeededE2E|Seeded[A-Za-z0-9]*E2ETest|V1HttpE2ECoverageTest)(\$[^/]*)?\.class$/;
const FIXTURE_RESOURCE = /^BOOT-INF\/classes\/(development\/|data\.sql$|import\.sql$|seed-data\.md$)/;
const CONTENT_MARKERS = ['synthetic-local-', 'orderhub-disposable-development', 'io/github/piresrenan/orderhub/development/',
    'io.github.piresrenan.orderhub.development', 'DevelopmentSeedCatalog', 'DevelopmentSeedScenario'];

function locateJar(argument) {
    if (argument) return argument;
    const candidates = readdirSync('target').filter(name => /^orderhub-.+\.jar$/.test(name));
    if (candidates.length !== 1) throw new Error(`Expected exactly one target/orderhub-*.jar, found ${candidates.length}`);
    return join('target', candidates[0]);
}

function entries(archive) {
    let end = -1;
    for (let offset = archive.length - 22; offset >= Math.max(0, archive.length - 65557); offset--) {
        if (archive.readUInt32LE(offset) === 0x06054b50) { end = offset; break; }
    }
    if (end < 0) throw new Error('Not a ZIP archive: end of central directory not found');
    const count = archive.readUInt16LE(end + 10);
    let cursor = archive.readUInt32LE(end + 16);
    if (count === 0xffff || cursor === 0xffffffff) throw new Error('ZIP64 archives are not supported; failing closed');
    const result = [];
    for (let index = 0; index < count; index++) {
        if (archive.readUInt32LE(cursor) !== 0x02014b50) throw new Error('Corrupt central directory');
        const method = archive.readUInt16LE(cursor + 10);
        const compressedSize = archive.readUInt32LE(cursor + 20);
        const nameLength = archive.readUInt16LE(cursor + 28);
        const extraLength = archive.readUInt16LE(cursor + 30);
        const commentLength = archive.readUInt16LE(cursor + 32);
        const localHeader = archive.readUInt32LE(cursor + 42);
        const name = archive.toString('utf8', cursor + 46, cursor + 46 + nameLength);
        result.push({ name, method, compressedSize, localHeader });
        cursor += 46 + nameLength + extraLength + commentLength;
    }
    return result;
}

function content(archive, entry) {
    const header = entry.localHeader;
    if (archive.readUInt32LE(header) !== 0x04034b50) throw new Error(`Corrupt local header for ${entry.name}`);
    const start = header + 30 + archive.readUInt16LE(header + 26) + archive.readUInt16LE(header + 28);
    const data = archive.subarray(start, start + entry.compressedSize);
    if (entry.method === 0) return data;
    if (entry.method === 8) return inflateRawSync(data);
    throw new Error(`Unsupported compression method ${entry.method} for ${entry.name}; failing closed`);
}

export function violations(jarPath) {
    const archive = readFileSync(jarPath);
    const found = [];
    for (const entry of entries(archive)) {
        if (entry.name.startsWith(FIXTURE_PACKAGE)) found.push(`forbidden entry (fixture package): ${entry.name}`);
        else if (FIXTURE_CLASS.test(entry.name)) found.push(`forbidden entry (fixture class): ${entry.name}`);
        else if (FIXTURE_RESOURCE.test(entry.name)) found.push(`forbidden entry (fixture resource): ${entry.name}`);
        if (entry.name.startsWith('BOOT-INF/classes/') && !entry.name.endsWith('/')) {
            const text = content(archive, entry).toString('latin1');
            for (const marker of CONTENT_MARKERS) {
                if (text.includes(marker)) found.push(`forbidden marker "${marker}" in ${entry.name}`);
            }
        }
    }
    return found;
}

const jar = locateJar(process.argv[2]);
const found = violations(jar);
if (found.length > 0) {
    console.error(`FAIL: ${jar} contains development seed fixture content:`);
    for (const violation of found) console.error(`  ${violation}`);
    process.exit(1);
}
console.log(`PASS: production artifact contains no development seed classes/resources (${jar})`);
