#!/usr/bin/env bash
# Lead审核冻结候选；仅本机编译/单测，不连接车辆、不将候选打包进产品。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
export ANDROID_HOME="$ROOT/.work/android-sdk" ANDROID_SDK_ROOT="$ROOT/.work/android-sdk"
export ANDROID_PREFS_ROOT="$ROOT/.work/android-prefs" GRADLE_USER_HOME="$ROOT/.work/gradle-home"
export GALAXY_VERIFY_CACHE="$ROOT/.work" GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
[ -s "$ANDROID_PREFS_ROOT/.android/debug.keystore" ] || { echo '缺少原工程签名，拒绝自动生成' >&2; exit 2; }
python3 - <<'PY'
from pathlib import Path
import re,hashlib,json
p=Path('.workbuddy/tasks/autorecord-storage-20261104')
if not re.search(r'状态[：:]\s*\**READY_FOR_REVIEW', (p/'STATUS.md').read_text()):
    raise SystemExit('候选尚未冻结为READY_FOR_REVIEW，禁止边写边验')
if not list((p/'test').rglob('*Test.kt')):
    raise SystemExit('缺少候选失败路径测试')
for f in p.rglob('*'):
    if f.is_symlink():raise SystemExit('候选不得包含符号链接')
    if f.is_file() and f.suffix not in ('.kt','.md'):raise SystemExit('候选包含非预期文件类型')
snapshot={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in p.rglob('*') if f.is_file()}
out=Path('.work/workbuddy-storage-review');out.mkdir(parents=True,exist_ok=True)
(out/'frozen.json').write_text(json.dumps(snapshot,sort_keys=True,indent=2)+'\n')
print('候选状态与文件边界检查通过')
PY
"$ROOT/gradlew" --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
    -Pkotlin.compiler.execution.strategy=in-process \
    -I "$ROOT/scripts/verify/lib/robolectric-offline.init.gradle" \
    -I "$ROOT/scripts/verify/lib/workbuddy-storage.init.gradle" \
    :record:testDebugUnitTest --tests 'com.galaxy.autorecord.storage.*'
python3 - <<'PY'
from pathlib import Path
import hashlib,json
p=Path('.workbuddy/tasks/autorecord-storage-20261104')
before=json.loads(Path('.work/workbuddy-storage-review/frozen.json').read_text())
after={str(f.relative_to(p)):hashlib.sha256(f.read_bytes()).hexdigest() for f in p.rglob('*') if f.is_file() and not f.is_symlink()}
if before!=after:raise SystemExit('候选在验证期间发生变化，本次结果不接受')
print('候选摘要未变；单测结果可归属该冻结批次，尚不代表集成/实车验收')
PY
