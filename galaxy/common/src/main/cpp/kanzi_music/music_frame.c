/* SPDX-License-Identifier: GPL-3.0-only */
#include "music_frame.h"
#include <string.h>
static uint64_t word(const uint8_t *p, unsigned count) {
    uint64_t n = 0;
    for (unsigned i = 0; i < count; ++i)
        n |= (uint64_t)p[i] << (i * 8);
    return n;
}
static int utf8(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n;) {
        uint8_t c = p[i++];
        if (!c)
            return 0;
        if (c < 0x80)
            continue;
        unsigned k;
        uint32_t value, minimum;
        if (c >= 0xc2 && c <= 0xdf) {
            k = 1;
            value = c & 31;
            minimum = 0x80;
        } else if (c >= 0xe0 && c <= 0xef) {
            k = 2;
            value = c & 15;
            minimum = 0x800;
        } else if (c >= 0xf0 && c <= 0xf4) {
            k = 3;
            value = c & 7;
            minimum = 0x10000;
        } else
            return 0;
        if (n - i < k)
            return 0;
        while (k--) {
            c = p[i++];
            if ((c & 0xc0) != 0x80)
                return 0;
            value = (value << 6) | (c & 63);
        }
        if (value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff))
            return 0;
    }
    return 1;
}
int gkm_decode(const uint8_t *p, size_t count, uint64_t now, struct gkm_frame *out) {
    struct gkm_frame next;
    if (!p || !out || count < 72 || count > GKM_MAX_FRAME || memcmp(p, "GKM1", 4) ||
        word(p + 4, 4) != count || word(p + 8, 2) != 1 || (word(p + 10, 2) & ~15u))
        return -1;
    memset(&next, 0, sizeof(next));
    next.flags = (uint16_t)word(p + 10, 2);
    memcpy(next.session, p + 12, 16);
    next.revision = word(p + 28, 8);
    next.expires_ms = word(p + 36, 8);
    next.position_ms = word(p + 44, 8);
    next.duration_ms = word(p + 52, 8);
    if (!next.revision || next.expires_ms < now || next.expires_ms - now > 15000 ||
        next.position_ms > 604800000 || next.duration_ms > 604800000)
        return -2;
    size_t offset = 72;
    for (unsigned i = 0; i < GKM_FIELDS; ++i) {
        size_t length = (size_t)word(p + 60 + i * 2, 2);
        if (length > 1024 || length > count - offset || !utf8(p + offset, length))
            return -3;
        memcpy(next.text[i], p + offset, length);
        offset += length;
    }
    if (offset != count || ((next.flags & 1) && !next.text[0][0]))
        return -4;
    *out = next;
    return 0;
}
