#!/usr/bin/env node
// 只封装本机已完成assembleDebug的产物；校对包内接收端/发送端与源码摘要，不连接设备。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = file => fs.readFileSync(path.join(root, file));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
}).formatToParts(new Date()).map(p => [p.type, p.value]));
const stamp = `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}${parts.second}`;
const destination = path.join(root, '.work/vehicle-test', `hab-transfer-fix-${stamp}`);
const apkPath = 'galaxy/service/build/outputs/apk/debug/service-debug.apk';
const zipEntry = entry => execFileSync('unzip', ['-p', path.join(root, apkPath), entry], { maxBuffer: 64 * 1024 * 1024 });
const build = JSON.parse(read('.work/hab-probes/build-report.json'));
const protocol = Number(read('galaxy/service/src/main/cpp/hab/galaxy_hab_owned.h').toString()
  .match(/#define GALAXY_HAB_OWNED_VERSION (\d+)u/)?.[1]);
if (protocol !== 2) throw new Error('this transfer fix package requires GLC2 v2');
for (const [file, expected] of Object.entries(build.source_sha256)) {
  if (sha(read(file)) !== expected) throw new Error(`source changed after native build: ${file}`);
}
const receiver = zipEntry('assets/qnx/receiver-candidate');
const receiverInfo = JSON.parse(zipEntry('assets/qnx/build.json'));
if (sha(receiver) !== receiverInfo.sha256 ||
    sha(read('.work/hab-probes/qnx/galaxy_hab_qnx_probe-candidate')) !== receiverInfo.sha256) {
  throw new Error('APK receiver does not match compiled QNX candidate');
}
const native = zipEntry('lib/arm64-v8a/libgalaxy_hab_bridge.so');
const stripped = 'galaxy/service/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libgalaxy_hab_bridge.so';
if (sha(native) !== sha(read(stripped))) throw new Error('APK sender does not match assembled native library');
const buildLog = '.work/logs/assemble-hab-transfer-fix-20261030.log';
if (!read(buildLog).toString().includes('BUILD SUCCESSFUL')) throw new Error('assembleDebug success record missing');
const certificate = execFileSync(path.join(root, '.work/android-sdk/build-tools/36.0.0/apksigner'),
  ['verify', '--print-certs', path.join(root, apkPath)], { encoding: 'utf8' });
const signer = certificate.match(/Signer #1 certificate SHA-256 digest: ([0-9a-f]+)/i)?.[1];
if (!signer) throw new Error('APK signer verification failed');
const sourceFiles = [
  ...Object.keys(build.source_sha256),
  'galaxy/service/src/main/java/com/ecarx/autoservice/projection/GalaxyHabProcess.kt',
  'galaxy/service/build.gradle.kts', 'scripts/build_hab_probes.py',
  'scripts/build_hab_bridge.py', 'scripts/prepare_auto_qnx_asset.py',
  'scripts/package_hab_transfer_fix.cjs',
];
// Java改动也必须早于这份APK；这里不把未编译的源码包装成已编译修复。
if (fs.statSync(path.join(root, sourceFiles.find(f => f.endsWith('GalaxyHabProcess.kt')))).mtimeMs >
    fs.statSync(path.join(root, apkPath)).mtimeMs) throw new Error('Kotlin source newer than APK');
fs.mkdirSync(path.dirname(destination), { recursive: true });
fs.mkdirSync(destination, { recursive: false });
function write(name, bytes) { fs.writeFileSync(path.join(destination, name), bytes); }
write('AutoService.apk', read(apkPath));
write('qnx-receiver-candidate', receiver);
write('native-build-report.json', JSON.stringify(build, null, 2) + '\n');
write('assembleDebug.log', read(buildLog));
write('修复说明.md', read('docs/仪表传输断流修复包-20261030.md'));
const sources = {};
for (const file of sourceFiles) {
  const bytes = read(file), to = path.join(destination, 'sources', file);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.writeFileSync(to, bytes);
  sources[file] = sha(bytes);
}
const artifacts = ['AutoService.apk', 'qnx-receiver-candidate', 'native-build-report.json', 'assembleDebug.log', '修复说明.md']
  .map(name => { const bytes = fs.readFileSync(path.join(destination, name)); return { name, bytes: bytes.length, sha256: sha(bytes) }; });
write('manifest.json', JSON.stringify({
  schema: 1, createdAt: new Date().toISOString(), applicationId: 'com.ecarx.autoservice',
  ownedProtocolVersion: protocol, frameChecksum: 'sampled-every-frame',
  vehicleValidated: false, unitTestsRun: false, lintRun: false,
  qnxBuildClass: receiverInfo.build_class, qnxSdkBuildComplete: build.qnx_sdk_build_complete,
  signerCertificateSha256: signer, artifacts,
  apkEntries: { receiverSha256: sha(receiver), nativeSenderSha256: sha(native) },
  sources,
}, null, 2) + '\n');
write('SHA256SUMS', [...artifacts.map(a => `${a.sha256}  ${a.name}`),
  `${sha(fs.readFileSync(path.join(destination, 'manifest.json')))}  manifest.json`].join('\n') + '\n');
write('README.txt', `仪表传输断流修复候选包\n\n安装AutoService.apk即可；QNX接收端也已内置在APK，按现有授权流程自动校验/更新。\n保留CarPlay和AutoCluster当前数据与签名，不替换原车HMI或启动项。\n设备内同包更新命令：pm install -r AutoService.apk\n\nGLC2 v2：导入cache同步、私有快照、每帧采样校验、同帧最多重传两次。\n旧v1握手会失败并释放，然后进入既有Root/QNX接收端准备流程。\n\n本包仅完成assembleDebug和产物绑定，未安装/实车验证；原车告警是否消失待单独验收。\n详见修复说明.md；manifest.json记录APK、接收端、签名公钥摘要与源码摘要。\n`);
const archive = destination + '.zip';
execFileSync('zip', ['-qr', archive, path.basename(destination)], { cwd: path.dirname(destination) });
console.log(JSON.stringify({ directory: destination, archive, archiveSha256: sha(fs.readFileSync(archive)),
  apkSha256: artifacts[0].sha256, receiverSha256: sha(receiver), protocol, vehicleValidated: false }));
