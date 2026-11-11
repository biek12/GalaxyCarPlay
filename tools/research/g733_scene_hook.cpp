/* SPDX-License-Identifier: GPL-3.0-only */
/* Fixed G733 Kanzi ABI. This probe runs its scene work on the original UI thread. */
#include <stddef.h>
#include <stdint.h>
extern "C" {
#include <string.h>
#include <unistd.h>
}

struct View { const char *data; size_t size; };
struct Handle { void *node; void *control; };
static void (*release_ref)(void *);

/* A non-trivial 16-byte result uses the AArch64 x8 return slot, like the OEM shared_ptr. */
struct Ref : Handle {
    Ref() : Handle{} {}
    Ref(Ref &&other) : Handle{other.take()} {}
    ~Ref() { if (control) release_ref(control); }
    Ref &operator=(Ref &&other) {
        if (control) release_ref(control);
        static_cast<Handle &>(*this) = other.take();
        return *this;
    }
    Handle take() {
        Handle result{node, control};
        node = control = nullptr;
        return result;
    }
};
static_assert(sizeof(Ref) == 16, "G733 shared_ptr size");

struct SceneApi {
    Ref (*root)(void *);
    size_t (*count)(void *);
    Ref (*child)(void *, size_t);
    Ref (*scene)(void *);
    bool (*named)(void *, View);
    bool (*visible)(void *);
    bool (*bound)(void *);
    void *(*entry)(void *);
    bool (*local_false)(void *);
    void (*hide)(void *);
    void (*remove)(void *);
};
static SceneApi api;

#define MAIN "Contents2D/"
#define ACTIVE MAIN "RootCtrl/E_POWER_STATUS_ACTIVE/"
#define DRIVING MAIN "RootCtrl/E_POWER_STATUS_DRIVING/CtrlNode/"
#define SR3 DRIVING "module_ADAS3D/ADAS3D/"
#define SR2 DRIVING "module_ADAS2D/ADAS2D/"
#define SR_SCENE SR3 "Viewport 2D/Scene/"
static const char *const paths[] = {
    MAIN "module_BG/BG", DRIVING "module_ADAS2D", DRIVING "module_ADAS3D",
    ACTIVE "module_ADAS2D", ACTIVE "module_ADAS3D",
    DRIVING "module_Warning", ACTIVE "module_Warning",
    DRIVING "moduleBgRoad/BgRoad",
    DRIVING "module_BackGround/Background", DRIVING "module_BackgroundDockBar",
    DRIVING "module_Gauge", SR3 "Viewport 2D/Scene", SR3 "Override",
    SR_SCENE "Lane", SR_SCENE "Line", SR_SCENE "TimeGap", SR_SCENE "LaneShadow",
    SR_SCENE "MovObjec", SR_SCENE "StaObjec", SR_SCENE "MyCar",
    SR2 "TLA_LAMP", SR2 "MiniAdas",
};
static constexpr unsigned paint_mask = 1, driving_sr = 0x06, active_sr = 0x18;
static constexpr unsigned driving_warning = 0x20, active_warning = 0x40;
static constexpr unsigned writable_paths = 5;
static constexpr unsigned path_count = sizeof(paths) / sizeof(paths[0]);
static_assert(path_count <= 32, "Probe masks are 32-bit");

static void message(const char *line) { (void)write(2, line, strlen(line)); }

static void masks(const char *prefix, unsigned first, unsigned second,
                  unsigned third, unsigned fourth) {
    char line[100];
    size_t n = strlen(prefix);
    memcpy(line, prefix, n);
    const unsigned values[] = {first, second, third, fourth};
    for (unsigned v : values) {
        line[n++] = ' ';
        for (int i = 28; i >= 0; i -= 4)
            line[n++] = "0123456789abcdef"[(v >> i) & 15];
    }
    line[n++] = '\n';
    (void)write(2, line, n);
}

static Ref unique_child(void *node, View name, unsigned &budget) {
    Ref result;
    if (name.size == 5 && !memcmp(name.data, "Scene", 5)) {
        Ref scene = api.scene(node);
        if (scene.node && api.named(scene.node, name))
            return scene;
    }
    size_t count = api.count(node);
    if (count > budget)
        return {};
    budget -= (unsigned)count;
    for (size_t i = 0; i < count; ++i) {
        Ref child = api.child(node, i);
        if (!child.node || !api.named(child.node, name))
            continue;
        if (result.node)
            return {}; // ambiguous siblings
        result = static_cast<Ref &&>(child);
    }
    return result;
}

