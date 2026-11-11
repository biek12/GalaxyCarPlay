/* SPDX-License-Identifier: GPL-3.0-only
 * G733固定ELF的窄ABI适配，不是Kanzi SDK。函数/布局来源及构建绑定见同目录README。
 * 挂接和资源操作只在原Application::onUpdate/onShutdown线程运行，不创建窗口或后台绘制线程。
 */
#include "music_frame.h"
#ifndef GKM_STATE_PATH
#define GKM_STATE_PATH "/otaupdate/autoservice-media/music.bin"
#endif
#ifndef GKM_PHASE_PATH
#define GKM_PHASE_PATH "/tmp/galaxy-carplay-kanzi-status.log"
#endif
extern "C" {
#include <string.h>
}
extern "C" {
void *malloc(size_t);
void free(void *);
int open(const char *, int, ...);
long read(int, void *, size_t);
int close(int);
long time(long *);
void *fopen(const char *, const char *);
size_t fwrite(const void *, size_t, size_t, void *);
int fclose(void *);
int getpid(void);
unsigned getuid(void);
}
inline void *operator new(size_t, void *p) noexcept { return p; }
/* 仅保留最近16次阶段变化；无曲目/歌词/原始载荷。文件重写有界，不依赖HMI全局日志。 */
static void phase(const char *name) {
    static char history[16][128];
    static unsigned serial;
    static char last[96];
    if (last[0] && !strcmp(last, name))
        return;
    unsigned last_size = 0;
    while (name[last_size] && last_size < sizeof(last) - 1) {
        last[last_size] = name[last_size];
        ++last_size;
    }
    last[last_size] = 0;
    char *line = history[serial++ % 16];
    unsigned n = 0;
    auto number = [&](unsigned long value) {
        char digits[24];
        unsigned k = 0;
        do {
            digits[k++] = (char)('0' + value % 10);
            value /= 10;
        } while (value);
        while (k && n < 124)
            line[n++] = digits[--k];
    };
    number((unsigned long)time(nullptr));
    line[n++] = ' ';
    number((unsigned long)getpid());
    line[n++] = ' ';
    number(getuid());
    line[n++] = ' ';
    while (*name && n < 126)
        line[n++] = *name++;
    line[n++] = '\n';
    line[n] = 0;
    void *file = fopen(GKM_PHASE_PATH, "w");
    if (file) {
        unsigned first = serial > 16 ? serial - 16 : 0;
        for (unsigned i = first; i < serial; ++i) {
            const char *entry = history[i % 16];
            fwrite(entry, 1, strlen(entry), file);
        }
        fclose(file);
    }
}
struct View {
    const char *data;
    size_t size;
};
extern "C" void
release_host(void *) asm("_ZNSt16_Sp_counted_baseILN9__gnu_cxx12_Lock_policyE2EE10_M_releaseEv");
struct Ref {
    void *object = nullptr;
    void *control = nullptr;
    Ref() = default;
    Ref(const Ref &r) : object(r.object), control(r.control) {
        if (control)
            __atomic_fetch_add((int *)((char *)control + 8), 1, __ATOMIC_RELAXED);
    }
    ~Ref() {
        if (control)
            release_host(control);
    }
    Ref &operator=(const Ref &r) {
        if (this != &r) {
            if (r.control)
                __atomic_fetch_add((int *)((char *)r.control + 8), 1, __ATOMIC_RELAXED);
            if (control)
                release_host(control);
            object = r.object;
            control = r.control;
        }
        return *this;
    }
};
static_assert(sizeof(Ref) == 16, "本车libstdc++ shared_ptr布局");
extern Ref get_root(void *) asm("_ZNK5kanzi11Application7getRootEv");
extern "C" void *get_domain(void *) asm("_ZNK5kanzi11Application9getDomainEv");
extern Ref create_empty(void *, View) asm(
    "_ZN5kanzi11EmptyNode2D6createEPNS_6DomainEN5boost17basic_string_viewIcSt11char_traitsIcEEE");
extern Ref create_text(void *, View) asm(
    "_ZN5kanzi11TextBlock2D6createEPNS_6DomainEN5boost17basic_string_viewIcSt11char_traitsIcEEE");
