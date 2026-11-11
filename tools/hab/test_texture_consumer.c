/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_qnx.h"
#define GBF_PATH ".work/kanzi-music/receiver.frame"
#define GHR_PATH ".work/kanzi-music/receiver-status.bin"
#define GHL_PATH ".work/kanzi-music/receiver-hook.bin"
#include "qnx_texture_consumer.h"
#include <assert.h>
int main(void) {
    unsigned char lease[32]={0},header[48],pixel[4];
    uint64_t now=(uint64_t)time(NULL)*1000;
    gbf_put32(lease,0x314c4847);gbf_put32(lease+4,2);gbf_put64(lease+8,123);
    gbf_put64(lease+16,now-28800000+5000);gbf_put32(lease+24,2);
    FILE *f=fopen(GHL_PATH,"wb");assert(f);assert(fwrite(lease,1,32,f)==32);assert(!fclose(f));
    // Captured QNX UTC is eight hours ahead of the Android source. The receipt
    // carries both source time and local sample time; the short-lived checker uses both.
    unsigned char status[64]={0};gbf_put32(status,0x31524847);gbf_put64(status+8,123);
    gbf_put64(status+16,now-28800000+3000);gbf_put32(status+48,1);gbf_put64(status+56,now);
    f=fopen(GHR_PATH,"wb");assert(fwrite(status,1,64,f)==64);fclose(f);
    assert(!texture_health());
    struct galaxy_hab_qnx q={0};struct texture_consumer c={0};
    texture_select(&q,&c);assert(q.consumer.prepare);
    struct galaxy_hab_layout layout={0};layout.count=1;layout.streams[0].target.width=1920;layout.streams[0].target.height=532;
    assert(!texture_prepare(&c,&layout));uint8_t *pixels;size_t capacity;uint32_t stride;
    assert(!texture_acquire(&c,0,&pixels,&capacity,&stride));assert(capacity==GBF_BYTES && stride==7680);
    memset(pixels,0,capacity);pixels[0]=9;pixels[1]=8;pixels[2]=7;
    struct galaxy_hab_frame_info info={0};assert(!texture_present(&c,&info));
    f=fopen(GBF_PATH,"rb");assert(f);assert(fread(header,1,48,f)==48);assert(gbf_valid(header,now,123,2));
    assert(fread(pixel,1,4,f)==4);assert(pixel[0]==7 && pixel[1]==8 && pixel[2]==9 && pixel[3]==255);fclose(f);
    gbf_put64(lease+8,124);f=fopen(GHL_PATH,"wb");assert(fwrite(lease,1,32,f)==32);fclose(f);
    assert(texture_present(&c,&info)==-EINTR);assert(texture_health()==1);
    texture_close(&c);texture_close(&c);assert(!c.pixels);assert(!(f=fopen(GBF_PATH,"rb")));
    unlink(GHL_PATH);unlink(GHR_PATH);return 0;
}
