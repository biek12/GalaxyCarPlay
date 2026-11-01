/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_receiver.h"
#include "galaxy_hab_owned.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

static uint32_t get32(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t get64(const uint8_t *p) { return get32(p) | (uint64_t)get32(p + 4) << 32; }
static int32_t signed32(uint32_t word)
{
    int32_t value;
    memcpy(&value, &word, sizeof(value));
    return value;
}
static int equal_bytes(const uint8_t *a, const uint8_t *b, size_t size)
{
    uint8_t difference = 0;
    size_t i;
    for (i = 0; i < size; ++i) difference |= a[i] ^ b[i];
    return difference == 0;
}
static int overlap(const void *a, size_t na, const void *b, size_t nb)
{
    uintptr_t pa = (uintptr_t)a, pb = (uintptr_t)b;
    if (!na || !nb) return 0;
    if (pa > UINTPTR_MAX - na || pb > UINTPTR_MAX - nb) return 1;
    return pa < pb + nb && pb < pa + na;
}
static int private_output(const struct galaxy_hab_receiver *r, const void *p, size_t size)
{
    return !overlap(p, size, r, sizeof(*r)) &&
           !overlap(p, size, r->mapping, r->mapping_size) &&
           !overlap(p, size, r->scratch, r->scratch_size);
}

void galaxy_hab_receiver_init(struct galaxy_hab_receiver *r,
                              galaxy_hab_sign sign, void *context)
{
    memset(r, 0, sizeof(*r));
    r->sign = sign;
    r->sign_context = context;
}
void galaxy_hab_receiver_close(struct galaxy_hab_receiver *r)
{
    galaxy_hab_sign sign;
    void *context;
    if (!r) return;
    sign = r->sign;
    context = r->sign_context;
    free(r->scratch);
    memset(r, 0, sizeof(*r));
    r->sign = sign;
    r->sign_context = context;
}

int galaxy_hab_receiver_auth(struct galaxy_hab_receiver *r, const uint8_t *packet, size_t size)
{
    uint8_t input[GALAXY_HAB_AUTH_SIZE], canonical[GALAXY_HAB_AUTH_SIZE];
    struct galaxy_hab_layout layout;
    struct galaxy_hab_target targets[2];
    uint32_t count, i, export_id;
    uint64_t session, nonce, timestamp;
    int result;
    if (!r || !packet || size != sizeof(input)) return -EPROTO;
    if (r->authenticated || r->ready) return -EALREADY;
    if (!r->sign) return -EACCES;
    memcpy(input, packet, sizeof(input));
    if (get32(input) != GALAXY_HAB_AUTH_MAGIC || get32(input + 4) != 1 ||
        get32(input + 8) != 6 || get32(input + 12) != sizeof(input)) return -EPROTO;
    count = get32(input + 0x54);
    if (count < 1 || count > 2) return -EPROTO;
    for (i = 0; i < count; ++i) {
        uint32_t display = get32(input + 0x88 + i * 4);
        if (display > INT32_MAX && display != UINT32_MAX) return -EPROTO;
        targets[i].display_id = signed32(display);
        targets[i].width = get32(input + 0x58 + i * 4);
        targets[i].height = get32(input + 0x60 + i * 4);
        targets[i].z_order = signed32(get32(input + 0x90 + i * 4));
    }
    result = galaxy_hab_layout_build(&layout, targets, count, get32(input + 0x78));
    if (result) return result;
    export_id = get32(input + 0x28);
    session = get64(input + 0x10);
    nonce = get64(input + 0x18);
    timestamp = get64(input + 0x20);
    result = galaxy_hab_encode_auth(canonical, &layout, export_id, session, nonce,
                                    timestamp, r->sign, r->sign_context);
    if (result) return result;
    /* 同时验证HMAC及冗余字段/保留字节，不能只信某一组未一致的尺寸。 */
    if (!equal_bytes(input, canonical, sizeof(input))) return -EACCES;
    r->layout = layout;
    r->export_id = export_id;
    r->session = session;
    r->nonce = nonce;
    r->timestamp_us = timestamp;
    r->authenticated = 1;
    return 0;
}

int galaxy_hab_receiver_start(struct galaxy_hab_receiver *r,
                              const uint8_t *packet, size_t size,
                              const uint8_t *mapping, size_t mapping_size, uint8_t *ack)
{
    uint8_t input[GALAXY_HAB_PACKET_SIZE], expected[GALAXY_HAB_PACKET_SIZE];
    uint8_t accepted[GALAXY_HAB_PACKET_SIZE] = {0}, *scratch;
    size_t largest = 0;
    uint32_t i;
    int result;
    if (!r || !r->authenticated) return -ENOTCONN;
    if (r->ready) return -EALREADY;
    if (!packet || size != sizeof(input) || !mapping || !ack ||
        mapping_size != r->layout.total_bytes) return -EINVAL;
    if (overlap(mapping, mapping_size, r, sizeof(*r)) ||
        overlap(ack, sizeof(accepted), mapping, mapping_size) ||
        overlap(ack, sizeof(accepted), r, sizeof(*r))) return -EINVAL;
    memcpy(input, packet, sizeof(input));
    atomic_thread_fence(memory_order_acquire);
    result = galaxy_hab_encode_init(expected, &r->layout, r->export_id, r->session,
                                    galaxy_hab_sample_hash(mapping, r->layout.total_bytes));
    if (result) return result;
    if (!equal_bytes(input, expected, sizeof(input))) return -EPROTO;
    for (i = 0; i < r->layout.count; ++i)
        if (r->layout.streams[i].slot_bytes > largest) largest = r->layout.streams[i].slot_bytes;
    scratch = malloc(largest);
    if (!scratch) return -ENOMEM;
    /* 原receiver其他ACK字节未知；本实现采用满足已知客户端合同的最小成功ACK。 */
    accepted[0] = 0x31; accepted[1] = 0x52; accepted[2] = 0x46; accepted[3] = 0x48;
    accepted[4] = 1;
    accepted[8] = 4;
    r->mapping = mapping;
    r->mapping_size = mapping_size;
    r->scratch = scratch;
    r->scratch_size = largest;
    r->ready = 1;
    memcpy(ack, accepted, sizeof(accepted));
    return 0;
}

int galaxy_hab_receiver_enable_consumption(struct galaxy_hab_receiver *r,
    const uint8_t *packet, size_t size, uint8_t *reply)
{
    int result;
    if (!r || !r->ready) return -ENOTCONN;
    if (r->owned_consumption) return -EALREADY;
    if (!reply || !private_output(r, reply, GALAXY_HAB_PACKET_SIZE)) return -EINVAL;
    result = galaxy_hab_owned_check(packet, size, GALAXY_HAB_OWNED_HELLO,
        0, r->export_id, 0, r->session, r->sign, r->sign_context);
    if (result) return result;
    result = galaxy_hab_owned_encode(reply, GALAXY_HAB_OWNED_READY,
        0, r->export_id, 0, r->session, r->sign, r->sign_context);
    if (result) return result;
    /* 实车GLC2直读共享slot出现EBADMSG；恢复私有快照，校验与post只消费同一份字节。 */
    r->owned_consumption = 1;
    return 0;
}

int galaxy_hab_receiver_frame(struct galaxy_hab_receiver *r,
                              const uint8_t *packet, size_t size,
                              uint8_t *output, size_t capacity,
                              struct galaxy_hab_frame_info *info)
{
    return galaxy_hab_receiver_frame_strided(r, packet, size, output, capacity, 0, info);
}

int galaxy_hab_receiver_frame_strided(struct galaxy_hab_receiver *r,
                              const uint8_t *packet, size_t size,
                              uint8_t *output, size_t capacity, size_t output_stride,
                              struct galaxy_hab_frame_info *info)
{
    uint8_t input[GALAXY_HAB_PACKET_SIZE], canonical[GALAXY_HAB_PACKET_SIZE];
    struct galaxy_hab_frame_info candidate;
    const struct galaxy_hab_stream_layout *stream;
    uint32_t index, sequence, hash;
    size_t offset, bytes, required, row;
    int result;
    if (!r || !r->ready) return -ENOTCONN;
    if (!packet || size != sizeof(input) || !output || !info) return -EINVAL;
    memcpy(input, packet, sizeof(input));
    index = get32(input + 0x3c);
    sequence = get32(input + 0x0c);
    hash = get32(input + 0x2c);
    if (index >= r->layout.count) return -EPROTO;
    result = galaxy_hab_encode_frame(canonical, &r->layout, r->export_id, sequence, index, hash);
    if (result) return result;
    if (!equal_bytes(input, canonical, sizeof(input))) return -EPROTO;
    stream = &r->layout.streams[index];
    bytes = (size_t)stream->stride * stream->target.height;
    if (!output_stride) output_stride = stream->stride;
    if (output_stride < stream->stride ||
        stream->target.height - 1 > (SIZE_MAX - stream->stride) / output_stride) return -EINVAL;
    required = (stream->target.height - 1) * output_stride + stream->stride;
    if (capacity < required) return -EMSGSIZE;
    if (!private_output(r, output, required) || !private_output(r, info, sizeof(*info)) ||
        overlap(output, required, info, sizeof(*info))) return -EINVAL;
    offset = stream->base + (size_t)(sequence % stream->slots) * stream->slot_bytes;
    if (offset > r->mapping_size || stream->slot_bytes > r->mapping_size - offset ||
        stream->slot_bytes > r->scratch_size) return -EPROTO;
    atomic_thread_fence(memory_order_acquire);
    memcpy(r->scratch, r->mapping + offset, stream->slot_bytes);
    const uint8_t *source = r->scratch;
    if (r->owned_consumption || sequence % 30u == 0) {
        r->expected_hash = hash;
        r->observed_hash = galaxy_hab_sample_hash(source, stream->slot_bytes);
        if (r->observed_hash != hash) return -EBADMSG;
    }
    /* 未声称未采样帧不会撕裂，也不回写消费标志或制造frame ACK。 */
    memset(&candidate, 0, sizeof(candidate));
    candidate.sequence = sequence; candidate.stream = index;
    candidate.width = stream->target.width; candidate.height = stream->target.height;
    candidate.stride = stream->stride; candidate.display_id = stream->target.display_id;
    candidate.bytes = bytes;
    if (output_stride == stream->stride) memcpy(output, source, bytes);
    else for (row = 0; row < stream->target.height; ++row)
        memcpy(output + row * output_stride, source + row * stream->stride, stream->stride);
    *info = candidate;
    return 0;
}

int galaxy_hab_receiver_control(struct galaxy_hab_receiver *r,
                                const uint8_t *packet, size_t size,
                                struct galaxy_hab_control_event *event)
{
    uint8_t input[GALAXY_HAB_PACKET_SIZE], canonical[GALAXY_HAB_PACKET_SIZE];
    struct galaxy_hab_control_event candidate;
    uint32_t index;
    int result;
    if (!r || !r->ready) return -ENOTCONN;
    if (!packet || size != sizeof(input) || !event || !private_output(r, event, sizeof(*event)))
        return -EINVAL;
    memcpy(input, packet, sizeof(input));
    candidate.type = get32(input + 8);
    candidate.sequence = get32(input + 12);
    index = get32(input + 0x34);
    if (index > INT32_MAX && index != UINT32_MAX) return -EPROTO;
    candidate.stream = signed32(index);
    candidate.z_order = signed32(get32(input + 0x3c));
    result = galaxy_hab_encode_control(canonical, candidate.type, candidate.sequence,
        r->export_id, r->session, candidate.stream, candidate.z_order, r->sign, r->sign_context);
    if (result) return result;
    if (!equal_bytes(input, canonical, sizeof(input))) return -EACCES;
    if (candidate.type == 3) galaxy_hab_receiver_close(r);
    else if (candidate.stream >= 0 && (uint32_t)candidate.stream < r->layout.count)
        r->layout.streams[candidate.stream].target.z_order = candidate.z_order;
    *event = candidate;
    return 0;
}