#ifdef G733_VIRTUAL_SCENE_API
/* 与早上实车SceneApi相同的抽象子节点入口。Screen/状态容器不能按Node2D成员布局读取。 */
static Ref get_child(void *node, size_t index) {
    auto table = *static_cast<uintptr_t **>(node);
    return reinterpret_cast<Ref (*)(void *, size_t)>(table[0x28 / sizeof(uintptr_t)])(node, index);
}
static size_t child_count(void *node) {
    auto table = *static_cast<uintptr_t **>(node);
    return reinterpret_cast<size_t (*)(void *)>(table[0x20 / sizeof(uintptr_t)])(node);
}
#else
extern Ref get_child(void *, size_t) asm("_ZNK5kanzi6Node2D8getChildEm");
extern "C" size_t child_count(void *) asm("_ZNK5kanzi6Node2D13getChildCountEv");
#endif
extern "C" bool
is_named(void *, View) asm("_ZNK5kanzi4Node7isNamedEN5boost17basic_string_viewIcSt11char_traitsIcEEE");
extern "C" void *get_parent(void *) asm("_ZNK5kanzi4Node9getParentEv");
extern "C" void add_child(void *, Ref) asm("_ZN5kanzi6Node2D8addChildESt10shared_ptrIS0_E");
extern "C" void insert_child(void *, size_t, Ref) asm("_ZN5kanzi6Node2D11insertChildEmSt10shared_ptrIS0_E");
extern "C" void remove_child(void *, size_t) asm("_ZN5kanzi6Node2D11removeChildEm");
extern "C" size_t child_index(void *, const void *) asm("_ZNK5kanzi6Node2D13getChildIndexERKNS_4NodeE");
extern "C" void copy_values(void *, const void *) asm("_ZN5kanzi14PropertyObject15copyLocalValuesERKS0_");
extern "C" bool effective_visible(void *) asm("_ZNK5kanzi4Node20isEffectivelyVisibleEv");
extern "C" bool has_local(void *, void *) asm("_ZNK5kanzi14PropertyObject13hasLocalValueENS_20AbstractPropertyTypeE");
extern "C" void remove_local(void *, void *) asm("_ZN5kanzi14PropertyObject16removeLocalValueENS_20AbstractPropertyTypeE");
extern "C" void *nonclass_value(void *, void *) asm("_ZNK5kanzi14PropertyObject22getNonClassValueSourceERNS_30AbstractPropertyTypeDescriptorE");
extern "C" void *class_value(void *, void *) asm("_ZNK5kanzi14PropertyObject19getClassValueSourceERNS_30AbstractPropertyTypeDescriptorE");
extern "C" void set_float(void *, const void *, float) asm(
    "_ZN5kanzi14PropertyObject11setPropertyIfEEvRKNS_12PropertyTypeIT_vEENS4_6Traits13ParameterTypeE");
struct Vec2 {
    float x, y;
};
extern "C" void set_vec2(void *, const void *,
                         Vec2) asm("_ZN5kanzi14PropertyObject11setPropertyINS_7Vector2EEEvRKNS_"
                                   "12PropertyTypeIT_vEENS5_6Traits13ParameterTypeE");
struct Srt2D {
    float scale_x, scale_y, rotation, x, y;
};
static_assert(sizeof(Srt2D) == 20, "G733 SRTValue2D layout");
extern "C" void set_srt(void *, const void *, const Srt2D &) asm(
    "_ZN5kanzi14PropertyObject11setPropertyINS_10SRTValue2DEEEvRKNS_12PropertyTypeIT_vEENS5_6Traits13ParameterTypeE");
extern "C" void set_bool(void *, const void *, bool) asm(
    "_ZN5kanzi14PropertyObject11setPropertyIbEEvRKNS_12PropertyTypeIT_vEENS4_6Traits13ParameterTypeE");
extern "C" void
set_font_size(void *,
              float) asm("_ZN5kanzi20TextBlockConceptImplINS_6Node2DENS_11TextBlock2DEE11setFontSizeEf");
extern "C" void
set_text(void *, void *) asm("_ZN5kanzi20TextBlockConceptImplINS_6Node2DENS_11TextBlock2DEE7setTextENSt7__"
                             "cxx1112basic_stringIcSt11char_traitsIcESaIcEEE");
