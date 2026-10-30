/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_pixels.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

int main(void)
{
    uint8_t source[64], destination[64], before[64];
    size_t pixel_stride, x, y;
    for (pixel_stride = 1; pixel_stride <= 8; ++pixel_stride) {
        size_t source_stride = 3 * pixel_stride + 5;
        size_t required = source_stride + 2 * pixel_stride + (pixel_stride > 2 ? 3 : pixel_stride);
        memset(source, 0x19, sizeof(source));
        memset(destination, 0xa5, sizeof(destination));
        for (y = 0; y < 2; ++y) for (x = 0; x < 3; ++x) {
            uint8_t *pixel = source + y * source_stride + x * pixel_stride;
            pixel[0] = (uint8_t)(10 + x + y);
            if (pixel_stride > 1) pixel[1] = (uint8_t)(50 + x + y);
            if (pixel_stride > 2) pixel[2] = (uint8_t)(90 + x + y);
            if (pixel_stride > 3) pixel[3] = 0; /* 源alpha不传输；输出必须255。 */
        }
        assert(galaxy_hab_pack_bgra(destination, sizeof(destination), 16, source,
                                    required, source_stride, pixel_stride, 3, 2) == 0);
        for (y = 0; y < 2; ++y) for (x = 0; x < 3; ++x) {
            const uint8_t *input = source + y * source_stride + x * pixel_stride;
            const uint8_t *output = destination + y * 16 + x * 4;
            assert(output[0] == input[pixel_stride > 2 ? 2 : 0]);
            assert(output[1] == input[pixel_stride > 1 ? 1 : 0]);
            assert(output[2] == input[0] && output[3] == 255);
        }
        assert(destination[12] == 0xa5 && destination[15] == 0xa5 && destination[28] == 0xa5);
        memcpy(before, destination, sizeof(before));
        assert(galaxy_hab_pack_bgra(destination, 27, 16, source, required,
                                    source_stride, pixel_stride, 3, 2) == -EMSGSIZE);
        assert(galaxy_hab_pack_bgra(destination, sizeof(destination), 16, source, required - 1,
                                    source_stride, pixel_stride, 3, 2) == -EMSGSIZE);
        assert(memcmp(before, destination, sizeof(before)) == 0);
    }
    memcpy(before, destination, sizeof(before));
    assert(galaxy_hab_pack_bgra(destination, 64, 16, source, 64, 16, 4, 0, 2) == -EINVAL);
    assert(galaxy_hab_pack_bgra(destination, 64, 11, source, 64, 16, 4, 3, 2) == -EINVAL);
    assert(galaxy_hab_pack_bgra(destination, 64, 16, source, 64, 16, 0, 3, 2) == -EINVAL);
    assert(galaxy_hab_pack_bgra(destination, 64, 16, source, 64, 16, 4, SIZE_MAX, 2) == -EINVAL);
    assert(galaxy_hab_pack_bgra(destination, 64, SIZE_MAX, source, 64, 16, 4, 3, SIZE_MAX) == -EINVAL);
    assert(galaxy_hab_pack_bgra(source, 64, 16, source, 64, 16, 4, 3, 2) == -EINVAL);
    assert(memcmp(before, destination, sizeof(before)) == 0);
    puts("HAB RGB-to-BGRA stride/alpha/bounds contracts passed");
    return 0;
}
