/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_protocol.h"
#include <errno.h>
#include <string.h>

static void put32(uint8_t *p, uint32_t value)
{
    unsigned int i;
    for (i = 0; i < 4; ++i) p[i] = (uint8_t)(value >> (8 * i));
}
static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 |
           (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static void put64(uint8_t *p, uint64_t value)
{
    put32(p, (uint32_t)value);
    put32(p + 4, (uint32_t)(value >> 32));
}
static uint32_t normalized(int32_t value) { return value < 0 ? UINT32_MAX : (uint32_t)value; }

int galaxy_hab_layout_build(struct galaxy_hab_layout *layout,
                            const struct galaxy_hab_target *targets,
                            uint32_t count, uint32_t slots)
{
    struct galaxy_hab_layout candidate;
    uint64_t total = 0;
    uint32_t i;
    if (!layout || !targets || count < 1 || count > 2 || slots < 2 || slots > 8)
        return -EINVAL;
    memset(&candidate, 0, sizeof(candidate));
    candidate.count = count;
    for (i = 0; i < count; ++i) {
        uint64_t stride = (uint64_t)targets[i].width * 4;
        uint64_t bytes, aligned;
        if (!targets[i].width || !targets[i].height || stride > UINT32_MAX) return -EINVAL;
        bytes = stride * targets[i].height;
        if (bytes > UINT32_MAX - 4095u) return -EOVERFLOW;
        aligned = (bytes + 4095u) & ~UINT64_C(4095);
        if (aligned * slots > UINT32_MAX - total) return -EOVERFLOW;
        candidate.streams[i].target = targets[i];
        candidate.streams[i].stride = (uint32_t)stride;
        candidate.streams[i].slots = slots;
        candidate.streams[i].slot_bytes = (uint32_t)aligned;
        candidate.streams[i].base = (uint32_t)total;
        total += aligned * slots;
    }
    candidate.total_bytes = (uint32_t)total;
    *layout = candidate;
    return 0;
}

static int valid_layout(const struct galaxy_hab_layout *layout)
{
    struct galaxy_hab_layout expected;
    struct galaxy_hab_target targets[2];
    uint32_t i;
    if (!layout || layout->count < 1 || layout->count > 2) return 0;
    for (i = 0; i < layout->count; ++i) targets[i] = layout->streams[i].target;
    if (galaxy_hab_layout_build(&expected, targets, layout->count, layout->streams[0].slots)) return 0;
    if (layout->total_bytes != expected.total_bytes) return 0;
    for (i = 0; i < layout->count; ++i) {
        const struct galaxy_hab_stream_layout *a = &layout->streams[i], *b = &expected.streams[i];
        if (a->stride != b->stride || a->slots != b->slots ||
            a->slot_bytes != b->slot_bytes || a->base != b->base) return 0;
    }
    return 1;
}

uint32_t galaxy_hab_sample_hash(const uint8_t *memory, uint32_t size)
{
    uint32_t hash = UINT32_C(0x811c9dc5);
    uint32_t step = size > 65536u ? 97u : 1u;
    uint64_t offset;
    for (offset = 0; offset < size; offset += step)
        hash = (hash ^ memory[offset]) * UINT32_C(0x01000193);
    return (hash ^ size) * UINT32_C(0x01000193);
}

int galaxy_hab_encode_auth(uint8_t *packet, const struct galaxy_hab_layout *layout,
                           uint32_t export_id, uint64_t session, uint64_t nonce,
                           uint64_t timestamp_us, galaxy_hab_sign sign, void *sign_context)
{
    uint8_t candidate[GALAXY_HAB_AUTH_SIZE] = {0}, digest[32];
    const struct galaxy_hab_stream_layout *first;
    uint32_t i;
    int result;
    if (!packet || !valid_layout(layout) || !export_id || !session || !nonce) return -EINVAL;
    if (!sign) return -EACCES;
    first = &layout->streams[0];
    put32(candidate, GALAXY_HAB_AUTH_MAGIC);
    put32(candidate + 4, 1);
    put32(candidate + 8, 6);
    put32(candidate + 12, GALAXY_HAB_AUTH_SIZE);
    put64(candidate + 0x10, session);
    put64(candidate + 0x18, nonce);
    put64(candidate + 0x20, timestamp_us);
    put32(candidate + 0x28, export_id);
    put32(candidate + 0x2c, first->target.width);
    put32(candidate + 0x30, first->target.height);
    put32(candidate + 0x34, first->stride);
    put32(candidate + 0x38, layout->total_bytes);
    put32(candidate + 0x3c, 1);
    put32(candidate + 0x44, first->slots);
    put32(candidate + 0x48, first->slot_bytes);
    put32(candidate + 0x4c, normalized(first->target.display_id));
    put32(candidate + 0x54, layout->count);
    for (i = 0; i < layout->count; ++i) {
        const struct galaxy_hab_stream_layout *stream = &layout->streams[i];
        put32(candidate + 0x58 + i * 4, stream->target.width);
        put32(candidate + 0x60 + i * 4, stream->target.height);
        put32(candidate + 0x68 + i * 4, stream->stride);
        put32(candidate + 0x70 + i * 4, stream->slot_bytes);
        put32(candidate + 0x78 + i * 4, stream->slots);
        put32(candidate + 0x80 + i * 4, stream->base);
        put32(candidate + 0x88 + i * 4, normalized(stream->target.display_id));
        put32(candidate + 0x90 + i * 4, (uint32_t)stream->target.z_order);
    }
    /* 签名范围必须184B，包含全零尾32B，而不是只签前152B。 */
    result = sign(sign_context, candidate, sizeof(candidate), digest);
    if (result != 0) return result;
    memcpy(candidate + 0x98, digest, sizeof(digest));
    memcpy(packet, candidate, sizeof(candidate));
    return 0;
}

static void header(uint8_t *packet, uint32_t type, uint32_t sequence, uint32_t export_id)
{
    memset(packet, 0, GALAXY_HAB_PACKET_SIZE);
    put32(packet, GALAXY_HAB_MAGIC);
    put32(packet + 4, 1);
    put32(packet + 8, type);
    put32(packet + 12, sequence);
    put32(packet + 16, export_id);
}
static void geometry(uint8_t *packet, const struct galaxy_hab_layout *layout, uint32_t index)
{
    const struct galaxy_hab_stream_layout *stream = &layout->streams[index];
    put32(packet + 0x14, stream->target.width);
    put32(packet + 0x18, stream->target.height);
    put32(packet + 0x1c, stream->stride);
    put32(packet + 0x20, layout->total_bytes);
    put32(packet + 0x24, 1);
    put32(packet + 0x30, stream->slots);
    put32(packet + 0x38, stream->slot_bytes);
}
int galaxy_hab_encode_init(uint8_t *packet, const struct galaxy_hab_layout *layout,
                           uint32_t export_id, uint64_t session, uint32_t memory_hash)
{
    if (!packet || !valid_layout(layout) || !export_id || !session) return -EINVAL;
    header(packet, 1, 0, export_id);
    geometry(packet, layout, 0);
    put32(packet + 0x2c, memory_hash);
    put32(packet + 0x3c, (uint32_t)session);
    return 0;
}
int galaxy_hab_encode_frame(uint8_t *packet, const struct galaxy_hab_layout *layout,
                            uint32_t export_id, uint32_t sequence, uint32_t stream,
                            uint32_t slot_hash)
{
    if (!packet || !valid_layout(layout) || !export_id || stream >= layout->count) return -EINVAL;
    header(packet, 2, sequence, export_id);
    geometry(packet, layout, stream);
    put32(packet + 0x2c, sequence % 30u == 0 ? slot_hash : 0);
    put32(packet + 0x34, sequence % layout->streams[stream].slots);
    put32(packet + 0x3c, stream);
    return 0;
}
int galaxy_hab_encode_control(uint8_t *packet, uint32_t type, uint32_t sequence,
                              uint32_t export_id, uint64_t session, int32_t stream,
                              int32_t z_order, galaxy_hab_sign sign, void *sign_context)
{
    uint8_t candidate[GALAXY_HAB_PACKET_SIZE], message[36], digest[32];
    int result;
    if (!packet || (type != 3 && type != 5) || !export_id || !session) return -EINVAL;
    if (!sign) return -EACCES;
    header(candidate, type, sequence, export_id);
    put32(candidate + 0x34, type == 3 ? 0 : normalized(stream));
    put32(candidate + 0x3c, type == 3 ? (uint32_t)session : (uint32_t)z_order);
    memcpy(message, candidate, 20);
    memcpy(message + 20, candidate + 0x34, 4);
    memcpy(message + 24, candidate + 0x3c, 4);
    put64(message + 28, session);
    result = sign(sign_context, message, sizeof(message), digest);
    if (result != 0) return result;
    memcpy(candidate + 0x2c, digest, 4);
    memcpy(packet, candidate, sizeof(candidate));
    return 0;
}
int galaxy_hab_check_ack(const uint8_t *packet, size_t size)
{
    if (!packet || size != GALAXY_HAB_PACKET_SIZE) return -EPROTO;
    if (get32(packet) != GALAXY_HAB_MAGIC || get32(packet + 8) != 4 ||
        get32(packet + 12) != 0) return -EPROTO;
    return 0;
}
