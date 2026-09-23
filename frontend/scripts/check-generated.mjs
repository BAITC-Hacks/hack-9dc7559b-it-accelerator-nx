import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
if (args.length && (args.length !== 2 || args[0] !== '--input')) {
  console.error('Usage: npm run gen:check -- [--input snapshot-path-or-live-url]');
  process.exit(1);
}
const input = args[1] ?? '../docs/api/openapi.json';
if (!/^https?:\/\//.test(input) && !existsSync(resolve(root, input))) {
  console.error(`Missing ${input}. UI-01 requires the springdoc snapshot from FOUND-01; do not handwrite a schema.`);
  process.exit(1);
}

function files(directory, prefix = '') {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(prefix, entry.name);
    return entry.isDirectory() ? files(join(directory, entry.name), path) : [path];
  });
}

// Generation is isolated. A failed drift check never overwrites src/client.
const scratch = mkdtempSync(join(tmpdir(), 'hackalem-sdk-check-'));
try {
  const output = join(scratch, 'client');
  execFileSync(process.execPath, [
    join(root, 'node_modules/@hey-api/openapi-ts/bin/run.js'),
    '--input', input, '--output', output, '--no-log-file',
  ], { cwd: root, stdio: 'inherit' });
  const checkedIn = join(root, 'src/client');
  const expected = files(output);
  const actual = files(checkedIn);
  const drift = [...new Set([...expected, ...actual])].filter((file) => {
    const generated = join(output, file);
    const tracked = join(checkedIn, file);
    return !existsSync(generated) || !existsSync(tracked) ||
      !readFileSync(generated).equals(readFileSync(tracked));
  });
  if (drift.length) {
    console.error(`Generated SDK drift (${relative(root, checkedIn)}):\n${drift.join('\n')}\nRegenerate with npm run gen -- --input ${input}`);
    process.exitCode = 1;
  } else {
    console.log('Generated SDK matches the contract.');
  }
} finally {
  // Only this process's mkdtemp output, never a repository/shared directory.
  rmSync(scratch, { recursive: true, force: true });
}
