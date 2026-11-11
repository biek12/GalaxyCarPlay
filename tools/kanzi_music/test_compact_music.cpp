/* SPDX-License-Identifier: GPL-3.0-only */
#define GKM_COMPACT_TEST
#ifndef GKM_COVER_PATH
#define GKM_COVER_PATH ".work/kanzi-music/test-cover.rgba"
#endif
#include "test_music_component.cpp"
static unsigned uploads;
OptionalFormat graphics_format(View name) {
    assert(!memcmp(name.data,"R8G8B8A8_UNORM",name.size));
    return {1,{0,0,0},42};
}
Ref create_texture(void *,int type,int levels,bool mip,int format,unsigned w,unsigned h,unsigned d,View name) {
    assert(type==0 && levels==1 && !mip && format==42 && w && h && d==1);
    return make_node(name);
}
extern "C" void texture_data(void *,const uint8_t *) { ++uploads; }
Ref create_image(void *,View name,Ref texture) { assert(texture.object); return make_node(name); }
extern "C" void text_alignment(void *,int alignment) { assert(alignment>=0 && alignment<=2); }
Vec2 measure_text(void *node,Vec2 available) {
    assert(available.x==65536 && available.y==44);
    return Vec2{(float)strlen(((FakeNode *)node)->content)*16,32};
}
static Ref full_scene() {
    Ref r=make_scene(), media=make_node(view("Media"));
    Ref target=get_child(r.object,0); remove_child(r.object,0);
    add_child(media.object,make_node(view("Media_Normal")));
    add_child(media.object,target);
    add_child(media.object,make_node(view("Media_NoMusic")));
    add_child(r.object,media); return r;
}
static void cover_file(uint64_t key,uint64_t revision,bool truncated=false) {
    uint8_t bytes[48]={}; memcpy(bytes,"GCA1",4);
    put(bytes+4,2,4); put(bytes+8,2,4); put(bytes+12,key,8); put(bytes+20,16,4); put(bytes+24,revision,8);
    void *f=fopen(GKM_COVER_PATH,"w"); assert(f);
    assert(fwrite(bytes,1,truncated ? 47 : 48,f)==(truncated ? 47u : 48u)); fclose(f);
}
int main() {
    initialize_types(); scene=full_scene(); Ref media=get_child(scene.object,0);
    Ref originals[3]; for(unsigned i=0;i<3;++i) originals[i]=get_child(media.object,i);
    frame(100,1,true); galaxy_music_on_update(nullptr,1000);
    assert(state && state->mounted && child_count(media.object)==1);
    auto *panel=(FakeNode *)state->panel.object;
    assert(panel->width==320 && panel->height==316 && panel->transform.x==1400 && panel->transform.y==128);
    assert(((FakeNode *)state->labels[3].object)->font_size==32);
    assert(((FakeNode *)state->cover.object)->width==80);
    for(unsigned i=0;i<9;++i) {
        auto *row=(FakeNode *)state->labels[i].object;
        assert((i==3 || row->transform.x+row->width<=320) && row->transform.y+row->height<=316);
    }
    assert(uploads==3);
    auto *active=(FakeNode *)state->labels[3].object;
    strcpy(active->content,"A current lyric longer than the available viewport width");
    prepare_compact_scroll(state);state->playback_flags=3;
    animate_compact(state,800);assert(active->transform.x==0);
    animate_compact(state,1000);assert(active->transform.x<0);
    const float paused=active->transform.x;state->playback_flags=1;
    animate_compact(state,1000);assert(active->transform.x==paused);
    strcpy(active->content,"Current");prepare_compact_scroll(state);animate_compact(state,0);
    assert(active->transform.x==0);

    gkm_frame track{}; memcpy(track.text[0],"Track",6); memcpy(track.text[1],"Artist",7);
    memcpy(track.session,state->session,16);
    uint64_t key=cover_track_key(track);
    cover_file(key,1); frame(100,2,true); galaxy_music_on_update(nullptr,1000); assert(uploads==4);
    frame(100,3,true); galaxy_music_on_update(nullptr,1000); assert(uploads==4);
    cover_file(key,2,true); frame(100,4,true); galaxy_music_on_update(nullptr,1000); assert(uploads==4);
    frame(100,5,true,"Other"); galaxy_music_on_update(nullptr,1000);
    assert(state->cover_revision==0 && uploads==4); // old cover rejected on next track
    frame(100,6,false); galaxy_music_on_update(nullptr,1000);
    assert(!state->mounted && child_count(media.object)==3);
    for(unsigned i=0;i<3;++i) assert(get_child(media.object,i).object==originals[i].object);
    frame(100,7,true); galaxy_music_on_update(nullptr,1000);
    frame(100,8,true,"Track",(uint64_t)time(nullptr)*1000-1); galaxy_music_on_update(nullptr,1000);
    assert(!state->mounted && child_count(media.object)==3);
    galaxy_music_on_shutdown(nullptr);
    scene=Ref{}; unlink(GKM_STATE_PATH); unlink(GKM_COVER_PATH);
    return 0;
}
