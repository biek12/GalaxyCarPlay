/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_COMPACT_MUSIC_REGION_H
#define GALAXY_COMPACT_MUSIC_REGION_H
#ifndef GKM_LEGACY_TEXT_LAYOUT
#ifndef GKM_COVER_PATH
#define GKM_COVER_PATH "/otaupdate/autoservice-media/cover.bin"
#endif
/* G733 ELF 385d8fc5: optional<GraphicsFormat> uses an indirect result, engaged byte at0,
 * enum at4 (0x32f674..0x32f688). The nontrivial destructor preserves that ABI. */
struct OptionalFormat {
    uint8_t present, padding[3]; int value;
    ~OptionalFormat() {}
};
static_assert(sizeof(OptionalFormat) == 8 && offsetof(OptionalFormat, value) == 4, "G733 optional enum");
extern OptionalFormat graphics_format(View) asm("_ZN5kanzi24findGraphicsFormatByNameEN5boost17basic_string_viewIcSt11char_traitsIcEEE");
extern Ref create_texture(void *, int, int, bool, int, unsigned, unsigned, unsigned, View) asm("_ZN5kanzi7Texture6createEPNS_6DomainENS0_4TypeEibNS_14GraphicsFormatEjjjN5boost17basic_string_viewIcSt11char_traitsIcEEE");
extern "C" void texture_data(void *, const uint8_t *) asm("_ZN5kanzi7Texture7setDataEPKh");
extern Ref create_image(void *, View, Ref) asm("_ZN5kanzi7Image2D6createEPNS_6DomainEN5boost17basic_string_viewIcSt11char_traitsIcEEESt10shared_ptrINS_7TextureEE");
extern "C" void text_alignment(void *, int) asm("_ZN5kanzi20TextBlockConceptImplINS_6Node2DENS_11TextBlock2DEE26setTextHorizontalAlignmentENS_16TextBlockConcept23TextHorizontalAlignmentE");
/* G733 0x20d558 measureOverride: Vector2参数/结果均为s0,s1 HFA，
 * 0x20d644取实际排版宽度。用实际字体排版测量，禁止按UTF-8字节数猜宽度。 */
