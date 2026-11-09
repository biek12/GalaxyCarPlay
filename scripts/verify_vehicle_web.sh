#!/usr/bin/env bash
# 本机单命令验证，不启动模拟器、不连接车辆、不跑Lint。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export GRADLE_USER_HOME="$ROOT/.work/gradle-home" ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs"
export GALAXY_VERIFY_CACHE="$ROOT/.work" GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
test -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" || { echo '缺少工程原签名，停止' >&2; exit 2; }
python3 -m unittest discover -s "$ROOT/scripts" -p test_remote_session.py
(cd "$ROOT/server/galaxyd" && go test -race ./...)
(cd "$ROOT/web" && node --test tests/*.test.mjs && npm exec -- vue-tsc --noEmit && npm run build)
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false -Pkotlin.compiler.execution.strategy=in-process \
 -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" :auto-client:testDebugUnitTest :service:testDebugUnitTest :record:testDebugUnitTest :service:assembleDebug :record:assembleField

python3 - "$ROOT/galaxy/record/build/outputs/apk/field/record-field.apk" <<'PYVERIFY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    dex=b"".join(apk.read(name) for name in apk.namelist() if name.startswith('classes') and name.endswith('.dex'))
    for cls in (b'Lorg/webrtc/PeerConnectionFactory;',b'Lorg/jni_zero/JniZero;',b'Lorg/jni_zero/CommonApis;'):
        if cls not in dex:raise SystemExit('WebRTC JNI class missing from APK: '+cls.decode())
    if 'lib/arm64-v8a/libjingle_peerconnection_so.so' not in apk.namelist():raise SystemExit('WebRTC arm64 native library missing')
print('Field APK WebRTC JNI names verified')
PYVERIFY
