#!/usr/bin/env bash
# 公共UI/媒体/独立App本机回归；无Lint、模拟器、车辆连接或安装。
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$ROOT"
export ANDROID_HOME=${GALAXY_ANDROID_SDK:-$ROOT/.work/android-sdk}
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/.work/gradle-home}
if [ -z "${GALAXY_AAPT2:-}" ]; then GALAXY_AAPT2=$(python3 scripts/verify/lib/local_aapt2.py); fi
export GALAXY_AAPT2
export GALAXY_VERIFY_CACHE="$ROOT/.work"
export GALAXY_ROBOLECTRIC_STRICT_OFFLINE=1
mkdir -p .work/kanzi-music .work/logs
clang -std=c11 -Wall -Wextra -Werror -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music \
  galaxy/common/src/main/cpp/kanzi_music/music_frame.c tools/kanzi_music/test_music_frame.c -o .work/kanzi-music/test-frame
.work/kanzi-music/test-frame
clang -std=c11 -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music -c galaxy/common/src/main/cpp/kanzi_music/music_frame.c -o .work/kanzi-music/host-frame.o
clang++ -std=c++11 -DGKM_LEGACY_TEXT_LAYOUT -Wall -Wextra -Werror -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music \
  tools/kanzi_music/test_music_component.cpp .work/kanzi-music/host-frame.o -o .work/kanzi-music/test-component
.work/kanzi-music/test-component
clang++ -std=c++11 -Wall -Wextra -Werror -fsanitize=address,undefined -I galaxy/common/src/main/cpp/kanzi_music \
  tools/kanzi_music/test_compact_music.cpp .work/kanzi-music/host-frame.o -o .work/kanzi-music/test-compact
.work/kanzi-music/test-compact
ARGS=(--offline)
UI_ONLY=0
for option in "$@"; do
  case "$option" in
    --resolve) ARGS=() ;;
    --ui-only) UI_ONLY=1 ;;
    *) echo "用法: bash scripts/verify_cluster_refactor.sh [--resolve] [--ui-only]" >&2; exit 2 ;;
  esac
done
if [ -n "${GALAXY_AAPT2:-}" ]; then ARGS+=("-Pandroid.aapt2FromMavenOverride=$GALAXY_AAPT2"); fi
TASKS=(:auto-ui:testDebugUnitTest :auto-contracts:testDebugUnitTest :cluster:testDebugUnitTest
  :service:testDebugUnitTest --tests '*KanziMediaFrameTest' --tests '*ClientRegistryTest' --tests '*ClusterElementPipelineTest' --tests '*ClusterHookLifecycleTest'
  :common:testDebugUnitTest --tests '*GalaxyLyricsProviderTest' --tests '*GalaxyNowPlayingIdentityTest' --tests '*GalaxyKanziMusicFrameTest'
  :service:compileDebugKotlin :mobile:compileDebugKotlin :cluster:processDebugResources)
if [ "$UI_ONLY" = 1 ]; then TASKS=(:auto-ui:testDebugUnitTest :cluster:testDebugUnitTest --tests '*ClusterAppNavigationTest'); fi
./gradlew ${ARGS[@]+"${ARGS[@]}"} -Pkotlin.incremental=false --no-configuration-cache --no-daemon --console=plain -Pandroid.builder.sdkDownload=false \
  -I scripts/verify/lib/maven-mirror.init.gradle -I scripts/verify/lib/robolectric-offline.init.gradle \
  "${TASKS[@]}" \
  2>&1 | tee .work/logs/verify-cluster-refactor.log
