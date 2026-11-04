/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_KANZI_MUSIC_FRAME_H
#define GALAXY_KANZI_MUSIC_FRAME_H
#include <stddef.h>
#include <stdint.h>
#define GKM_MAX_FRAME 8192
#define GKM_FIELDS 6
struct gkm_frame {
    uint8_t session[16];
    uint64_t revision, expires_ms, position_ms, duration_ms;
    uint16_t flags;
    char text[GKM_FIELDS][1025]; /* 标题、歌手、上句、当前句、下两句。 */
};
/* 完整校验后才覆盖out；不接受截断、尾随数据或非UTF-8字符串。 */
#ifdef __cplusplus
extern "C"
#endif
    int
    gkm_decode(const uint8_t *bytes, size_t count, uint64_t now_ms, struct gkm_frame *out);
#endif
