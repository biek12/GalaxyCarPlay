/* SPDX-License-Identifier: GPL-3.0-only */
/* 先声明本车 Ref 与子节点操作。仅在原厂 UI 线程提交和撤销。 */
#ifndef GALAXY_KANZI_NODE_REPLACEMENT_H
#define GALAXY_KANZI_NODE_REPLACEMENT_H
struct MusicNodeReplacement {
    Ref parent, original[16], inserted;
    size_t original_count = 0;
    bool active = false;
    bool installing = false;

    bool owns_slot() const {
        return active && child_count(parent.object) == 1 &&
            get_child(parent.object, 0).object == inserted.object;
    }

    bool restore() {
        if (!active)
            return true;
        const size_t count = child_count(parent.object);
        bool owns_original_slot = owns_slot() ||
            (installing && count == 0);
        void *current_parent = get_parent(inserted.object);
        if (current_parent)
            remove_child(current_parent, child_index(current_parent, inserted.object));
        if (owns_original_slot) {
            for (size_t i = 0; i < original_count; ++i) {
                if (!get_parent(original[i].object))
                    insert_child(parent.object, child_count(parent.object), original[i]);
            }
        }
        bool restored = child_count(parent.object) == original_count;
        for (size_t i = 0; restored && i < original_count; ++i)
            restored = get_child(parent.object, i).object == original[i].object;
        active = false;
        installing = false;
        parent = Ref{};
        for (auto &node : original) node = Ref{};
        original_count = 0;
        inserted = Ref{};
        return restored;
    }

    bool install(const Ref &target, const Ref &panel) {
        return target.object && child_count(target.object) == 2 && install_children(target, panel);
    }

    bool install_children(const Ref &target, const Ref &panel) {
        if (active || !target.object || !panel.object ||
            child_count(target.object) == 0 || child_count(target.object) > 16 || get_parent(panel.object))
            return false;
        parent = target;
        original_count = child_count(target.object);
        for (size_t i = 0; i < original_count; ++i) original[i] = get_child(target.object, i);
        inserted = panel;
        active = installing = true;
        for (size_t i = original_count; i > 0; --i) remove_child(parent.object, i - 1);
        add_child(parent.object, inserted);
        if (get_parent(inserted.object) != parent.object) {
            restore();
            return false;
        }
        installing = false;
        return true;
    }
};
#endif
