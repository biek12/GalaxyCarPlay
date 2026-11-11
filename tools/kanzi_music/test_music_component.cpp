/* SPDX-License-Identifier: GPL-3.0-only
 * 用宿主替身检验本项目挂接/恢复与会话逻辑；不模拟或代验原厂Kanzi执行。
 */
#ifndef GKM_STATE_PATH
#define GKM_STATE_PATH ".work/kanzi-music/test-state.bin"
#endif
#ifndef GKM_PHASE_PATH
#define GKM_PHASE_PATH ".work/kanzi-music/test-status.log"
#endif
#include "../../galaxy/common/src/main/cpp/kanzi_music/music_component.cpp"
#include <assert.h>
extern "C" int unlink(const char *);

struct FakeNode {
    void *reserved;
    float width, height;
    Srt2D transform;
    float font_size;
    float opacity;
    bool visible;
    FakeNode *parent;
    char name[64], content[1100];
    size_t count;
    Ref children[32];
};
struct Control {
    void *reserved;
    int count, weak;
    FakeNode *node;
};
static Ref scene;
static bool fail_next_add;
static Ref make_node(View name) {
    FakeNode *n = new (malloc(sizeof(FakeNode))) FakeNode{};
    n->width = 280;
    n->height = 532;
    n->transform = {1, 1, 0, 0, 0};
    n->visible = true;
    n->opacity = 1;
    assert(name.size < sizeof(n->name));
    memcpy(n->name, name.data, name.size);
    Control *c = (Control *)malloc(sizeof(Control));
    *c = {nullptr, 1, 1, n};
    Ref r;
    r.object = n;
    r.control = c;
    return r;
}
extern "C" void release_host(void *p) {
    Control *c = (Control *)p;
    if (--c->count == 0) {
        c->node->~FakeNode();
        free(c->node);
        free(c);
    }
}
Ref get_root(void *) { return scene; }
extern "C" void *get_domain(void *) { return nullptr; }
Ref create_empty(void *, View n) { return make_node(n); }
Ref create_text(void *, View n) { return make_node(n); }
Ref get_child(void *p, size_t i) {
    auto *n = (FakeNode *)p;
    assert(i < n->count);
    return n->children[i];
}
extern "C" size_t child_count(void *p) { return ((FakeNode *)p)->count; }
extern "C" bool is_named(void *p, View n) {
    auto *v = (FakeNode *)p;
    return strlen(v->name) == n.size && !memcmp(v->name, n.data, n.size);
}
extern "C" void *get_parent(void *p) { return ((FakeNode *)p)->parent; }
extern "C" void insert_child(void *p, size_t index, Ref child) {
    auto *n = (FakeNode *)p;
    assert(index <= n->count && n->count < 32 && !get_parent(child.object));
    for (size_t i = n->count; i > index; --i)
        n->children[i] = n->children[i - 1];
    n->children[index] = child;
    ++n->count;
    ((FakeNode *)child.object)->parent = n;
}
extern "C" void add_child(void *p, Ref child) {
    if (fail_next_add) { fail_next_add = false; return; }
    insert_child(p, child_count(p), child);
}
extern "C" void remove_child(void *p, size_t i) {
    auto *n = (FakeNode *)p;
    assert(i < n->count);
    ((FakeNode *)n->children[i].object)->parent = nullptr;
    for (; i + 1 < n->count; ++i)
        n->children[i] = n->children[i + 1];
    n->children[--n->count] = Ref{};
}
extern "C" size_t child_index(void *p, const void *child) {
    auto *n = (FakeNode *)p;
    for (size_t i = 0; i < n->count; ++i)
        if (n->children[i].object == child)
            return i;
    assert(false);
    return 0;
}
extern "C" char width_prop[8] = {}, height_prop[8] = {}, vertical_prop[8] = {}, horizontal_prop[8] = {};
extern "C" char actual_width_prop[8] = {}, actual_height_prop[8] = {};
extern "C" char layout_transform_prop[8] = {}, render_transform_prop[8] = {}, perspective_prop[8] = {};
extern "C" char horizontal_align_prop[8] = {}, vertical_align_prop[8] = {}, clip_prop[8] = {};
extern "C" char opacity_prop[8] = {};
alignas(8) static unsigned char descriptors[13][64];
static char *const types[] = {width_prop, height_prop, vertical_prop, horizontal_prop,
    actual_width_prop, actual_height_prop, layout_transform_prop, render_transform_prop,
    perspective_prop, horizontal_align_prop, vertical_align_prop, clip_prop, opacity_prop};
