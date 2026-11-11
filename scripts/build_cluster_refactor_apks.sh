#!/usr/bin/env bash
# 仅构建自有同签名候选，不连接、不安装；缺少原工程签名即停止。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$ROOT/.work/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/.work/gradle-home}
if [ -z "${GALAXY_AAPT2:-}" ]; then GALAXY_AAPT2=$(python3 scripts/verify/lib/local_aapt2.py); fi
export GALAXY_AAPT2
export ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
test -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" || { echo '缺少工程原签名，停止' >&2; exit 2; }
ARGS=(--offline)
for option in "$@";do
  case "$option" in --resolve) ARGS=();; *) echo '用法: bash scripts/build_cluster_refactor_apks.sh [--resolve]' >&2;exit 2;; esac
done
if [ -n "${GALAXY_AAPT2:-}" ];then ARGS+=("-Pandroid.aapt2FromMavenOverride=$GALAXY_AAPT2");fi
mkdir -p .work/logs
./gradlew ${ARGS[@]+"${ARGS[@]}"} -Pkotlin.incremental=false --no-configuration-cache --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/maven-mirror.init.gradle \
  :mobile:assembleDebug :service:assembleDebug :cluster:assembleDebug :record:assembleDebug \
  2>&1 | tee .work/logs/build-cluster-refactor-apks.log
python3 - <<'PY'
from pathlib import Path
import subprocess,hashlib,re,json,datetime
root=Path.cwd();keystore=root/'.work/android-prefs/.android/debug.keystore'
cert=subprocess.run(['keytool','-exportcert','-keystore',str(keystore),'-storepass','android','-alias','androiddebugkey'],check=True,capture_output=True).stdout
expected=hashlib.sha256(cert).hexdigest()
tool=root/'.work/android-sdk/build-tools/36.0.0/apksigner'
outputs={}
for module in ['mobile','service','cluster','record']:
 path=root/f'galaxy/{module}/build/outputs/apk/debug/{module}-debug.apk'
 result=subprocess.run([str(tool),'verify','--print-certs',str(path)],check=True,capture_output=True,text=True).stdout
 actual=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)',result).group(1).lower()
 assert actual==expected,f'{module}: signature mismatch'
 outputs[module]={'path':str(path.relative_to(root)),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'sameOriginalSignature':True}
report={'builtAt':datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),'contract':6,'installed':False,'outputs':outputs}
p=root/'.work/cluster-refactor-build.json';p.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('四个候选APK与原工程签名匹配；未安装。摘要：'+str(p.relative_to(root)))
PY
