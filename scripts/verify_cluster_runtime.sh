#!/usr/bin/env bash
# 本机帧合同/资源恢复验证，不启动模拟器或连接设备。
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .work/kanzi-music
clang -std=c11 -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music -c galaxy/common/src/main/cpp/kanzi_music/music_frame.c -o .work/kanzi-music/runtime-frame.o
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined tools/kanzi_music/test_background_runtime.cpp .work/kanzi-music/runtime-frame.o -o .work/kanzi-music/test-background-runtime
.work/kanzi-music/test-background-runtime
clang -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -I galaxy/service/src/main/cpp/hab -I galaxy/service/src/test/cpp/hab/qnx_stubs tools/hab/test_texture_consumer.c -o .work/kanzi-music/test-texture-consumer
.work/kanzi-music/test-texture-consumer
python3 scripts/verify_hab_native.py