static void initialize_types() {
    for (unsigned i = 0; i < 13; ++i) {
        void *d = descriptors[i];
        memcpy(types[i], &d, sizeof(d));
    }
}
extern "C" bool effective_visible(void *p) { return ((FakeNode *)p)->visible; }
extern "C" void *nonclass_value(void *p, void *d) {
    auto *n = (FakeNode *)((char *)p - 8);
    if (d == descriptor(width_prop) || d == descriptor(actual_width_prop)) return &n->width;
    if (d == descriptor(height_prop) || d == descriptor(actual_height_prop)) return &n->height;
    if (d == descriptor(render_transform_prop)) return &n->transform;
    if (d == descriptor(opacity_prop)) return &n->opacity;
    return nullptr;
}
extern "C" void *class_value(void *, void *) { return nullptr; }
extern "C" bool has_local(void *, void *) { return true; }
extern "C" void remove_local(void *p, void *d) {
    if (d == descriptor(render_transform_prop))
        ((FakeNode *)((char *)p - 8))->transform = {1, 1, 0, 0, 0};
}
extern "C" void copy_values(void *to, const void *from) {
    auto *target = (FakeNode *)((char *)to - 8);
    auto *source = (const FakeNode *)((const char *)from - 8);
    target->width = source->width;
    target->height = source->height;
    target->transform = source->transform;
    target->font_size = source->font_size;
}
extern "C" void set_float(void *p, const void *type, float value) {
    auto *n = (FakeNode *)((char *)p - 8);
    if (type == width_prop)
        n->width = value;
    else if (type == height_prop)
        n->height = value;
}
extern "C" void set_vec2(void *, const void *, Vec2) {}
extern "C" void set_srt(void *p, const void *type, const Srt2D &value) {
    assert(type == render_transform_prop);
    ((FakeNode *)((char *)p - 8))->transform = value;
}
extern "C" void set_bool(void *, const void *type, bool value) {
    assert(type == clip_prop && value);
}
extern "C" void set_font_size(void *p, float value) { ((FakeNode *)p)->font_size = value; }
extern "C" void set_text(void *p, void *value) {
    struct String {
        const char *data;
        size_t size;
    };
    auto *v = (String *)value;
    assert(v->size < 1100);
    auto *n = (FakeNode *)p;
    memcpy(n->content, v->data, v->size);
    n->content[v->size] = 0;
}
static Ref make_scene() {
    Ref r = make_node(view("Root")), target = make_node(view("Media_Text"));
    add_child(target.object, make_node(view("Media_Title")));
    add_child(target.object, make_node(view("Media_Artist")));
    ((FakeNode *)get_child(target.object, 0).object)->transform.y = 318;
    ((FakeNode *)get_child(target.object, 1).object)->transform.y = 362;
    ((FakeNode *)get_child(target.object, 0).object)->height = 30;
    ((FakeNode *)get_child(target.object, 1).object)->height = 30;
    add_child(r.object, target);
    return r;
}
static void put(uint8_t *p, uint64_t v, unsigned n) {
    for (unsigned i = 0; i < n; ++i)
        p[i] = (uint8_t)(v >> (8 * i));
}
static void frame(uint64_t epoch, uint64_t revision, bool active, const char *title = "Track",
                  uint64_t expires = 0) {
    uint8_t bytes[200] = {};
    const char *rows[] = {title, "Artist", "Before", "Current", "Next", "After"};
    size_t length = 72;
    for (unsigned i = 0; i < 6; ++i) {
        size_t size = active ? strlen(rows[i]) : 0;
        assert(length + size <= sizeof(bytes));
        put(bytes + 60 + i * 2, size, 2);
        memcpy(bytes + length, rows[i], size);
        length += size;
    }
    memcpy(bytes, "GKM1", 4);
    put(bytes + 4, length, 4);
    put(bytes + 8, 1, 2);
    put(bytes + 10, active ? 3 : 0, 2);
    put(bytes + 12, epoch, 8);
    put(bytes + 28, revision, 8);
    put(bytes + 36, expires ? expires : (uint64_t)time(nullptr) * 1000 + 7000, 8);
    put(bytes + 44, 1000, 8);
    put(bytes + 52, 10000, 8);
    void *f = fopen(GKM_STATE_PATH, "w");
    assert(f);
    assert(fwrite(bytes, 1, length, f) == length);
    fclose(f);
}
#ifndef GKM_COMPACT_TEST
int main() {
    initialize_types();
    scene = make_scene();
    Ref original_scene = scene, target = get_child(scene.object, 0);
    Ref title = get_child(target.object, 0), artist = get_child(target.object, 1);
    frame(100, 1, true);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 1 && !get_parent(title.object));
    Ref panel = get_child(target.object, 0);
    assert(child_count(panel.object) == 7);
    assert(((FakeNode *)target.object)->height == 532);
    assert(((FakeNode *)panel.object)->transform.y == 318);
    const float rows[] = {0, 26, 46, 64, 90, 108, 124};
    for (unsigned i = 0; i < 7; ++i) {
        auto *row = (FakeNode *)get_child(panel.object, i).object;
        assert(row->transform.y == rows[i]);
        assert(row->transform.x == 0 && row->transform.scale_x == 1 && row->transform.scale_y == 1);
        assert(row->transform.y + row->height <= 140);
    }
    Ref label = get_child(panel.object, 0);
    assert(!strcmp(((FakeNode *)label.object)->content, "Track"));
    const char *lyrics[] = {"Before", "Current", "Next", "After"};
    for (unsigned i = 0; i < 4; ++i)
        assert(!strcmp(((FakeNode *)get_child(panel.object, i + 2).object)->content, lyrics[i]));
    frame(100, 2, true, "Next");
    galaxy_music_on_update(nullptr, 1000);
    assert(!strcmp(((FakeNode *)label.object)->content, "Next"));
    frame(100, 1, true, "Old");
    galaxy_music_on_update(nullptr, 1000);
    assert(!strcmp(((FakeNode *)label.object)->content, "Next"));
    frame(100, 3, false);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 2 && get_child(target.object, 0).object == title.object &&
           get_child(target.object, 1).object == artist.object);
    assert(((FakeNode *)title.object)->transform.y == 318 && ((FakeNode *)artist.object)->transform.y == 362);
    frame(200, 1, true);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 1);
    frame(100, 4, false);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 1);
    scene = make_scene();
    Ref new_target = get_child(scene.object, 0);
    frame(201, 1, true);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 2 && child_count(new_target.object) == 1);
    frame(201, 2, true, "Track", (uint64_t)time(nullptr) * 1000 - 1);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(new_target.object) == 2);
    frame(202, 1, true);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(new_target.object) == 1);
    galaxy_music_on_shutdown(nullptr);
    assert(child_count(new_target.object) == 2 && !state);
    MusicNodeReplacement transaction;
    Ref original_a = get_child(new_target.object, 0), original_b = get_child(new_target.object, 1);
    Ref own = make_node(view("OwnedPanel"));
    fail_next_add = true;
    assert(!transaction.install(new_target, own));
    assert(!transaction.active && child_count(new_target.object) == 2);
    assert(get_child(new_target.object, 0).object == original_a.object);
    assert(get_child(new_target.object, 1).object == original_b.object);
    assert(transaction.install(new_target, own));
    Ref foreign = make_node(view("OemChangedContent"));
    add_child(new_target.object, foreign);
    assert(!transaction.restore());
    assert(child_count(new_target.object) == 1 && get_child(new_target.object, 0).object == foreign.object);
    assert(transaction.restore());
    assert(!get_parent(original_a.object) && !get_parent(original_b.object));
    Ref one = make_scene(), two = make_scene();
    Ref second_target = get_child(two.object, 0);
    remove_child(two.object, 0);
    add_child(one.object, second_target);
    unsigned budget = 4096;
    assert(!find_text(one, 32, &budget).object);
    ((FakeNode *)second_target.object)->opacity = 0;
    budget = 4096;
    assert(find_text(one, 32, &budget).object == get_child(one.object, 0).object);
    ((FakeNode *)one.object)->opacity = 0;
    budget = 4096;
    assert(!find_text(one, 32, &budget).object);
    Ref short_space = make_scene();
    Ref short_target = get_child(short_space.object, 0);
    ((FakeNode *)get_child(short_target.object, 0).object)->transform.y = 500;
    State unmounted{};
    assert(!mount(&unmounted, nullptr, short_space));
    assert(child_count(short_target.object) == 2 && !unmounted.replacement.active);
    Ref relocated_scene = make_scene(), external_parent = make_node(view("ExternalParent"));
    Ref relocated_target = get_child(relocated_scene.object, 0);
    assert(transaction.install(relocated_target, own));
    remove_child(relocated_target.object, 0);
    add_child(external_parent.object, own);
    assert(!transaction.owns_slot());
    assert(!transaction.restore());
    assert(child_count(external_parent.object) == 0 && !get_parent(own.object));
    (void)unlink(GKM_STATE_PATH);
    scene = Ref{};
    return 0;
}

#endif
