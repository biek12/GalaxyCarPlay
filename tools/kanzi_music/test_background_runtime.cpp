/* SPDX-License-Identifier: GPL-3.0-only */
#define main compact_test_main
#include "test_compact_music.cpp"
#undef main
#define GBF_PATH ".work/kanzi-music/background.frame"
#define GBI_PATH ".work/kanzi-music/image.frame"
#define GHL_PATH ".work/kanzi-music/hook.bin"
#include "../../galaxy/common/src/main/cpp/kanzi_background/background_runtime.h"
static void lease(uint64_t generation, uint64_t expiry, unsigned flags=3) {
    unsigned char data[32]={};gbf_put32(data,0x314c4847);gbf_put32(data+4,flags);
    gbf_put64(data+8,generation);gbf_put64(data+16,expiry);gbf_put32(data+24,2);
    void *file=fopen(GHL_PATH,"wb");assert(file);assert(fwrite(data,1,32,file)==32);fclose(file);
}
static void background_frame(uint64_t generation,uint64_t sequence,uint64_t expiry,bool truncate=false) {
    unsigned char data[48]={};gbf_put32(data,0x31464247);gbf_put32(data+4,1920);gbf_put32(data+8,532);gbf_put32(data+12,7680);
    gbf_put64(data+16,generation);gbf_put64(data+24,sequence);gbf_put64(data+32,expiry);gbf_put32(data+40,2);gbf_put32(data+44,GBF_BYTES);
    void *file=fopen(GBF_PATH,"wb");assert(file);assert(fwrite(data,1,48,file)==48);
    void *pixels=malloc(GBF_BYTES);memset(pixels,123,GBF_BYTES);size_t bytes=truncate?GBF_BYTES-1:GBF_BYTES;
    assert(fwrite(pixels,1,bytes,file)==bytes);free(pixels);fclose(file);
}
static void clock_skew() {
    ghl_clock clock{};unsigned char packet[32]={};uint64_t adjusted=0;
    gbf_put32(packet,0x314c4847);gbf_put32(packet+4,3);gbf_put64(packet+8,8);gbf_put64(packet+16,100000+5000);gbf_put32(packet+24,1);
    assert(!ghl_source_now(&clock,packet,100000+28800000,&adjusted));
    assert(!ghl_source_now(&clock,packet,101000+28800000,&adjusted)); // retained file cannot calibrate
    gbf_put64(packet+16,101000+5000);
    assert(ghl_source_now(&clock,packet,101000+28800000,&adjusted)&&adjusted==101000);
    assert(ghl_source_now(&clock,packet,103000+28800000,&adjusted)&&adjusted==103000);
    assert(!ghl_source_now(&clock,packet,107000+28800000,&adjusted)); // no renewal really expires
    gbf_put64(packet+8,7);gbf_put64(packet+16,108000+5000);
    assert(!ghl_source_now(&clock,packet,108000+28800000,&adjusted)); // late old generation
}
int main() {
    clock_skew();
    initialize_types();scene=make_node(view("Root"));Ref contents=make_node(view("Contents2D")),module=make_node(view("module_BG")),bg=make_node(view("BG")),original=make_node(view("OEM"));
    add_child(scene.object,contents);add_child(contents.object,module);add_child(module.object,bg);add_child(bg.object,original);
    uint64_t now=(uint64_t)time(nullptr)*1000;
    lease(11,now+5000);assert(hook_refresh());background_frame(11,1,now+2000);background_update(nullptr,0,100);
    assert(background_state && background_nodes.active && background_sequence==1);
    assert(get_parent(original.object)==nullptr);
    unsigned before=uploads;background_update(nullptr,0,200);assert(uploads==before);
    background_frame(11,2,now+2000);background_update(nullptr,0,300);assert(uploads==before+1);
    background_frame(11,3,now+2000,true);background_update(nullptr,0,400);
    assert(!background_state && get_child(bg.object,0).object==original.object);
    background_frame(11,4,now+2000);background_update(nullptr,0,500);assert(background_nodes.active);
    lease(12,now+5000);assert(hook_refresh());assert(!background_state);
    background_update(nullptr,0,600);assert(!background_state); // previous generation cannot remount
    background_frame(12,1,now+2000);background_update(nullptr,0,700);assert(background_nodes.active);
    Ref saved=scene;scene=make_node(view("OtherRoot"));
    background_update(nullptr,0,800);
    assert(background_state && !background_nodes.active && get_parent(original.object)==bg.object);
    scene=saved;background_update(nullptr,0,900);assert(background_nodes.active);
    lease(12,now-1);assert(!hook_refresh());assert(!background_state && get_parent(original.object)==bg.object);
    hook_after_swap();
    background_restore();background_restore();unlink(GBF_PATH);unlink(GHL_PATH);
    scene=Ref{};return 0;
}