extern Vec2 measure_text(void *, Vec2) asm("_ZN5kanzi11TextBlock2D15measureOverrideENS_7Vector2E");
static void prepare_compact_scroll(State *s) {
    Vec2 measured=measure_text(s->labels[3].object, Vec2{65536,44});
    const float width=measured.x > 0 && measured.x < 65536 ? measured.x : 320;
    s->scroll_distance=width > 320 ? width-320 : 0;
    s->scroll_ms=0; s->scroll_offset=-1;
    set_float(property(s->labels[3].object),width_prop,width > 320 ? width : 320);
    text_alignment(s->labels[3].object,width > 320 ? 0 : 2);
}
static void animate_compact(State *s,long delta_ms) {
    if(!s->mounted)return;
    if((s->playback_flags&2) && delta_ms>0)s->scroll_ms+=(uint64_t)(delta_ms<1000 ? delta_ms : 1000);
    float offset=s->scroll_ms>800 ? (s->scroll_ms-800)*.028f : 0;
    if(offset>s->scroll_distance)offset=s->scroll_distance;
    if(offset==s->scroll_offset)return;
    const Srt2D transform{1,1,0,-offset,148};
    set_srt(property(s->labels[3].object),render_transform_prop,transform);
    s->scroll_offset=offset;
}
static void place(void *node, float x, float y, float width, float height) {
    clear_geometry(node);
    set_float(property(node), width_prop, width);
    set_float(property(node), height_prop, height);
    const Srt2D transform{1, 1, 0, x, y};
    set_srt(property(node), render_transform_prop, transform);
}
static Ref image(void *app, const char *name, unsigned width, unsigned height,
                 const uint8_t *pixels, Ref *texture) {
    // GraphicsFormat查找表使用Kanzi枚举名；rgba8888只是原厂窗口配置名。
    auto format = graphics_format(view("R8G8B8A8_UNORM"));
    if (!format.present) { phase("image_format_unavailable"); return {}; }
    // Texture::CreateInfo2D at0x459428 identifies Type0; one explicit mip level.
    Ref tex = create_texture(get_domain(app), 0, 1, false, format.value, width, height, 1, view(name));
    if (!tex.object) { phase("image_texture_unavailable"); return {}; }
    texture_data(tex.object, pixels);
    Ref node = create_image(get_domain(app), view(name), tex);
    if (node.object) *texture = tex;
    else phase("image_node_unavailable");
    return node;
}
static Ref reference_in_tree(const Ref &root,void *target,unsigned depth,unsigned *budget) {
    if(!root.object||!depth||!*budget)return {};
    --*budget;
    if(root.object==target)return root;
    const size_t count=child_count(root.object);if(count>1024)return {};
    for(size_t i=0;i<count&&*budget;++i){Ref found=reference_in_tree(get_child(root.object,i),target,depth-1,budget);if(found.object)return found;}
    return {};
}
static bool mount_compact(State *s, void *app, const Ref &root) {
    unsigned budget = 4096;
    Ref text_area = find_text(root, 32, &budget);
    if (!text_area.object) { phase("music_text_unavailable"); return false; }
    Ref media = text_area;
    for (unsigned depth = 0; depth < 8 && !is_named(media.object, view("Media")); ++depth) {
        void *parent = get_parent(media.object);
        if (!parent) { phase("music_parent_unavailable"); return false; }
        unsigned parent_budget=4096;
        media = reference_in_tree(root,parent,32,&parent_budget);
        if(!media.object){phase("music_parent_reference_unavailable");return false;}
    }
    if (!is_named(media.object, view("Media"))) { phase("music_region_unavailable"); return false; }
    Ref first = get_child(text_area.object, 0), second = get_child(text_area.object, 1);
    Ref title = is_named(first.object, view("Media_Title")) ? first : second;
    Ref artist = is_named(first.object, view("Media_Artist")) ? first : second;
    Ref panel = create_empty(get_domain(app), view("GalaxyCompactMusic"));
    if (!panel.object) return false;
    place(panel.object, 1400, 128, 320, 316);
    set_bool(property(panel.object), clip_prop, true);
    s->panel = panel;
    const char *names[] = {"GalaxyTitle", "GalaxyArtist", "GalaxyLyricBefore", "GalaxyLyricCurrent",
        "GalaxyLyricNext", "GalaxyLyricAfter", "GalaxyElapsed", "GalaxyPlayback", "GalaxyDuration"};
    const float x[] = {100,100,0,0,0,0,0,100,160};
    const float y[] = {4,47,112,148,192,230,298,298,298};
    const float widths[] = {220,220,320,320,320,320,100,60,60};
    const float heights[] = {33,26,28,44,30,26,18,18,18};
    const float sizes[] = {26,18,18,32,20,18,15,15,15};
    const float opacity[] = {1,.68f,.42f,1,.68f,.42f,.68f,.68f,.68f};
    for (unsigned i = 0; i < 9; ++i) {
        Ref label = create_text(get_domain(app), view(names[i]));
        if (!label.object) { restore(s); return false; }
        copy_values(property(label.object), property(i == 0 || i == 3 ? title.object : artist.object));
        place(label.object, x[i], y[i], widths[i], heights[i]);
        set_bool(property(label.object), clip_prop, true);
        set_float(property(label.object), opacity_prop, opacity[i]);
        text_alignment(label.object, i == 8 ? 1 : ((i >= 2 && i <= 5) || i == 7) ? 2 : 0);
        set_font_size(label.object, sizes[i]); text(label.object, "");
        add_child(panel.object, label); s->labels[i] = label;
    }
    const uint8_t gray[] = {38,42,50,255}, blue[] = {86,171,255,255};
    s->cover = image(app, "GalaxyCover", 1, 1, gray, &s->cover_texture);
    s->progress_track = image(app, "GalaxyProgressTrack", 1, 1, gray, &s->progress_track_texture);
    s->progress_fill = image(app, "GalaxyProgressFill", 1, 1, blue, &s->progress_fill_texture);
    if (!s->cover.object || !s->progress_track.object || !s->progress_fill.object) { restore(s); return false; }
    place(s->cover.object, 0,0,80,80); add_child(panel.object, s->cover);
    s->cover_placeholder = create_text(get_domain(app), view("GalaxyCoverMissing"));
    if (!s->cover_placeholder.object) { restore(s); return false; }
    copy_values(property(s->cover_placeholder.object), property(artist.object));
    place(s->cover_placeholder.object, 0,31,80,22); text_alignment(s->cover_placeholder.object, 2);
    set_font_size(s->cover_placeholder.object, 14); text(s->cover_placeholder.object, "暂无封面");
    add_child(panel.object, s->cover_placeholder);
    place(s->progress_track.object,0,282,220,3); place(s->progress_fill.object,0,282,0,3);
    add_child(panel.object,s->progress_track); add_child(panel.object,s->progress_fill);
    s->root=root; s->target=media;
    if (!s->replacement.install_children(media, panel)) { restore(s); return false; }
    s->mounted=true; phase("compact_region_mounted"); return true;
}
static uint64_t cover_track_key(const gkm_frame &frame) {
    uint64_t key=14695981039346656037ULL;
    for(unsigned i=0;i<16;++i)key=(key ^ frame.session[i])*1099511628211ULL;
    for (unsigned field=0; field<2; ++field) {
        const uint8_t *p=(const uint8_t *)frame.text[field];
        do { key=(key ^ *p) * 1099511628211ULL; } while (*p++);
    }
    return key;
}
static void update_cover(State *s, void *app, const gkm_frame &frame) {
    const uint64_t key=cover_track_key(frame);
    // Never display an old song's cover while the next transfer is incomplete.
    if (s->cover_track != key) {
        set_float(property(s->cover.object),opacity_prop,0);
        set_float(property(s->cover_placeholder.object),opacity_prop,1);
        s->cover_revision=0; s->cover_track=key;
    }
    uint8_t header[32];
    int fd=open(GKM_COVER_PATH,0);
    if (fd<0) return;
    long count=read(fd,header,sizeof(header));
    uint32_t width=0,height=0,length=0; uint64_t track=0,revision=0;
    if (count==32) {
        memcpy(&width,header+4,4); memcpy(&height,header+8,4);
        memcpy(&track,header+12,8); memcpy(&length,header+20,4); memcpy(&revision,header+24,8);
    }
    if (count!=32 || memcmp(header,"GCA1",4) || track!=key || !revision ||
        revision==s->cover_revision || !width || width>160 || !height || height>160 || length!=width*height*4) {
        close(fd); return;
    }
    uint8_t *pixels=(uint8_t *)malloc(length+1);
    if (!pixels) { close(fd); return; }
    size_t total=0;
    while (total<length+1) {
        long n=read(fd,pixels+total,length+1-total); if (n<=0) break; total+=(size_t)n;
    }
    close(fd);
    if (total==length) {
        Ref texture, cover=image(app,"GalaxyAlbumCover",width,height,pixels,&texture);
        if (cover.object) {
            place(cover.object,0,0,80,80);
            size_t index=child_index(s->panel.object,s->cover.object);
            remove_child(s->panel.object,index); insert_child(s->panel.object,index,cover);
            s->cover=cover; s->cover_texture=texture; s->cover_revision=revision;
            set_float(property(s->cover_placeholder.object),opacity_prop,0);
            phase("compact_real_cover_uploaded");
        }
    }
    free(pixels);
}
static void update_compact(State *s, void *app, const gkm_frame &frame, const char *progress) {
    update_cover(s,app,frame);
    char elapsed[40],duration[40]; unsigned split=0;
    while (progress[split] && progress[split]!='/') ++split;
    unsigned start=frame.flags&2 ? 0 : 3;
    unsigned n=0; for (unsigned i=start; i<split && n<38; ++i) elapsed[n++]=progress[i];
    if (n && elapsed[n-1]==' ') --n; elapsed[n]=0;
    n=0; if (progress[split]) { ++split; if (progress[split]==' ') ++split; }
    while (progress[split] && n<39) duration[n++]=progress[split++]; duration[n]=0;
    text(s->labels[6].object,elapsed); text(s->labels[7].object,"");
    text(s->labels[8].object,duration);
    float fraction=(frame.flags&12)==12 && frame.duration_ms ? (float)frame.position_ms/frame.duration_ms : 0;
    if (fraction>1) fraction=1;
    set_float(property(s->progress_fill.object),width_prop,220*fraction);
}
#endif
#endif
