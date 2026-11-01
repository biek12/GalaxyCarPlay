#!/usr/bin/env bash
# 上车前完整离线入口；不连接设备、不安装、不运行Lint。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
exec python3 scripts/cluster_preflight.py "$@"
