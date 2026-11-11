#!/usr/bin/env bash
# 完整图片/媒体接入离线回归；不连接设备、不安装、不启动模拟器。
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/verify_cluster_runtime.sh
bash scripts/verify_cluster_refactor.sh "$@"
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined tools/kanzi_music/test_element_pipeline.cpp .work/kanzi-music/runtime-frame.o -o .work/kanzi-music/test-element-pipeline
.work/kanzi-music/test-element-pipeline
python3 tools/research/test_g733_hook_runtime.py
