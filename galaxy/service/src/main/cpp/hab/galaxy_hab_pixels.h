/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_PIXELS_H
#define GALAXY_HAB_PIXELS_H
#include <stddef.h>
#include <stdint.h>

/* 对标nativeSendFrame的R/B交换和alpha=255；所有尺寸预检完成后才写目标。 */
int galaxy_hab_pack_bgra(uint8_t *destination, size_t destination_size,
                         size_t destination_stride, const uint8_t *source,
                         size_t source_size, size_t source_stride,
                         size_t pixel_stride, size_t width, size_t height);
#endif
