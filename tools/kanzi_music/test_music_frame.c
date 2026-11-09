/* SPDX-License-Identifier: GPL-3.0-only */
#include "music_frame.h"
#include <assert.h>
#include <stdint.h>
#include <string.h>
static void put(uint8_t *p, uint64_t n, unsigned length) {
    for (unsigned i = 0; i < length; ++i)
        p[i] = (uint8_t)(n >> (8 * i));
}
int main(void) {
    uint8_t p[GKM_MAX_FRAME + 1] = {0};
    struct gkm_frame out, old;
    memcpy(p, "GKM1", 4);
    put(p + 4, 75, 4);
    put(p + 8, 1, 2);
    put(p + 10, 3, 2);
    put(p + 12, 42, 8);
    put(p + 28, 1, 8);
    put(p + 36, 8000, 8);
    put(p + 44, 1234, 8);
    put(p + 52, 10000, 8);
    put(p + 60, 3, 2);
    memcpy(p + 72, "\xe6\x9b\xb2", 3);
    assert(gkm_decode(p, 75, 1000, &out) == 0);
    assert(out.flags == 3 && out.position_ms == 1234);
    assert(strcmp(out.text[0], "曲") == 0);
    old = out;
    assert(gkm_decode(p, 74, 1000, &out) < 0);
    assert(memcmp(&out, &old, sizeof(out)) == 0);
    assert(gkm_decode(p, 75, 8001, &out) < 0);
    assert(gkm_decode(p, 75, 0, &out) == 0);
    put(p + 36, 20000, 8);
    assert(gkm_decode(p, 75, 1000, &out) < 0);
    put(p + 36, 8000, 8);
    p[72] = 0xc0;
    assert(gkm_decode(p, 75, 1000, &out) < 0);
    p[72] = 0xe6;
    put(p + 60, 1025, 2);
    assert(gkm_decode(p, 75, 1000, &out) < 0);
    put(p + 60, 3, 2);
    put(p + 10, 16, 2);
    assert(gkm_decode(p, 75, 1000, &out) < 0);
    put(p + 10, 0, 2);
    assert(gkm_decode(p, 75, 1000, &out) == 0 && out.flags == 0);
    uint32_t seed = 42;
    for (unsigned trial = 0; trial < 5000; ++trial) {
        for (unsigned j = 0; j < sizeof(p); ++j) {
            seed = seed * 1664525u + 1013904223u;
            p[j] = (uint8_t)(seed >> 24);
        }
        (void)gkm_decode(p, trial % (sizeof(p) + 1), 1000, &out);
    }
    return 0;
}
