#!/usr/bin/env bash
# 本地原型验证及CarPlay试验APK；不跑Lint、不连接、不执行目标、不安装。
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_dir"
trace_python="$repo_dir/.work/research/dim-native-venv/bin/python"
if [[ ! -x "$trace_python" ]]; then
  python3 -m venv "$repo_dir/.work/research/dim-native-venv"
fi
if ! "$trace_python" -c 'import lief; from elftools.elf.elffile import ELFFile' 2>/dev/null; then
  "$trace_python" -m pip install lief==0.17.1 pyelftools==0.33
fi
mkdir -p .work/kanzi-music .work/logs
clang -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I galaxy/common/src/main/cpp/kanzi_music \
  galaxy/common/src/main/cpp/kanzi_music/music_frame.c tools/kanzi_music/test_music_frame.c \
  -o .work/kanzi-music/test-frame
.work/kanzi-music/test-frame
clang -std=c11 -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music \
  -c galaxy/common/src/main/cpp/kanzi_music/music_frame.c -o .work/kanzi-music/host-frame.o
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined \
  -I galaxy/common/src/main/cpp/kanzi_music tools/kanzi_music/test_music_component.cpp \
  .work/kanzi-music/host-frame.o -o .work/kanzi-music/test-component
.work/kanzi-music/test-component
"$trace_python" scripts/build_kanzi_music.py "$@"
export ANDROID_HOME="${GALAXY_ANDROID_SDK:-$repo_dir/.work/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_dir/.work/gradle-home}"
export GALAXY_VERIFY_CACHE="$repo_dir/.work"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
./gradlew --offline --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -PgalaxyKanziMusicTrial=true -I scripts/verify/lib/robolectric-offline.init.gradle \
  :common:testDebugUnitTest --tests '*GalaxyKanziMusicFrameTest' --tests '*GalaxyOemLyricsTest' \
  --tests '*GalaxyNowPlayingIdentityTest' --tests '*L7MediaCenterSessionTest' :mobile:assembleDebug \
  2>&1 | tee .work/logs/verify-kanzi-music.log
python3 scripts/prepare_kanzi_music_trial.py
