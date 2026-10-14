import { execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, relative, sep } from 'node:path';
import { createHash } from 'node:crypto';

// Restore committed bytes only, so Git's Windows CRLF checkout cannot invalidate the dataset digest.
const repo = fileURLToPath(new URL('../../../', import.meta.url));
const target = fileURLToPath(new URL('../.state/m3/', import.meta.url));
const prefix = 'assets/datasets/horti-m3/';
const files = execFileSync('git', ['ls-tree', '-r', '--name-only', 'HEAD', '--', prefix], { cwd: repo, encoding: 'utf8' }).trim().split('\n');
for (const path of files) {
  if (!path.startsWith(prefix) || !/\/(normalized|runs)\//.test(path) || !path.endsWith('.json')) continue;
  const destination = resolve(target, path.slice(prefix.length));
  if (relative(target, destination).startsWith(`..${sep}`)) throw new Error('Reference path outside adapter state');
  const bytes = execFileSync('git', ['show', `HEAD:${path}`], { cwd: repo, maxBuffer: 32 * 1024 * 1024 });
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, bytes);
}
const manifest = JSON.parse(await readFile(resolve(target, 'normalized/multiyear-manifest.json'), 'utf8'));
const bytes = await readFile(resolve(target, 'normalized/multiyear-observations.json'));
const actual = createHash('sha256').update(bytes).digest('hex');
if (actual !== manifest.observationsSha256) throw new Error('Committed observation checksum differs from manifest; do not bypass verification');
console.log(JSON.stringify({ restoredFrom: 'HEAD git blobs', observationsSha256: actual, target }));
