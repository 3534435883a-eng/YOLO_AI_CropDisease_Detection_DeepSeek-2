import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { deflateRawSync } from 'node:zlib';

// Explicit entry lists; never copy a live workspace or credential directory into a release.
const moduleRoot = fileURLToPath(new URL('../', import.meta.url));
const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));
const output = resolve(process.argv[2] || fileURLToPath(new URL('../.state/release/', import.meta.url)));
const skillFiles = ['SKILL.md', 'scripts/client.mjs', 'scripts/report.mjs', 'scripts/decision.mjs', 'references/usage.md', 'references/goals.md', 'references/goal-integral.json', 'references/goal-strict.json'];
const hash = data => createHash('sha256').update(data).digest('hex');
const table = Array.from({ length: 256 }, (_, v) => { for (let i = 0; i < 8; i++) v = (v & 1) ? (0xedb88320 ^ (v >>> 1)) : (v >>> 1); return v >>> 0; });
const crc = data => { let v = 0xffffffff; for (const byte of data) v = table[(v ^ byte) & 255] ^ (v >>> 8); return (v ^ 0xffffffff) >>> 0; };
function zip(entries) {
  const chunks = [], directory = []; let offset = 0;
  for (const { name, data } of entries) {
    const filename = Buffer.from(name), compressed = deflateRawSync(data), checksum = crc(data);
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x800, 6); local.writeUInt16LE(8, 8); local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(checksum, 14); local.writeUInt32LE(compressed.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(filename.length, 26);
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50, 0); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0x800, 8); central.writeUInt16LE(8, 10); central.writeUInt16LE(0x21, 14);
    central.writeUInt32LE(checksum, 16); central.writeUInt32LE(compressed.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    chunks.push(local, filename, compressed); directory.push(central, filename);
    offset += local.length + filename.length + compressed.length;
  }
  const centralBytes = Buffer.concat(directory), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(centralBytes.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, centralBytes, end]);
}

await mkdir(output, { recursive: true });
const skills = await Promise.all(skillFiles.map(async name => ({ name, data: await readFile(resolve(moduleRoot, 'skill/agri-research', name)) })));
let secret = '';
try { secret = /^DUMATE_TOKEN=(.+)$/m.exec(await readFile(resolve(moduleRoot, '.env'), 'utf8'))?.[1].trim() || ''; } catch {}
const names = [...new Set(execFileSync('git', ['ls-files', '-z', '-co', '--exclude-standard', '--', 'integrations/dumate/'], { cwd: repoRoot, encoding: 'utf8' }).split('\0').filter(Boolean))].sort();
const sources = [];
for (const name of names) {
  if (!name.startsWith('integrations/dumate/')) throw new Error('Unexpected source outside module');
  const rel = name.slice('integrations/dumate/'.length);
  if (/^\.state\/|(?:^|\/)\.env$|node_modules|credentials|\.local$/.test(rel)) throw new Error('Private entry refused');
  const data = await readFile(resolve(repoRoot, name));
  if (secret && data.includes(Buffer.from(secret))) throw new Error('Credential detected in source');
  sources.push({ name: `source/${name}`, data });
}
for (const file of skills) if (secret && file.data.includes(Buffer.from(secret))) throw new Error('Credential detected in skill');
const skillZip = zip(skills), sourceZip = zip(sources);
await writeFile(resolve(output, 'agri-research.zip'), skillZip);
await writeFile(resolve(output, 'dumate-integration.zip'), sourceZip);
const manifest = { version: '0.3.0', generatedAt: new Date().toISOString(),
  sourceCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: repoRoot, encoding: 'utf8' }).trim(),
  skill: { file: 'agri-research.zip', sha256: hash(skillZip), files: skills.map(e => ({ path: e.name, sha256: hash(e.data) })) },
  source: { file: 'dumate-integration.zip', sha256: hash(sourceZip), fileCount: sources.length },
  credentialsIncluded: false, workspacePath: relative(repoRoot, moduleRoot).replace(/\\/g, '/'),
};
const deliverableNames = ['禾序农业研究助手-应用方案.pdf', '作品简介.md', '演示讲稿与录屏操作表.md', '答辩问题与回答.md', '提交清单与使用入口.md', '给DuMate的启动任务.txt', '交付入口.md', '验收报告.html', '目标决策演示提示词.txt', '目标决策验收.json', '积分约束实验报告.html', '严格约束实验报告.html', 'GitHub相近项目调研.md'];
const deliverables = [];
for (const name of deliverableNames) {
  try {
    const data = await readFile(resolve(output, name));
    if (secret && data.includes(Buffer.from(secret))) throw new Error('Credential detected in material');
    deliverables.push({ name, data });
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
}
manifest.deliverables = deliverables.map(e => ({ file: e.name, bytes: e.data.length, sha256: hash(e.data) }));
await writeFile(resolve(output, 'release-manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);
if (deliverables.length === deliverableNames.length) {
  const bundle = zip([{ name: 'agri-research.zip', data: skillZip }, { name: 'dumate-integration.zip', data: sourceZip },
    ...deliverables, { name: 'release-manifest.json', data: Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`) }]);
  await writeFile(resolve(output, 'ican-submission-bundle.zip'), bundle);
  await writeFile(resolve(output, 'ican-submission-bundle.sha256'), `${hash(bundle)}  ican-submission-bundle.zip\n`);
}
console.log(JSON.stringify({ output, skillFiles: skills.length, sourceFiles: sources.length, credentialsIncluded: false }));
