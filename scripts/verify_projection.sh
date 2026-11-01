#!/usr/bin/env bash
# 投屏优化本机回归/三个APK/合成帧对照；不运行Lint、不连接设备。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
python3 scripts/verify_hab_native.py --android
bash scripts/verify_autoservice_remediation.sh
python3 -m unittest scripts.tests.test_autoservice_measure
sh -n tools/hab/snapshot-qnx.sh
python3 scripts/benchmark_projection.py
