/* SPDX-License-Identifier: GPL-3.0-only */
/* Reuse the live component's four-line layout and reversible node transaction. */
#include <stddef.h>
#include <stdint.h>
extern "C" {
#include <string.h>
#include <unistd.h>
}
inline void *operator new(size_t, void *memory) noexcept { return memory; }
namespace g733_music {
#include "music-reuse.cpp"
#include "background_runtime.h"
}
extern "C" void g733_music_bind(uintptr_t);
static bool initialized, expired, render_pending;
static long elapsed, next_scan;
static bool runtime_mode;
extern "C" void g733_music_runtime_enable() { runtime_mode = true; }

extern "C" void g733_music_trial_shutdown(void *) {
    using namespace g733_music;
    background_restore();
    if (!state)
        return;
    restore(state);
    state->~State();
    free(state);
    state = nullptr;
    __atomic_store_n(&render_pending,false,__ATOMIC_RELEASE);
    const char *line = "G733_MUSIC_RESTORED\n";
    (void)::write(2, line, ::strlen(line));
}

extern "C" void g733_music_trial_update(void *app, long delta_ms, uintptr_t base) {
    using namespace g733_music;
    if (expired)
        return;
    if (!initialized) {
        g733_music_bind(base);
        initialized = true;
        const char *line = "G733_MUSIC_CALLBACK\n";
        (void)::write(2, line, ::strlen(line));
    }
    if (delta_ms > 0)
        elapsed += delta_ms > 500 ? 500 : delta_ms;
    if (runtime_mode) {
        hook_status();
        hook_refresh();
        background_update(app,base,elapsed);
        if (!(hook_flags&1)) { if(state) { restore(state); state->~State(); free(state); state=nullptr; } return; }
    }
    if (!runtime_mode && elapsed >= 9000) {
        g733_music_trial_shutdown(app);
        expired = true;
        return;
    }
#ifdef G733_LIVE_MUSIC
    galaxy_music_on_update(app, delta_ms);
    if (state && state->mounted && !__atomic_load_n(&render_pending,__ATOMIC_ACQUIRE)) {
        static bool reported;
        if (!reported) { reported=true; __atomic_store_n(&render_pending,true,__ATOMIC_RELEASE); (void)::write(2,"G733_MUSIC_APPLIED\n",19); }
    }
    return;
#endif
    if (!state) {
        void *memory = malloc(sizeof(State));
        if (!memory)
            return;
        state = new (memory) State{};
    }
    Ref root = get_root(app);
    if (state->mounted) {
        if (state->root.object != root.object || !music_visible(state->target.object) ||
            !state->replacement.owns_slot()) {
            g733_music_trial_shutdown(app);
            expired = true;
        }
        return;
    }
    if (elapsed < next_scan)
        return;
    next_scan = elapsed + 500;
    if (!mount(state, app, root))
        return;
    const char *labels[] = {
        "GalaxyPlay 音乐", "四行歌词 · 驻车测试", "上句 · 已播放",
        "当前句 · 高亮显示", "下句 · 待播放", "再下句 · 待播放",
        "00:12 / 03:45 · 测试数据"
    };
    for (unsigned i = 0; i < 7; ++i)
        text(state->labels[i].object, labels[i]);
    const char *line = "G733_MUSIC_APPLIED\n";
    (void)::write(2, line, ::strlen(line));
    __atomic_store_n(&render_pending,true,__ATOMIC_RELEASE);
}

extern "C" void g733_music_trial_after_swap() {
    if(runtime_mode)g733_music::hook_after_swap();
    if (__atomic_exchange_n(&render_pending,false,__ATOMIC_ACQ_REL)) {
        const char *line = "G733_MUSIC_EGL_SWAP\n";
        (void)::write(2, line, ::strlen(line));
    }
}
