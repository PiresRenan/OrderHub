// Self-test for verify-production-artifact-isolation.mjs (run with: node --test scripts/). Needs a built target/classes.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { crc32, deflateRawSync } from 'node:zlib';

const GUARD = 'scripts/verify-production-artifact-isolation.mjs';

/** Writes a minimal ZIP (stored or deflated entries) so the guard is exercised on real archive structure. */
function zip(path, entries, deflate) {
    const locals = [];
    const centrals = [];
    let offset = 0;
    for (const [name, raw] of entries) {
        const data = Buffer.from(raw);
        const body = deflate ? deflateRawSync(data) : data;
        const nameBytes = Buffer.from(name, 'utf8');
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(deflate ? 8 : 0, 8);
        local.writeUInt32LE(crc32(data), 14); local.writeUInt32LE(body.length, 18); local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBytes.length, 26);
        const central = Buffer.alloc(46);
        central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
        central.writeUInt16LE(deflate ? 8 : 0, 10); central.writeUInt32LE(crc32(data), 16);
        central.writeUInt32LE(body.length, 20); central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(nameBytes.length, 28); central.writeUInt32LE(offset, 42);
        locals.push(local, nameBytes, body);
        centrals.push(central, nameBytes);
        offset += 30 + nameBytes.length + body.length;
    }
    const directory = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
    writeFileSync(path, Buffer.concat([...locals, directory, end]));
    return path;
}

function guard(jar) {
    const result = spawnSync(process.execPath, [GUARD, jar], { encoding: 'utf8' });
    return { status: result.status, output: result.stdout + result.stderr };
}

const work = mkdtempSync(join(tmpdir(), 'orderhub-isolation-'));

test('rejects a fixture class entry', () => {
    const result = guard(zip(join(work, 'fixture.jar'),
        [['BOOT-INF/classes/io/github/piresrenan/orderhub/development/DevelopmentIssuer.class', 'fixture']], true));
    assert.equal(result.status, 1);
    assert.match(result.output, /forbidden entry \(fixture package\): .*development\/DevelopmentIssuer\.class/);
});

test('rejects a fixture class name outside the fixture package and a seed resource', () => {
    const result = guard(zip(join(work, 'names.jar'), [
        ['BOOT-INF/classes/io/github/piresrenan/orderhub/other/DevelopmentSeedScenario$Boundaries.class', 'x'],
        ['BOOT-INF/classes/development/seed.json', '{}']], false));
    assert.equal(result.status, 1);
    assert.match(result.output, /fixture class\): .*DevelopmentSeedScenario\$Boundaries\.class/);
    assert.match(result.output, /fixture resource\): BOOT-INF\/classes\/development\/seed\.json/);
});

test('rejects a synthetic subject hidden in an ordinary entry', () => {
    const result = guard(zip(join(work, 'marker.jar'),
        [['BOOT-INF/classes/io/github/piresrenan/orderhub/orders/Innocent.class', 'subject synthetic-local-staff']], false));
    assert.equal(result.status, 1);
    assert.match(result.output, /forbidden marker "synthetic-local-" in .*orders\/Innocent\.class/);
});

test('accepts legitimate Development-named production classes', () => {
    const result = guard(zip(join(work, 'legitimate.jar'), [[
        'BOOT-INF/classes/io/github/piresrenan/orderhub/security/DevelopmentDocumentationSecurityConfiguration.class',
        'documentation posture']], true));
    assert.equal(result.status, 0, result.output);
    assert.match(result.output, /^PASS: production artifact contains no development seed classes\/resources/);
});

test('accepts every real main-output class and resource', () => {
    const root = join('target', 'classes');
    const files = [];
    const walk = directory => readdirSync(directory).forEach(name => {
        const path = join(directory, name);
        if (statSync(path).isDirectory()) walk(path); else files.push(path);
    });
    walk(root);
    assert.ok(files.length > 100, 'target/classes must be built first');
    const entries = files.map(path => ['BOOT-INF/classes/' + relative(root, path).split(sep).join('/'), readFileSync(path)]);
    const result = guard(zip(join(work, 'real.jar'), entries, true));
    assert.equal(result.status, 0, result.output);
});
