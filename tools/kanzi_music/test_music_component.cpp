/* SPDX-License-Identifier: GPL-3.0-only
 * 用宿主替身检验本项目挂接/恢复与会话逻辑；不模拟或代验原厂Kanzi执行。
 */
#define GKM_STATE_PATH ".work/kanzi-music/test-state.bin"
#define GKM_PHASE_PATH ".work/kanzi-music/test-status.log"
#include "../../galaxy/common/src/main/cpp/kanzi_music/music_component.cpp"
#include <assert.h>

struct FakeNode {
    void *reserved;
    float width, height;
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
static Ref make_node(View name) {
    FakeNode *n = new (malloc(sizeof(FakeNode))) FakeNode{};
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
extern "C" void add_child(void *p, Ref child) { insert_child(p, child_count(p), child); }
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
extern "C" void copy_values(void *to, const void *from) { memcpy(to, from, 8); }
extern "C" void set_float(void *p, const void *type, float value) {
    auto *n = (FakeNode *)((char *)p - 8);
    if (type == width_prop)
        n->width = value;
    else if (type == height_prop)
        n->height = value;
}
extern "C" void set_vec2(void *, const void *, Vec2) {}
extern "C" void set_font_size(void *, float) {}
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
    size_t len = active ? strlen(title) : 0;
    memcpy(bytes, "GKM1", 4);
    put(bytes + 4, 72 + len, 4);
    put(bytes + 8, 1, 2);
    put(bytes + 10, active ? 3 : 0, 2);
    put(bytes + 12, epoch, 8);
    put(bytes + 28, revision, 8);
    put(bytes + 36, expires ? expires : (uint64_t)time(nullptr) * 1000 + 7000, 8);
    put(bytes + 44, 1000, 8);
    put(bytes + 52, 10000, 8);
    put(bytes + 60, len, 2);
    memcpy(bytes + 72, title, len);
    void *f = fopen(GKM_STATE_PATH, "w");
    assert(f);
    assert(fwrite(bytes, 1, 72 + len, f) == 72 + len);
    fclose(f);
}
int main() {
    scene = make_scene();
    Ref original_scene = scene, target = get_child(scene.object, 0);
    Ref title = get_child(target.object, 0), artist = get_child(target.object, 1);
    frame(100, 1, true);
    galaxy_music_on_update(nullptr, 1000);
    assert(child_count(target.object) == 1 && !get_parent(title.object));
    Ref panel = get_child(target.object, 0);
    assert(child_count(panel.object) == 7);
    Ref label = get_child(panel.object, 0);
    assert(!strcmp(((FakeNode *)label.object)->content, "Track"));
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
    scene = Ref{};
    return 0;
}
