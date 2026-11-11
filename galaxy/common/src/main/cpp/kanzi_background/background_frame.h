/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_BACKGROUND_FRAME_H
#define GALAXY_BACKGROUND_FRAME_H
#include <stdint.h>
#include <stddef.h>
/* GBF1: little endian, tight RGBA8, top row first. Atomic rename publishes a
 * complete RAM-file; neither the UI nor the producer holds a PMEM slot address. */
#define GBF_BYTES (1920u * 532u * 4u)
#define GBF_HEADER 48u

#ifndef GBF_PATH
#define GBF_PATH "/dev/shmem/autoservice-cluster.frame"

#endif
#ifndef GHR_PATH
#define GHR_PATH "/otaupdate/autoservice-media/hook-status.bin"
#endif
#ifndef GBI_PATH
#define GBI_PATH "/otaupdate/autoservice-media/background.bin"
#endif
#ifndef GHL_PATH
#define GHL_PATH "/otaupdate/autoservice-media/hook.bin"

#endif
static inline uint32_t gbf_u32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static inline uint64_t gbf_u64(const unsigned char *p) {
    return gbf_u32(p) | (uint64_t)gbf_u32(p+4)<<32;
}
static inline void gbf_put32(unsigned char *p, uint32_t v) {
    unsigned i; for(i=0;i<4;++i)p[i]=(unsigned char)(v>>(8*i));
}
static inline void gbf_put64(unsigned char *p, uint64_t v) {
    gbf_put32(p,(uint32_t)v);gbf_put32(p+4,(uint32_t)(v>>32));
}
/* GHL1: enabled flags (music=1, background=2), generation, expiry epoch ms,
 * source (image=1, CarPlay=2, local-map=3), reserved zero. No path or Shell. */
static inline int ghl_valid(const unsigned char *p, uint64_t now) {
    uint64_t until=gbf_u64(p+16);
    return gbf_u32(p)==0x314c4847 && !(gbf_u32(p+4)&~3u) && gbf_u64(p+8) &&
        until>now && until-now<=6000 && gbf_u32(p+24)<=3 && !gbf_u32(p+28);
}
static inline int gbf_valid(const unsigned char *p, uint64_t now, uint64_t generation, unsigned source) {
    uint64_t until=gbf_u64(p+32);
    return gbf_u32(p)==0x31464247 && gbf_u32(p+4)==1920 && gbf_u32(p+8)==532 &&
        gbf_u32(p+12)==7680 && gbf_u64(p+16)==generation && gbf_u64(p+24) &&
        ((source==1 && until==0) || (until>now && until-now<=6000)) && gbf_u32(p+40)==source && gbf_u32(p+44)==GBF_BYTES;
}
/* QNX's reported UTC is +8h on the captured vehicle. As in GKM1, a lone
 * retained file cannot establish a skew: require two live renewals first. */
struct ghl_clock {
    uint64_t generation, pending_generation, pending_until, pending_local;
    int64_t offset;
    int ready;
};
static inline int ghl_source_now(struct ghl_clock *clock, const unsigned char *p,
                                uint64_t local, uint64_t *source) {
    uint64_t until=gbf_u64(p+16), generation=gbf_u64(p+8);
    if(until<5000 || until>INT64_MAX || local>INT64_MAX ||
       !ghl_valid(p,until-5000) || (clock->generation && generation<clock->generation))return 0;
    int64_t adjusted=(int64_t)local+(clock->ready ? clock->offset : 0);
    if(adjusted>=0 && ghl_valid(p,(uint64_t)adjusted)) {
        *source=(uint64_t)adjusted;clock->generation=generation;return 1;
    }
    // Once calibrated, an unchanged/expired frame must expire, never re-anchor itself.
    if(clock->ready)return 0;
    int64_t offset=(int64_t)(until-5000)-(int64_t)local;
    if(offset>=-15000 && offset<=15000)return 0;
    if(clock->pending_generation==generation && until>clock->pending_until &&
       until-clock->pending_until<=6000 && local>=clock->pending_local &&
       local-clock->pending_local<=6000) {
        clock->offset=offset;clock->ready=1;clock->generation=generation;
        *source=until-5000;return 1;
    }
    if(clock->pending_generation!=generation || until>clock->pending_until) {
        clock->pending_generation=generation;clock->pending_until=until;clock->pending_local=local;
    }
    return 0;
}
#endif
