/* SPDX-License-Identifier: GPL-3.0-only */
/* Consume the exact encoded image/media/lease produced by the Android integration test.
 * Epochs alone are rebased for offline execution; pixels/text/identity are unchanged. */
#define GKM_STATE_PATH ".work/cluster-pipeline/music.bin"
#define GKM_COVER_PATH ".work/cluster-pipeline/cover.bin"
#define GBI_PATH ".work/cluster-pipeline/background.bin"
#define GHL_PATH ".work/cluster-pipeline/hook.bin"
#define main compact_fixture_main
#include "test_compact_music.cpp"
#undef main
#include "../../galaxy/common/src/main/cpp/kanzi_background/background_runtime.h"
static void renew(const char *path,unsigned offset) {
    unsigned char bytes[7000];int fd=open(path,0);assert(fd>=0);long n=read(fd,bytes,sizeof(bytes));close(fd);assert(n>0);
    gbf_put64(bytes+offset,(uint64_t)time(nullptr)*1000+5000);
    void *f=fopen(path,"wb");assert(f);assert(fwrite(bytes,1,(size_t)n,f)==(size_t)n);fclose(f);
}
int main() {
    initialize_types();scene=full_scene();
    Ref media=get_child(scene.object,0),contents=make_node(view("Contents2D")),module=make_node(view("module_BG")),bg=make_node(view("BG")),stock=make_node(view("OEMBackground"));
    Ref originals[3];for(unsigned i=0;i<3;++i)originals[i]=get_child(media.object,i);
    add_child(scene.object,contents);add_child(contents.object,module);add_child(module.object,bg);add_child(bg.object,stock);
    renew(GHL_PATH,16);renew(GKM_STATE_PATH,36);
    assert(hook_refresh()&&hook_source==1&&hook_flags==3);
    background_update(nullptr,0,100);galaxy_music_on_update(nullptr,1000);
    assert(background_state&&background_nodes.owns_slot()&&background_sequence==1);
    assert(state&&state->mounted&&state->replacement.owns_slot());
    assert(!strcmp(((FakeNode *)state->labels[0].object)->content,"Pipeline Song"));
    assert(!strcmp(((FakeNode *)state->labels[2].object)->content,"Previous"));
    assert(!strcmp(((FakeNode *)state->labels[3].object)->content,"Current"));
    assert(!strcmp(((FakeNode *)state->labels[4].object)->content,"Next"));
    assert(!strcmp(((FakeNode *)state->labels[5].object)->content,"After"));
    assert(background_pixels[(266*1920+960)*4]==255&&background_pixels[(266*1920+960)*4+1]==0);
    // A missing service lease rolls back both owners; repeat shutdown is harmless.
    unlink(GHL_PATH);assert(!hook_refresh());assert(!background_state);
    galaxy_music_on_shutdown(nullptr);galaxy_music_on_shutdown(nullptr);
    assert(get_child(bg.object,0).object==stock.object&&get_parent(stock.object)==bg.object);
    assert(child_count(media.object)==3);for(unsigned i=0;i<3;++i)assert(get_child(media.object,i).object==originals[i].object);
    hook_after_swap();scene=Ref{};return 0;
}