static Ref resolve(void *root, const char *path, unsigned &budget) {
    Ref result;
    void *parent = root;
    while (*path && parent) {
        const char *end = path;
        while (*end && *end != '/')
            ++end;
        result = unique_child(parent, {path, (size_t)(end - path)}, budget);
        parent = result.node;
        path = *end ? end + 1 : end;
    }
    return result;
}

struct Change { Handle target; void *entry; };
static Change changes[3];
static unsigned requested, applied, last_found, last_visible, last_values, last_bound;
static unsigned ticks;
static long elapsed, next_scan;
static Handle active_root;
static bool attempted, scanned, expired;
static unsigned render_pending;
#ifdef G733_MUSIC_PROBE
static bool music_mode;
extern "C" void g733_music_runtime_enable();
extern "C" void g733_music_trial_update(void *, long, uintptr_t);
extern "C" void g733_music_trial_shutdown(void *);
extern "C" void g733_music_trial_after_swap();
#endif

static void rollback() {
    bool conflict = false;
    for (auto &change : changes) {
        if (!change.target.node)
            continue;
        if (api.entry(change.target.node) == change.entry && api.local_false(change.target.node)) {
            api.remove(change.target.node);
            conflict |= api.entry(change.target.node) != nullptr;
        } else {
            conflict = true;
        }
        if (change.target.control)
            release_ref(change.target.control);
        change = {};
    }
    if (applied || conflict)
        message(conflict ? "G733_SCENE_RESTORE_CONFLICT\n" : "G733_SCENE_RESTORED\n");
    applied = 0;
    if (active_root.control)
        release_ref(active_root.control);
    active_root = {};
    __atomic_store_n(&render_pending, 0, __ATOMIC_RELAXED);
}

static void update_scene(void *app, long delta_ms) {
    if (expired)
        return;
    if (ticks++ == 0)
        message("G733_SCENE_CALLBACK\n");
    if (delta_ms > 0)
        elapsed += delta_ms > 500 ? 500 : delta_ms;
    if (elapsed >= 9000) {
        if (applied)
            rollback();
        if (!expired && requested && !attempted)
            message("G733_SCENE_NO_VISIBLE_MATCH\n");
        if (!expired && !scanned)
            message("G733_SCENE_ROOT_UNAVAILABLE\n");
        expired = true;
        return;
    }
    if (attempted && !applied)
        return;
    if (elapsed < next_scan && !applied)
        return;
    Ref root = api.root(app);
    if (applied) {
        if (root.node != active_root.node)
            rollback();
        return;
    }
    if (!root.node)
        return;
    next_scan = elapsed + 500;
    unsigned budget = 4096;
    unsigned found = 0, visible = 0, local = 0, bound = 0;
    Ref eligible[writable_paths];
    for (unsigned i = 0; i < path_count && budget; ++i) {
        Ref node = resolve(root.node, paths[i], budget);
        if (!node.node)
            continue;
        unsigned bit = 1u << i;
        found |= bit;
        if (api.visible(node.node))
            visible |= bit;
        if (api.entry(node.node))
            local |= bit;
        if (api.bound(node.node))
            bound |= bit;
        if (i < writable_paths)
            eligible[i] = static_cast<Ref &&>(node);
    }
    if (found != last_found || visible != last_visible || local != last_values ||
        bound != last_bound || !scanned) {
        masks("G733_SCENE_SCAN", found, visible, local, bound);
        last_found = found;
        last_visible = visible;
        last_values = local;
        last_bound = bound;
        scanned = true;
    }
    if (!requested || ((requested & 1) && !(visible & paint_mask)))
        return;
    unsigned targets = requested & 1;
    if (requested & 2) {
        unsigned sr = visible & (driving_sr | active_sr);
        if (sr && !(sr & active_sr) &&
            (found & (driving_sr | driving_warning)) == (driving_sr | driving_warning))
            targets |= sr;
        else if (sr && !(sr & driving_sr) &&
                 (found & (active_sr | active_warning)) == (active_sr | active_warning))
            targets |= sr;
        else
            return; // no unique complete SR pair in a visible branch
    }
    attempted = true;
    if ((local | bound) & targets) {
        message("G733_SCENE_OEM_SOURCE_PRESENT\n");
        return;
    }
    active_root = root.take();
    unsigned change_index = 0;
    for (unsigned i = 0; i < writable_paths; ++i) {
        unsigned bit = 1u << i;
        if (!(targets & bit))
            continue;
        void *node = eligible[i].node;
        api.hide(node);
        void *source = api.entry(node);
        if (source && api.local_false(node)) {
            changes[change_index++] = {eligible[i].take(), source};
            applied |= bit;
        }
        if (!(applied & bit) || api.visible(node)) {
            rollback();
            message("G733_SCENE_APPLY_FAILED\n");
            return;
        }
    }
    masks("G733_SCENE_APPLIED", requested, applied & paint_mask,
          applied & (driving_sr | active_sr), 0);
    __atomic_store_n(&render_pending, 1, __ATOMIC_RELEASE);
}

