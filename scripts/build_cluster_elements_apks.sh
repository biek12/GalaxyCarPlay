#!/usr/bin/env bash
# 固定固件只读构建→源码绑定→四包原签名构建；绝不部署/连接/安装。
set -euo pipefail
cd "$(dirname "$0")/.."
cluster_rootfs=${GALAXY_QNX_ROOTFS:-}
args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --rootfs) cluster_rootfs=$2; shift 2 ;;
    --resolve) args+=(--resolve); shift ;;
    *) echo '用法: bash scripts/build_cluster_elements_apks.sh --rootfs <已采集固定固件目录> [--resolve]' >&2;exit 2 ;;
  esac
done
[ -n "$cluster_rootfs" ] || { echo '缺少固定固件目录，不封装旧Hook' >&2;exit 2; }
python3 scripts/build_hab_probes.py --qnx-rootfs "$cluster_rootfs"
GALAXY_QNX_ROOTFS="$cluster_rootfs" python3 scripts/prepare_cluster_hook_asset.py --output galaxy/service/build/generated/cluster/assets/cluster
# Gradle只验证上一步产物，无须再次构建或读取固件。
unset GALAXY_QNX_ROOTFS
bash scripts/build_cluster_refactor_apks.sh ${args[@]+"${args[@]}"}
python3 - <<'PY'
import hashlib,json,zipfile,datetime
from pathlib import Path
root=Path.cwd();apk=root/'galaxy/service/build/outputs/apk/debug/service-debug.apk'
with zipfile.ZipFile(apk) as z:
    metadata=json.loads(z.read('assets/cluster/build.json'))
    assert hashlib.sha256(z.read('assets/cluster/libgalaxy_g733_cluster.so')).hexdigest()==metadata['hookSha256']
    assert z.read('assets/cluster/g733_hook_runtime.sh')==(root/'tools/research/g733_hook_runtime.sh').read_bytes()
    assert z.read('assets/qnx/receiver-candidate')==(root/'.work/hab-probes/qnx/galaxy_hab_qnx_probe-candidate').read_bytes()
    for path in metadata['sources']:
        assert hashlib.sha256((root/path).read_bytes()).hexdigest()==metadata['sources'][path]
    assert not any('/cluster-pipeline/' in name for name in z.namelist())
report=json.loads((root/'.work/cluster-refactor-build.json').read_text())
report.update(contract=6,hook=metadata['hookSha256'],installed=False,targetExecuted=False)
(root/'.work/cluster-elements-build.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Hook/控制脚本/接收端与最终APK绑定核验通过；未执行目标或安装。')
PY