extern "C" char width_prop[] asm("_ZN5kanzi4Node13WidthPropertyE");
extern "C" char height_prop[] asm("_ZN5kanzi4Node14HeightPropertyE");
extern "C" char vertical_prop[] asm("_ZN5kanzi4Node22VerticalMarginPropertyE");
extern "C" char horizontal_prop[] asm("_ZN5kanzi4Node24HorizontalMarginPropertyE");
extern "C" char actual_width_prop[] asm("_ZN5kanzi4Node19ActualWidthPropertyE");
extern "C" char actual_height_prop[] asm("_ZN5kanzi4Node20ActualHeightPropertyE");
extern "C" char layout_transform_prop[] asm("_ZN5kanzi6Node2D28LayoutTransformationPropertyE");
extern "C" char render_transform_prop[] asm("_ZN5kanzi6Node2D28RenderTransformationPropertyE");
extern "C" char perspective_prop[] asm("_ZN5kanzi6Node2D33PerspectiveTransformationPropertyE");
extern "C" char horizontal_align_prop[] asm("_ZN5kanzi4Node27HorizontalAlignmentPropertyE");
extern "C" char vertical_align_prop[] asm("_ZN5kanzi4Node25VerticalAlignmentPropertyE");
extern "C" char clip_prop[] asm("_ZN5kanzi4Node20ClipChildrenPropertyE");
extern "C" char opacity_prop[] asm("_ZN5kanzi4Node15OpacityPropertyE");
static void *property(void *node) { return (char *)node + 8; }
static View view(const char *s) { return {s, strlen(s)}; }
static void *descriptor(const char *type) { void *p; memcpy(&p, type, sizeof(p)); return p; }
/* 与本车isEffectivelyVisible内联属性解析一致：非类来源→继承来源→类来源→descriptor+0x20默认值。 */
static const void *resolved_value(void *node, const char *type) {
    void *d = descriptor(type);
    void *value = nonclass_value(property(node), d);
    unsigned flags; memcpy(&flags, (char *)d + 0x18, sizeof(flags));
    if (!value && (flags & 0x7fffffff)) {
        void *parent = get_parent(node);
        for (unsigned depth = 0; parent && !value && depth < 32; ++depth) {
            value = nonclass_value(property(parent), d);
            parent = get_parent(parent);
        }
    }
    if (!value) value = class_value(property(node), d);
    if (!value) value = (char *)d + 0x20;
    return value;
}
static float scalar(void *node, const char *type) {
    float out;
    memcpy(&out, resolved_value(node, type), sizeof(out));
    return out;
}
static __attribute__((unused)) void geometry_phase(const char *prefix, float value) {
    char line[96]; unsigned n = 0;
    while (*prefix && n < 60) line[n++] = *prefix++;
    unsigned v = value >= 0 && value < 16384 ? (unsigned)(value * 10) : 999999;
    char digits[12]; unsigned k = 0;
    do { digits[k++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (k) line[n++] = digits[--k];
    line[n] = 0; phase(line);
}
/* 克隆文字仅复用字体/颜色；布局变换、对齐与裁剪不能把原容器/原文字的偏移复制第二遍。 */
static void clear_geometry(void *node) {
    const char *types[] = {layout_transform_prop, render_transform_prop, perspective_prop,
                          horizontal_align_prop, vertical_align_prop, clip_prop};
    for (const char *type : types) remove_local(property(node), descriptor(type));
}
#include "node_replacement.h"

/* 原厂TextBlock调用者在0x20e680..0x20e6b4构造32字节C++11 string后传不可见引用。
 * 这里只构造调用期间的只读字符串视图布局；setter经_M_replace复制文本，不取得输入所有权。
 */
static void text(void *node, const char *s) {
    struct HostString {
        const char *pointer;
        size_t length;
        size_t capacity;
        size_t padding;
    };
    HostString value{s, strlen(s), strlen(s), 0};
    set_text(node, &value);
}
struct State {
    Ref root, target, panel, labels[9];
    MusicNodeReplacement replacement;
    Ref cover, cover_texture, cover_placeholder, progress_track, progress_fill;
    Ref progress_track_texture, progress_fill_texture;
    uint64_t cover_revision, cover_track;
    uint64_t scroll_ms, last_position;
    float scroll_distance, scroll_offset;
    uint16_t playback_flags;
    char rendered_text[6][1025];
    uint64_t elapsed_ms, last_revision, last_epoch, last_expires;
    uint64_t pending_epoch, pending_revision, pending_expires;
    int64_t source_offset_ms;
    uint8_t session[16];
    bool mounted, source_clock_ready;
};
static State *state;

/* 实车两端UTC相差约8h。跨时钟先观察同代两个递增revision及续租时间，
 * 单个遗留文件不能建立校准。之后仍按7秒源时钟期限过期，不改任何系统时钟。 */
static bool decode_source(State *s, const uint8_t *bytes, size_t count, uint64_t receiver_now,
                          gkm_frame *frame, uint64_t *source_now, bool *waiting) {
    int64_t adjusted = (int64_t)receiver_now + (s->source_clock_ready ? s->source_offset_ms : 0);
    *source_now = adjusted > 0 ? (uint64_t)adjusted : 0;
    *waiting = false;
    if (!gkm_decode(bytes, count, *source_now, frame))
        return true;
    if (count < 72)
        return false;
    uint64_t expires;
    memcpy(&expires, bytes + 36, 8);
    if (expires < 7000 || expires > 0x7fffffffffffffffULL ||
        gkm_decode(bytes, count, expires - 7000, frame))
        return false;
    int64_t offset = (int64_t)(expires - 7000) - (int64_t)receiver_now;
    if (offset >= -15000 && offset <= 15000)
        return false;
    uint64_t epoch = 0;
    memcpy(&epoch, frame->session, 8);
    if (epoch < s->last_epoch || (epoch == s->last_epoch && frame->revision < s->last_revision))
        return true; // 交给既有代次门控忽略，旧文件不得改写当前时钟校准。
    if (s->pending_epoch == epoch && frame->revision > s->pending_revision &&
        expires > s->pending_expires && expires - s->pending_expires <= 15000) {
        s->source_offset_ms = offset;
        s->source_clock_ready = true;
        s->pending_epoch = s->pending_revision = s->pending_expires = 0;
        *source_now = expires - 7000;
        phase("source_clock_calibrated");
        return true;
    }
    if (s->pending_epoch != epoch || frame->revision > s->pending_revision) {
        s->pending_epoch = epoch;
        s->pending_revision = frame->revision;
        s->pending_expires = expires;
    }
    *waiting = true;
    return false;
}

static bool music_visible(void *node) {
    if (!effective_visible(node))
        return false;
    for (unsigned depth = 0; node && depth < 32; ++depth) {
        if (!(scalar(node, opacity_prop) > 0.01f))
            return false;
        node = get_parent(node);
    }
    return !node;
}

/* Node2D集合中须只有一个实际可见且结构匹配的文字区。 */
static Ref collect_text(const Ref &node, unsigned depth, unsigned *budget, unsigned *matches) {
    if (!node.object || !depth || !*budget)
        return {};
    --*budget;
    size_t n = child_count(node.object);
    if (n > 1024)
        return {};
    Ref result;
    if (is_named(node.object, view("Media_Text")) && n == 2 && music_visible(node.object)) {
        Ref a = get_child(node.object, 0), b = get_child(node.object, 1);
        if ((is_named(a.object, view("Media_Title")) && is_named(b.object, view("Media_Artist"))) ||
            (is_named(b.object, view("Media_Title")) && is_named(a.object, view("Media_Artist"))))
        {
            ++*matches;
            result = node;
        }
    }
    for (size_t i = 0; i < n && *matches < 2 && *budget; ++i) {
        Ref child = get_child(node.object, i), match = collect_text(child, depth - 1, budget, matches);
        if (match.object)
            result = match;
    }
    return result;
}
static Ref find_text(const Ref &node, unsigned depth, unsigned *budget) {
    unsigned matches = 0;
    Ref result = collect_text(node, depth, budget, &matches);
    if (matches == 1 && *budget)
        return result;
    return {};
}
static void restore(State *s) {
    if (!s->replacement.restore())
        phase("restore_slot_changed");
    s->mounted = false;
    for (auto &label : s->labels)
        label = Ref{};
    s->panel = Ref{};
    s->cover = s->cover_texture = s->cover_placeholder = Ref{};
    s->progress_track = s->progress_fill = Ref{};
    s->progress_track_texture = s->progress_fill_texture = Ref{};
    s->cover_revision = s->cover_track = 0;
    s->target = Ref{};
    s->root = Ref{};
    // 保留发布代次高水位，旧会话的迟到文件不能重新挂接组件。
}
#include "compact_music_region.h"
static bool mount(State *s, void *app, const Ref &root) {
#ifndef GKM_LEGACY_TEXT_LAYOUT
    return mount_compact(s, app, root);
#else
    unsigned budget = 4096;
    Ref target = find_text(root, 32, &budget);
    if (!target.object)
        return false;
    float available_width = scalar(target.object, actual_width_prop);
    if (!(available_width > 0 && available_width < 16384)) return false; // 等待真实布局完成。
    geometry_phase("target_width_x10=", available_width);
    geometry_phase("target_height_x10=", scalar(target.object, actual_height_prop));
    Ref first = get_child(target.object, 0), second = get_child(target.object, 1);
    Ref title = is_named(first.object, view("Media_Title")) ? first : second;
    Ref artist = is_named(first.object, view("Media_Artist")) ? first : second;
    Srt2D origin;
    memcpy(&origin, resolved_value(title.object, render_transform_prop), sizeof(origin));
    float available_height = scalar(target.object, actual_height_prop);
    if (!(origin.y >= 0 && origin.y + 140.f <= available_height))
        return false;
    geometry_phase("panel_origin_y_x10=", origin.y);
    Ref panel = create_empty(get_domain(app), view("GalaxyMusicText"));
    if (!panel.object)
        return false;
    set_float(property(panel.object), width_prop, available_width);
    set_float(property(panel.object), height_prop, 140.f);
    set_bool(property(panel.object), clip_prop, true);
    const Srt2D panel_transform{1, 1, 0, 0, origin.y};
    set_srt(property(panel.object), render_transform_prop, panel_transform);
    const char *names[] = {"GalaxyTitle",     "GalaxyArtist",     "GalaxyLyricBefore", "GalaxyLyricCurrent",
                           "GalaxyLyricNext", "GalaxyLyricAfter", "GalaxyProgress"};
    const float sizes[] = {22, 16, 14, 22, 14, 12, 13};
    const float tops[] = {0, 26, 46, 64, 90, 108, 124};
    const float heights[] = {26, 20, 18, 26, 18, 16, 16};
    Ref labels[7];
    for (unsigned i = 0; i < 7; ++i) {
        Ref label = create_text(get_domain(app), view(names[i]));
        if (!label.object)
            return false;
        copy_values(property(label.object), property(i == 0 || i == 3 ? title.object : artist.object));
        clear_geometry(label.object);
        set_float(property(label.object), width_prop, available_width);
        set_float(property(label.object), height_prop, heights[i]);
        const Srt2D row_transform{1, 1, 0, 0, tops[i]};
        set_srt(property(label.object), render_transform_prop, row_transform);
        set_font_size(label.object, sizes[i]);
        text(label.object, "");
        add_child(panel.object, label);
        labels[i] = label;
    }
    for (unsigned i = 0; i < 7; ++i)
        s->labels[i] = labels[i];
    s->root = root;
    s->target = target;
    s->panel = panel;
    if (!s->replacement.install(target, panel)) {
        restore(s);
        return false;
    }
    s->mounted = true;
    return true;
#endif
}
static void progress_text(char *out, uint64_t position, uint64_t duration, bool playing, unsigned flags) {
    // 不依赖stdio格式化/用户输入；时长已由wire限制为一周。
    const char *prefix = playing ? "" : "|| ";
    unsigned n = 0;
    while (*prefix)
        out[n++] = *prefix++;
    uint64_t values[] = {position / 1000, duration / 1000};
    for (unsigned j = 0; j < 2; ++j) {
        if (!(flags & (j ? 8u : 4u))) {
            const char *unknown = "--:--";
            while (*unknown)
                out[n++] = *unknown++;
            if (!j) {
                out[n++] = ' ';
                out[n++] = '/';
                out[n++] = ' ';
            }
            continue;
        }
        unsigned minutes = (unsigned)(values[j] / 60), seconds = (unsigned)(values[j] % 60);
        char reversed[8];
        unsigned count = 0;
        do {
            reversed[count++] = (char)('0' + minutes % 10);
            minutes /= 10;
        } while (minutes);
        while (count)
            out[n++] = reversed[--count];
        out[n++] = ':';
        out[n++] = (char)('0' + seconds / 10);
        out[n++] = (char)('0' + seconds % 10);
        if (!j) {
            out[n++] = ' ';
            out[n++] = '/';
            out[n++] = ' ';
        }
    }
    out[n] = 0;
}
extern "C" __attribute__((visibility("default"))) void galaxy_music_on_update(void *app, long delta_ms) {
    if (!state) {
        void *memory = malloc(sizeof(State));
        if (!memory)
            return;
        state = new (memory) State{};
        state->elapsed_ms = 1000;
        phase("initialized");
    }
    State *s = state;
#ifndef GKM_LEGACY_TEXT_LAYOUT
    animate_compact(s, delta_ms);
#endif
    s->elapsed_ms += delta_ms > 0 ? (uint64_t)(delta_ms < 1000 ? delta_ms : 1000) : 0;
    if (s->elapsed_ms < (s->mounted ? 200u : 1000u))
        return;
    s->elapsed_ms = 0;
    uint8_t bytes[GKM_MAX_FRAME + 1];
    long count = -1;
    int fd = open(GKM_STATE_PATH, 0);
    if (fd >= 0) {
        count = read(fd, bytes, sizeof(bytes));
        close(fd);
    }
    struct gkm_frame frame;
    long seconds = time(nullptr);
    uint64_t source_now = 0;
    bool waiting_clock = false;
    if (seconds < 0 || count < 0 || !decode_source(s, bytes, (size_t)count, (uint64_t)seconds * 1000,
                                                 &frame, &source_now, &waiting_clock)) {
        if (s->mounted)
            restore(s);
        phase(waiting_clock ? "waiting_source_clock" : "source_expired_or_unavailable");
        return;
    }
    uint64_t epoch = 0;
    memcpy(&epoch, frame.session, 8);
    if (epoch < s->last_epoch || (epoch == s->last_epoch && frame.revision < s->last_revision)) {
        if (source_now > s->last_expires && s->mounted)
            restore(s);
        return;
    }
    if (!(frame.flags & 1)) {
        restore(s);
        phase("restored_inactive");
        s->last_epoch = epoch;
        s->last_revision = frame.revision;
        s->last_expires = frame.expires_ms;
        return;
    }
    Ref root = get_root(app);
    if (s->mounted && (s->root.object != root.object || !music_visible(s->target.object) ||
                       !s->replacement.owns_slot()))
        restore(s);
    bool fresh = !s->mounted;
    if (fresh && !mount(s, app, root)) {
        phase("waiting_music_component");
        return;
    }
    if (fresh)
        phase("mounted");
    if (!fresh && !memcmp(frame.session, s->session, 16) && frame.revision <= s->last_revision)
        return;
    const bool current_changed = fresh || strcmp(s->rendered_text[3], frame.text[3]) ||
        memcmp(s->session, frame.session, 16) || frame.position_ms + 200 < s->last_position ||
        frame.position_ms > s->last_position + 1500;
#ifdef GKM_LEGACY_TEXT_LAYOUT
    (void)current_changed;
#endif
    for (unsigned i = 0; i < 6; ++i) {
        if (fresh || strcmp(s->rendered_text[i], frame.text[i])) {
            text(s->labels[i].object, frame.text[i]);
            memcpy(s->rendered_text[i], frame.text[i], strlen(frame.text[i]) + 1);
        }
    }
    char progress[40];
    progress_text(progress, frame.position_ms, frame.duration_ms, (frame.flags & 2) != 0, frame.flags);
#ifndef GKM_LEGACY_TEXT_LAYOUT
    if (current_changed) prepare_compact_scroll(s);
    s->last_position = frame.position_ms; s->playback_flags = frame.flags;
    update_compact(s, app, frame, progress);
#else
    text(s->labels[6].object, progress);
#endif
    s->last_epoch = epoch;
    s->last_expires = frame.expires_ms;
    s->last_revision = frame.revision;
    memcpy(s->session, frame.session, 16);
}
extern "C" __attribute__((visibility("default"))) void galaxy_music_on_shutdown(void *) {
    if (state) {
        phase("shutdown");
        restore(state);
        state->~State();
        free(state);
        state = nullptr;
    }
}