extern "C" __attribute__((visibility("hidden"))) void g733_scene_after_swap() {
#ifdef G733_MUSIC_PROBE
    if (music_mode)
        g733_music_trial_after_swap();
#endif
    if (__atomic_load_n(&render_pending, __ATOMIC_ACQUIRE) &&
        __atomic_exchange_n(&render_pending, 0, __ATOMIC_ACQ_REL))
        message("G733_SCENE_EGL_SWAP\n");
}

#ifndef G733_SCENE_HOST_TEST
extern "C" int getpagesize();
extern "C" int mprotect(void *, size_t, int);
static uintptr_t hmi;
static bool shutting_down;
static void (*original_destruct)(void *);
static void (*original_delete)(void *);

template <class T> static T at(uintptr_t offset) { return reinterpret_cast<T>(hmi + offset); }
template <class T> static T slot(void *node, unsigned offset) {
    auto vptr = *static_cast<uintptr_t **>(node);
    return reinterpret_cast<T>(vptr[offset / sizeof(uintptr_t)]);
}
static void *property_object(void *node) { return static_cast<char *>(node) + 8; }
static void *visible_type() { return at<void *>(0xa56408); }
static void *descriptor() { return *at<void **>(0xa56408); }

static size_t oem_count(void *node) { return slot<size_t (*)(void *)>(node, 0x20)(node); }
static Ref oem_child(void *node, size_t i) { return slot<Ref (*)(void *, size_t)>(node, 0x28)(node, i); }
static Ref oem_scene(void *node) {
    auto find = slot<Ref (*)(void *, View)>(node, 0x60);
    if (reinterpret_cast<uintptr_t>(find) != hmi + 0x48a938)
        return {};
    return find(node, {"Scene", 5});
}
static bool oem_bound(void *node) {
    auto storage = at<unsigned char *(*)(void *, void *)>(0x5928d8)(property_object(node), descriptor());
    return storage && *reinterpret_cast<void **>(storage + 0x30);
}
static bool oem_visible(void *node) {
    if (!at<bool (*)(void *)>(0x268720)(node))
        return false;
    void *opacity = *at<void **>(0xa56338);
    if (!opacity)
        return false;
    for (unsigned depth = 0; node && depth < 32; ++depth) {
        auto value = at<const float *(*)(void *, void *)>(0x192fc0)(node, opacity);
        if (value && !(*value > 0.01f))
            return false;
        node = at<void *(*)(void *)>(0x268210)(node);
    }
    return !node;
}
static void *oem_entry(void *node) {
    return at<void *(*)(void *, void *)>(0x592920)(property_object(node), descriptor());
}
static bool oem_local_false(void *node) {
    void *object = property_object(node);
    if (!at<bool (*)(void *, void *)>(0x592ef0)(object, descriptor()))
        return false;
    auto value = at<const unsigned char *(*)(void *, void *)>(0x592a08)(object, descriptor());
    return value && *value == 0;
}
static void oem_hide(void *node) {
    at<void (*)(void *, void *, bool)>(0x1942f8)(property_object(node), visible_type(), false);
}
static void oem_remove(void *node) {
    at<void (*)(void *, void *)>(0x592f48)(property_object(node), descriptor());
}
static void on_update(void *app, long delta_ms) {
    if(shutting_down)return;
#ifdef G733_MUSIC_PROBE
    if (music_mode) {
        g733_music_trial_update(app, delta_ms, hmi);
        return;
    }
#endif
    if (descriptor())
        update_scene(app, delta_ms);
}
static void on_shutdown(void *app) {
    if(shutting_down)return;
    shutting_down=true;
#ifdef G733_MUSIC_PROBE
    if (music_mode) {
        g733_music_trial_shutdown(app);
        return;
    }
#else
    (void)app;
#endif
    rollback();
}
// 现场SIGTERM退出可直接进入ClusterFunction析构，不经过uninitialize。
// 必须在Application::destruct销毁Domain之前释放自有节点及原节点强引用。
static void on_destruct(void *app){on_shutdown(app);original_destruct(app);}
static void on_delete(void *app){on_shutdown(app);original_delete(app);}

