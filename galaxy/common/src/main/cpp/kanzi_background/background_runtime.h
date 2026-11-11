/* SPDX-License-Identifier: GPL-3.0-only */
/* Included after the fixed-G733 music ABI. All Kanzi ownership stays on UI. */
#include "background_frame.h"
#ifdef G733_RESOURCE_BACKGROUND
extern "C" int g733_background_resource_active(void);
#endif
extern "C" long lseek(int, long, int);
extern "C" int rename(const char *, const char *);
static unsigned char hook_lease[32];
static ghl_clock hook_clock;
static uint64_t hook_generation;
static unsigned hook_flags, hook_source;
static uint64_t background_sequence;
struct BackgroundState { Ref root, texture; MusicNodeReplacement nodes; };
static BackgroundState *background_state;
#define background_root background_state->root
#define background_texture background_state->texture
#define background_nodes background_state->nodes
static unsigned char *background_pixels;
static long background_next_scan;
static bool background_read(int fd, void *memory, size_t length) {
    unsigned char *p = static_cast<unsigned char *>(memory);
    while (length) {
        long n = read(fd, p, length);
        if (n <= 0) return false;
        p += n; length -= (size_t)n;
    }
    return true;
}
static void background_restore() {
    if (!background_state) return;
    if (background_nodes.active) {
        bool ok = background_nodes.restore();
        phase(ok ? "background_restored" : "background_restore_conflict");
    }
    background_texture = Ref{};
    background_root = Ref{};
    background_sequence = 0;
    if (background_pixels) free(background_pixels);
    background_pixels = nullptr;
    background_state->~BackgroundState(); free(background_state); background_state=nullptr;
}
static bool hook_refresh() {
    unsigned char lease[32], extra;
    int fd = open(GHL_PATH, 0);
    bool ok = fd >= 0 && background_read(fd, lease, sizeof(lease)) && read(fd, &extra, 1) == 0;
    if (fd >= 0) close(fd);
    uint64_t source_now=0;
    ok = ok && ghl_source_now(&hook_clock,lease,(uint64_t)time(nullptr)*1000,&source_now);
    unsigned flags = ok ? gbf_u32(lease+4) : 0;
    uint64_t generation = ok ? gbf_u64(lease+8) : 0;
    unsigned source = ok ? gbf_u32(lease+24) : 0;
    if (generation != hook_generation || source != hook_source || !(flags&2)) background_restore();
    hook_generation = generation; hook_flags = flags; hook_source = source;
    if (ok) memcpy(hook_lease, lease, sizeof(lease));
    return ok;
}
static Ref background_child(void *parent, const char *name) {
    Ref found;
    size_t n = child_count(parent);
    if (n > 256) return {};
    for(size_t i=0;i<n;++i) {
        Ref child=get_child(parent,i);
        if(child.object && is_named(child.object,view(name))) {
            if(found.object)return {};
            found=child;
        }
    }
    return found;
}
static void background_update(void *app, uintptr_t base, long elapsed) {
    (void)base;
    if (!(hook_flags&2)) return;
#ifdef G733_RESOURCE_BACKGROUND
    if(hook_source==1)return; // 本地图片沿已实车通过的background.kzb读取替换，不新建BG节点。
#endif
    if (!background_state) { void *p=malloc(sizeof(BackgroundState)); if(!p)return; background_state=new(p) BackgroundState{}; }
    Ref root = get_root(app);
    if (background_nodes.active && (root.object != background_root.object || !background_nodes.owns_slot()))
        { background_restore(); void *p=malloc(sizeof(BackgroundState)); if(!p)return; background_state=new(p) BackgroundState{}; }
    // Frame reads are paced, and unchanged sequences read only the 48-byte header.
    if (elapsed < background_next_scan) return;
    background_next_scan = elapsed + 100;
    unsigned char header[GBF_HEADER], extra;
    int fd = open(hook_source==1 ? GBI_PATH : GBF_PATH, 0);
    bool valid = fd >= 0 && background_read(fd, header, sizeof(header)) &&
        gbf_valid(header, (uint64_t)time(nullptr)*1000, hook_generation, hook_source);
    if (!valid) { if(fd>=0)close(fd); background_restore(); phase("background_frame_unavailable"); return; }
    uint64_t sequence = gbf_u64(header+24);
    if (sequence == background_sequence && background_nodes.active) { close(fd); return; }
    if (!background_pixels) background_pixels=static_cast<unsigned char *>(malloc(GBF_BYTES));
    valid = background_pixels && background_read(fd, background_pixels, GBF_BYTES) && read(fd,&extra,1)==0;
    close(fd);
    if (!valid) { background_restore(); return; }
    if (!background_nodes.active) {
        if (!root.object) return;
        Ref contents=background_child(root.object,"Contents2D");
        Ref module=contents.object ? background_child(contents.object,"module_BG") : Ref{};
        Ref bg=module.object ? background_child(module.object,"BG") : Ref{};
        if(!bg.object) { phase("background_path_unavailable"); return; }
        Ref texture;
        Ref image_node=image(app,"GalaxyBackground",1920,532,background_pixels,&texture);
        if(!image_node.object)return;
        set_float(property(image_node.object),width_prop,1920);
        set_float(property(image_node.object),height_prop,532);
        if(!background_nodes.install_children(bg,image_node))return;
        background_texture=texture; background_root=root;
        phase("background_mounted");
    } else {
        texture_data(background_texture.object,background_pixels);
    }
    background_sequence=sequence;
}

/* UI owns state and writes receipts only after an EGL submission has occurred.
 * Render callback touches one atomic serial, never reads Kanzi objects. */
static unsigned hook_swap_serial;

static void hook_after_swap() { __atomic_add_fetch(&hook_swap_serial,1,__ATOMIC_RELEASE); }
static __attribute__((unused)) void hook_status() {
    static long last;
    static unsigned last_swap;
    unsigned swaps=__atomic_load_n(&hook_swap_serial,__ATOMIC_ACQUIRE);
    long now=time(nullptr);
    if(now==last || swaps==last_swap)return;
    last=now;last_swap=swaps;
    unsigned char status[64]={};
    gbf_put32(status,0x31524847); // GHR1 actual UI receipt, separate from GHL1 intent
    unsigned mounted=(state && state->mounted ? 1u : 0u) |
        (background_state && background_nodes.owns_slot() ? 2u : 0u);
#ifdef G733_RESOURCE_BACKGROUND
    if(g733_background_resource_active())mounted|=2u;
#endif
    gbf_put32(status+4,mounted);gbf_put64(status+8,hook_generation);
    gbf_put64(status+16,(uint64_t)((int64_t)now*1000+(hook_clock.ready ? hook_clock.offset : 0))+3000);gbf_put32(status+24,hook_source);
    gbf_put32(status+28,hook_flags);gbf_put64(status+32,background_sequence);
    gbf_put64(status+40,state && state->mounted ? state->last_revision : 0);
    gbf_put32(status+48,swaps);gbf_put32(status+52,(unsigned)getpid());
    gbf_put64(status+56,(uint64_t)now*1000);
    void *file=fopen(GHR_PATH ".tmp","wb");
    if(!file)return;
    bool ok=fwrite(status,1,sizeof(status),file)==sizeof(status);
    int closed=fclose(file);
    if(ok&&!closed)rename(GHR_PATH ".tmp",GHR_PATH);
}
