#!/usr/bin/env node
// 只读提取APK的DEX字符串表与Manifest。仅输出ECARX action/按键extra，不导出其它字符串。
// 用于离车研究，不注册接收器、不发广播、不进入APK；失败与未覆盖项保留在报告中。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const args = process.argv.slice(2), inputs = [];
let output, aapt2;
for (let i = 0; i < args.length; i++) {
  const flag = args[i], value = args[++i];
  if (!value) throw new Error(`missing value for ${flag}`);
  if (flag === '--current' || flag === '--reference') inputs.push({ origin: flag.slice(2), dir: path.resolve(value) });
  else if (flag === '--output') output = path.resolve(value);
  else if (flag === '--aapt2') aapt2 = path.resolve(value);
  else throw new Error(`unknown option ${flag}`);
}
if (!output || !inputs.length || !aapt2) {
  throw new Error('usage: node scan_ecarx_key_actions.cjs --current DIR [--reference DIR] --aapt2 FILE --output JSON');
}
const report = { schema: 1, createdAt: new Date().toISOString(), scope: '指定目录顶层APK；DEX全部字符串与Manifest，非全车固件声明', packages: [], actions: [], extras: [], errors: [] };
const actions = new Map(), extras = new Map();
function record(value, location) {
  // 常量、日志文本中的完整action均保留；字符串存在不等于发送/接收逻辑可达。
  const found = new Set(value.match(/ecarx\.intent\.action(?:\.[A-Za-z0-9_$]+)*/g) || []);
  for (const name of found) {
    if (!actions.has(name)) actions.set(name, []);
    actions.get(name).push(location);
  }
  for (const name of new Set(value.match(/ecarx\.extra\.[A-Za-z0-9_.$]*KEY[A-Za-z0-9_.$]*/g) || [])) {
    if (!extras.has(name)) extras.set(name, []);
    extras.get(name).push(location);
  }
}
function dexStrings(bytes, location) {
  if (bytes.subarray(0, 4).toString() !== 'dex\n' || bytes.length < 112) throw new Error('invalid_dex');
  const count = bytes.readUInt32LE(56), table = bytes.readUInt32LE(60);
  if (table + count * 4 > bytes.length) throw new Error('invalid_string_table');
  for (let i = 0; i < count; i++) {
    const offset = bytes.readUInt32LE(table + i * 4);
    let start = offset, lengthBytes = 0;
    while (start < bytes.length && lengthBytes++ < 5 && (bytes[start++] & 128)) {}
    if (lengthBytes > 5 || start >= bytes.length) throw new Error('invalid_string_length');
    const end = bytes.indexOf(0, start);
    if (end < 0) throw new Error('unterminated_string');
    // 目标命名空间均为ASCII；其识别不依赖MUTF-8的非ASCII字符解码。
    record(bytes.toString('utf8', start, end), { ...location, stringIndex: i, dataOffset: offset });
  }
  return count;
}
for (const input of inputs) {
  for (const entry of fs.readdirSync(input.dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if (!entry.isFile() || !entry.name.endsWith('.apk')) continue;
    const file = path.join(input.dir, entry.name), bytes = fs.readFileSync(file);
    const info = { file, origin: input.origin, sha256: crypto.createHash('sha256').update(bytes).digest('hex'), dex: [], manifest: false };
    report.packages.push(info);
    try {
      const names = execFileSync('unzip', ['-Z1', file], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }).split('\n');
      for (const name of names.filter(name => /^classes(?:\d+)?\.dex$/.test(name))) {
        const dex = execFileSync('unzip', ['-p', file, name], { maxBuffer: 128 * 1024 * 1024 });
        info.dex.push({ name, strings: dexStrings(dex, { apk: file, origin: input.origin, section: name }) });
      }
      const xml = execFileSync(aapt2, ['dump', 'xmltree', file, '--file', 'AndroidManifest.xml'], { encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
      xml.split('\n').forEach((line, index) => record(line, { apk: file, origin: input.origin, section: 'AndroidManifest.xml', dumpLine: index + 1 }));
      info.manifest = true;
    } catch (error) { report.errors.push({ file, stage: 'extract', error: error.code || error.name }); }
  }
}
report.actions = [...actions].sort(([a], [b]) => a.localeCompare(b)).map(([name, locations]) => ({ name, keyCandidate: /KEY|BUTTON|TRIP|SRC|MENU/.test(name), locations }));
report.extras = [...extras].sort(([a], [b]) => a.localeCompare(b)).map(([name, locations]) => ({ name, locations }));
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ output, packages: report.packages.length, dex: report.packages.reduce((n, p) => n + p.dex.length, 0), actions: report.actions.length, keyCandidates: report.actions.filter(a => a.keyCandidate).map(a => a.name), errors: report.errors }));
