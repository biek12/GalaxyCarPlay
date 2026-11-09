#!/usr/bin/env bash
# 本地材料静态追踪；不执行目标ELF、不连接车辆、不进入APK。
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
trace_venv="$repo_dir/.work/research/dim-native-venv"
if [[ ! -x "$trace_venv/bin/python" ]]; then
  python3 -m venv "$trace_venv"
fi
if ! "$trace_venv/bin/python" -c 'from importlib.metadata import version; assert version("pyelftools") == "0.33"; assert version("capstone") == "5.0.9"' 2>/dev/null; then
  "$trace_venv/bin/python" -m pip install pyelftools==0.33 capstone==5.0.9
fi
cd "$repo_dir"
exec "$trace_venv/bin/python" tools/research/l6_dim_static_trace.py "$@"
