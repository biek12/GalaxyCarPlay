/* SPDX-License-Identifier: GPL-3.0-only */
/* Receiver-owned RAM-file snapshot. Imported PMEM is never exposed to Kanzi.
 * Success acknowledges a validated private copy, not a visible EGL submission. */
#include "../../galaxy/common/src/main/cpp/kanzi_background/background_frame.h"
#include <errno.h>
#include <stdlib.h>
#ifndef GALAXY_QNX_ABI_CANDIDATE
#include <stdio.h>
#endif
#include <string.h>
 #ifdef GALAXY_QNX_ABI_CANDIDATE
extern void *fopen(const char *, const char *);
extern size_t fread(void *, size_t, size_t, void *);
extern size_t fwrite(const void *, size_t, size_t, void *);
extern int fclose(void *);
 #endif
extern int rename(const char *, const char *);
extern int unlink(const char *);
extern long time(long *);
static uint64_t texture_serial;
struct texture_consumer { unsigned char *pixels; uint64_t generation, sequence; };
static int texture_health(void);
/* Health is calibrated by the live UI; this short-lived executable cannot
 * infer a source clock from one retained lease by itself. */
static int texture_lease(unsigned char *lease) {
    void *file=fopen(GHL_PATH,"rb");
    if(!file)return 0;
    int valid=fread(lease,1,32,file)==32;
    unsigned char extra;
    valid=valid && fread(&extra,1,1,file)==0;
    int closed=fclose(file);
    return valid && !closed && !texture_health() &&
        (gbf_u32(lease+4)&2) && gbf_u32(lease+24)==2;
}
static int texture_prepare(void *context, const struct galaxy_hab_layout *layout) {
    struct texture_consumer *c=context;
    unsigned char lease[32];
    if(layout->count!=1 || layout->streams[0].target.width!=1920 ||
       layout->streams[0].target.height!=532 || !texture_lease(lease))return -EINVAL;
    c->generation=gbf_u64(lease+8);c->sequence=0;
    c->pixels=malloc(GBF_BYTES);
    return c->pixels ? 0 : -ENOMEM;
}
static int texture_acquire(void *context,uint32_t stream,uint8_t **pixels,size_t *capacity,uint32_t *stride) {
    struct texture_consumer *c=context;
    if(stream || !c->pixels)return -EINVAL;
    *pixels=c->pixels;*capacity=GBF_BYTES;*stride=7680;return 0;
}
static int texture_present(void *context,const struct galaxy_hab_frame_info *info) {
    struct texture_consumer *c=context;
    unsigned char lease[32],header[GBF_HEADER]={0};
    size_t i;
    (void)info;
    if(!texture_lease(lease) || gbf_u64(lease+8)!=c->generation)return -EINTR;
    gbf_put32(header,0x31464247);gbf_put32(header+4,1920);gbf_put32(header+8,532);
    gbf_put32(header+12,7680);gbf_put64(header+16,c->generation);
    gbf_put64(header+24,++texture_serial + (uint64_t)time(NULL)*1000000);gbf_put64(header+32,(uint64_t)time(NULL)*1000+2000);
    gbf_put32(header+40,2);gbf_put32(header+44,GBF_BYTES);
    // HAB BGRA -> tight RGBA8, retaining top-to-bottom orientation.
    for(i=0;i<GBF_BYTES;i+=4) { unsigned char b=c->pixels[i];c->pixels[i]=c->pixels[i+2];c->pixels[i+2]=b;c->pixels[i+3]=255; }
    void *file=fopen(GBF_PATH ".tmp","wb");
    if(!file)return -EIO;
    int ok=fwrite(header,1,sizeof(header),file)==sizeof(header) && fwrite(c->pixels,1,GBF_BYTES,file)==GBF_BYTES;
    int closed=fclose(file);
    if(!ok || closed || rename(GBF_PATH ".tmp",GBF_PATH)) { unlink(GBF_PATH ".tmp"); return -EIO; }
    return 0;
}
static void texture_close(void *context) {
    struct texture_consumer *c=context;
    if(c->pixels) { free(c->pixels);c->pixels=NULL;unlink(GBF_PATH);unlink(GBF_PATH ".tmp"); }
}
static void texture_select(struct galaxy_hab_qnx *q,struct texture_consumer *c) {
    unsigned char lease[32];
    if(!texture_lease(lease))return;
    q->consumer.context=c;q->consumer.prepare=texture_prepare;q->consumer.acquire=texture_acquire;
    q->consumer.present=texture_present;q->consumer.close=texture_close;
}

static int texture_health(void) {
    unsigned char lease[32],status[64];
    void *file=fopen(GHL_PATH,"rb");
    if(!file)return 1;
    int ok=fread(lease,1,32,file)==32;fclose(file);
    if(!ok)return 1;
    file=fopen(GHR_PATH,"rb");
    if(!file)return 1;
    ok=fread(status,1,64,file)==64;fclose(file);
    uint64_t local=(uint64_t)time(NULL)*1000,sample=gbf_u64(status+56),until=gbf_u64(status+16);
    if(!ok || gbf_u32(status)!=0x31524847 || local<sample || local-sample>=3000 || until<3000)return 1;
    uint64_t source_now=until-3000+(local-sample);
    return !(ghl_valid(lease,source_now) && gbf_u32(lease+4) &&
        gbf_u64(status+8)==gbf_u64(lease+8) && gbf_u32(status+48));
}