extern "C" __attribute__((visibility("hidden"))) void g733_scene_install(
        uintptr_t *table, uintptr_t base, const char *mode, const char *bench) {
    if (!mode || !*mode)
        return;
#ifdef G733_MUSIC_PROBE
    if (!strcmp(mode, "runtime")) {
#ifndef G733_LIVE_MUSIC
        message("G733_RUNTIME_LIVE_BUILD_REQUIRED\n");
        return;
#endif
        g733_music_runtime_enable();
        music_mode = true;
    } else if (!strcmp(mode, "music"))
        music_mode = true;
    else
#endif
    if (!strcmp(mode, "background"))
        requested = 1;
    else if (!strcmp(mode, "sr"))
        requested = 2;
    else if (!strcmp(mode, "both"))
        requested = 3;
    else if (strcmp(mode, "scan")) {
        message("G733_SCENE_MODE_INVALID\n");
        return;
    }
    if ((requested & 2) && (!bench || strcmp(bench, "1"))) {
        message("G733_SCENE_SR_BENCH_REQUIRED\n");
        return;
    }
    hmi = base;
    release_ref = at<void (*)(void *)>(0x15ce28);
    api = {at<Ref (*)(void *)>(0x27f7b8), oem_count, oem_child, oem_scene,
           at<bool (*)(void *, View)>(0x268218), oem_visible,
           oem_bound, oem_entry, oem_local_false, oem_hide, oem_remove};
    size_t page_size = (size_t)getpagesize();
    uintptr_t *update_slot = table + 0x60 / sizeof(uintptr_t);
    uintptr_t *shutdown_slot = table + 0x48 / sizeof(uintptr_t);
    uintptr_t *destruct_slot = table + 0x28 / sizeof(uintptr_t);
    uintptr_t *delete_slot = table + 0x30 / sizeof(uintptr_t);
    uintptr_t page_mask = ~(uintptr_t)(page_size - 1);
    uintptr_t page = (uintptr_t)update_slot & page_mask;
    if (page_size < 4096 || page_size > 65536 || (page_size & (page_size - 1)) ||
        ((uintptr_t)shutdown_slot & page_mask) != page ||
        ((uintptr_t)destruct_slot & page_mask) != page ||
        ((uintptr_t)delete_slot & page_mask) != page ||
        *destruct_slot!=base+0x15c0b0 || *delete_slot!=base+0x15c0c8 ||
        page < base + 0xa1c6a8 || page + page_size > base + 0xa4e000 ||
        mprotect((void *)page, page_size, 0x300)) {
        message("G733_SCENE_RELRO_WRITE_FAILED\n");
        return;
    }
    *update_slot = (uintptr_t)on_update;
    *shutdown_slot = (uintptr_t)on_shutdown;
    original_destruct=(void (*)(void *))*destruct_slot;
    original_delete=(void (*)(void *))*delete_slot;
    *destruct_slot=(uintptr_t)on_destruct;
    *delete_slot=(uintptr_t)on_delete;
    if (mprotect((void *)page, page_size, 0x100)) {
        *update_slot = base + 0x27d2a0;
        *shutdown_slot = base + 0x27d2a8;
        *destruct_slot=(uintptr_t)original_destruct;
        *delete_slot=(uintptr_t)original_delete;
        (void)mprotect((void *)page, page_size, 0x100);
        message("G733_SCENE_RELRO_RESTORE_FAILED\n");
        return;
    }
    message("G733_SCENE_SLOTS_INSTALLED\n");
}
#endif
