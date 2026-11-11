/* SPDX-License-Identifier: GPL-3.0-only */
#include <assert.h>
#include <stdio.h>
#define G733_SCENE_HOST_TEST 1
#include "g733_scene_hook.cpp"

struct Node {
    const char *name;
    Node *children[8];
    unsigned count;
    Node *scene;
    unsigned refs;
    bool binding, hidden, fail_hide, local, oem_entry;
};

static Ref ref(Node *node) {
    Ref r;
    if (node) {
        ++node->refs;
        r.node = node;
        r.control = &node->refs;
    }
    return r;
}
static Node &node(void *p) { return *static_cast<Node *>(p); }
static Node make(const char *name) { Node n{}; n.name = name; return n; }
static void add(Node &parent, Node &child) { parent.children[parent.count++] = &child; }
static Ref root_ref(void *app) { return ref(*static_cast<Node **>(app)); }
static Ref child_ref(void *p, size_t i) { return ref(node(p).children[i]); }
static Ref scene_ref(void *p) { return ref(node(p).scene); }
static size_t count(void *p) { return node(p).count; }
static bool named(void *p, View name) {
    return strlen(node(p).name) == name.size && memcmp(node(p).name, name.data, name.size) == 0;
}
static bool visible(void *p) { return !node(p).hidden; }
static bool bound(void *p) { return node(p).binding; }
static void *entry(void *p) { return node(p).local ? p : node(p).oem_entry ? &node(p).oem_entry : nullptr; }
static bool local_false(void *p) { return node(p).local && node(p).hidden; }
static void hide(void *p) {
    if (!node(p).fail_hide)
        node(p).local = node(p).hidden = true;
}
static void remove(void *p) { node(p).local = node(p).hidden = false; }

static void reset(unsigned mode) {
    rollback();
    requested = mode;
    attempted = scanned = expired = false;
    elapsed = next_scan = 0;
    ticks = 0;
}

int main() {
    release_ref = [](void *p) { auto &n = *static_cast<unsigned *>(p); assert(n > 0); --n; };
    api = {root_ref, count, child_ref, scene_ref, named, visible, bound, entry, local_false, hide, remove};
    Node root = make("Root"), content = make("Contents2D"), ctrl = make("RootCtrl");
    Node drive = make("E_POWER_STATUS_DRIVING"), ctrl_node = make("CtrlNode");
    Node bg = make("module_BG"), paint = make("BG"), road = make("moduleBgRoad"), ground = make("BgRoad");
    Node warning = make("module_Warning"), adas2 = make("module_ADAS2D");
    Node adas = make("module_ADAS3D"), prefab = make("ADAS3D");
    Node viewport = make("Viewport 2D"), scene = make("Scene"), lane = make("Lane");
    Node active = make("E_POWER_STATUS_ACTIVE"), active_warning = make("module_Warning");
    Node active2 = make("module_ADAS2D"), active3 = make("module_ADAS3D");
    add(root, content); add(content, ctrl); add(ctrl, drive); add(drive, ctrl_node);
    add(content, bg); add(bg, paint); add(ctrl_node, road); add(road, ground);
    add(ctrl_node, warning); add(ctrl_node, adas2); add(ctrl_node, adas);
    add(adas, prefab); add(prefab, viewport);
    add(ctrl, active); add(active, active_warning); add(active, active2); add(active, active3);
    active2.hidden = active3.hidden = true;
    viewport.scene = &scene; add(scene, lane);
    Node *app = &root;

    reset(0);
    update_scene(&app, 100);
    assert((last_found & ((1u << 11) | (1u << 13))) == ((1u << 11) | (1u << 13)));
    assert(!paint.hidden && !ground.hidden && !applied && !root.refs);
    g733_scene_after_swap();

    reset(1);
    update_scene(&app, 100);
    assert(applied == 1 && paint.hidden && !ground.hidden && !warning.hidden && root.refs == 1);
    g733_scene_after_swap(); g733_scene_after_swap();
    app = nullptr;
    update_scene(&app, 100);
    assert(!applied && !paint.hidden && !root.refs);
    app = &root;

    reset(2);
    adas.binding = true;
    update_scene(&app, 100);
    assert(!applied && !adas.hidden && attempted);
    adas.binding = false;
    reset(2);
    adas.oem_entry = true;
    update_scene(&app, 100);
    assert(!applied && !adas.hidden);
    adas.oem_entry = false;

    reset(2);
    update_scene(&app, 100);
    assert(applied == driving_sr && adas.hidden && adas2.hidden && !ground.hidden &&
           !paint.hidden && !lane.hidden && !warning.hidden);
    for (unsigned i = 0; i < 18; ++i)
        update_scene(&app, 500);
    assert(!adas.hidden && !adas2.hidden && !applied && expired);

    reset(3);
    adas.fail_hide = true;
    update_scene(&app, 100);
    assert(!paint.hidden && !adas.hidden && !adas2.hidden && !applied);
    adas.fail_hide = false;
    reset(3);
    update_scene(&app, 100);
    assert(applied == (paint_mask | driving_sr) && paint.hidden && adas.hidden &&
           adas2.hidden && !ground.hidden && !warning.hidden);
    rollback();
    assert(!paint.hidden && !adas.hidden && !adas2.hidden && !root.refs && !paint.refs && !adas.refs);

    reset(2);
    active2.hidden = active3.hidden = false;
    update_scene(&app, 100);
    assert(!applied && !attempted); // two concurrent power-state branches
    adas.hidden = adas2.hidden = true;
    active2.hidden = true;
    update_scene(&app, 500);
    assert(applied == (1u << 4) && active3.local && !active2.local && !active_warning.local);
    rollback();
    assert(!active3.hidden && active2.hidden && adas.hidden);
    adas.hidden = adas2.hidden = false;
    active3.hidden = true;

    reset(1);
    Node duplicate = make("BG");
    add(bg, duplicate);
    update_scene(&app, 100);
    assert(!applied && !paint.hidden && !duplicate.hidden && (last_found & 1) == 0);
    puts("G733 scene hook host checks passed");
}
