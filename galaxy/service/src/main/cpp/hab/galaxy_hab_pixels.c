/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_pixels.h"
#include <errno.h>
#include <stdint.h>

static int plane_size(size_t rows, size_t stride, size_t tail, size_t *required)
{
    if (tail > stride || rows - 1 > (SIZE_MAX - tail) / stride) return -EINVAL;
    *required = (rows - 1) * stride + tail;
    return 0;
}

int galaxy_hab_pack_bgra(uint8_t *destination, size_t destination_size,
                         size_t destination_stride, const uint8_t *source,
                         size_t source_size, size_t source_stride,
                         size_t pixel_stride, size_t width, size_t height)
{
    size_t source_tail, source_required, destination_required, x, y;
    size_t green, blue;
    if (!destination || !source || !width || !height || !pixel_stride ||
        !source_stride || !destination_stride || width > SIZE_MAX / 4) return -EINVAL;
    /* 样本通用分支：1字节灰度、2字节时B取首字节、>=3字节时读取RGB。 */
    green = pixel_stride > 1 ? 1 : 0;
    blue = pixel_stride > 2 ? 2 : 0;
    if (width - 1 > (SIZE_MAX - (pixel_stride > 2 ? 3u : pixel_stride)) / pixel_stride)
        return -EINVAL;
    source_tail = (width - 1) * pixel_stride + (pixel_stride > 2 ? 3u : pixel_stride);
    if (plane_size(height, source_stride, source_tail, &source_required) ||
        plane_size(height, destination_stride, width * 4, &destination_required)) return -EINVAL;
    if (source_size < source_required || destination_size < destination_required) return -EMSGSIZE;
    /* 此转换用于不同的ImageReader/shared-ring存储，禁止别名覆盖源像素。 */
    if ((uintptr_t)source > UINTPTR_MAX - source_required ||
        (uintptr_t)destination > UINTPTR_MAX - destination_required) return -EINVAL;
    if ((uintptr_t)source < (uintptr_t)destination + destination_required &&
        (uintptr_t)destination < (uintptr_t)source + source_required) return -EINVAL;
    for (y = 0; y < height; ++y) {
        const uint8_t *row = source + y * source_stride;
        uint8_t *output = destination + y * destination_stride;
        for (x = 0; x < width; ++x) {
            const uint8_t *pixel = row + x * pixel_stride;
            output[x * 4] = pixel[blue];
            output[x * 4 + 1] = pixel[green];
            output[x * 4 + 2] = pixel[0];
            output[x * 4 + 3] = 255;
        }
    }
    return 0;
}
