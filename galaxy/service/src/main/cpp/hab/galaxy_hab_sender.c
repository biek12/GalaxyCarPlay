/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200112L
#include "galaxy_hab_sender.h"
#include "galaxy_hab_pixels.h"
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

void galaxy_hab_sender_init(struct galaxy_hab_sender *s, struct galaxy_hab_io io)
{
    memset(s, 0, sizeof(*s));
    galaxy_hab_transport_init(&s->transport, io);
}

int galaxy_hab_sender_close(struct galaxy_hab_sender *s)
{
    uint8_t packet[GALAXY_HAB_PACKET_SIZE];
    int first_error = 0, result;
    if (!s) return -EINVAL;
    if (s->ready) {
        /* STOP不等ACK；认证失败仍清理自己拥有的export/VC/fd/memory。 */
        s->ready = 0;
        first_error = galaxy_hab_encode_control(packet, 3, s->sequence,
            s->export_id, s->session, 0, 0, s->sign, s->sign_context);
        if (!first_error) first_error = galaxy_hab_transport_send(&s->transport, packet, sizeof(packet));
    }
    result = galaxy_hab_transport_close(&s->transport);
    if (!first_error) first_error = result;
    free(s->memory);
    s->memory = NULL;
    s->export_id = 0;
    s->sequence = 0;
    s->session = 0;
    s->sign = NULL;
    s->sign_context = NULL;
    return first_error;
}

int galaxy_hab_sender_open(struct galaxy_hab_sender *s,
                           const struct galaxy_hab_target *targets, uint32_t count,
                           uint32_t slots, uint32_t timeout_ms, uint64_t session,
                           uint64_t nonce, uint64_t timestamp_us,
                           galaxy_hab_sign sign, void *sign_context)
{
    uint8_t auth[GALAXY_HAB_AUTH_SIZE], packet[GALAXY_HAB_PACKET_SIZE];
    size_t received = 0;
    void *memory = NULL;
    int result;
    if (!s || !session || !nonce || !timeout_ms) return -EINVAL;
    if (s->ready || s->memory || s->transport.fd >= 0) return -EALREADY;
    if (!sign) return -EACCES;
    result = galaxy_hab_layout_build(&s->layout, targets, count, slots);
    if (result) return result;
    result = galaxy_hab_transport_open(&s->transport, GALAXY_HAB_MMID, timeout_ms);
    if (result) return result;
    result = posix_memalign(&memory, 4096, s->layout.total_bytes);
    if (result) { result = -result; goto failed; }
    s->memory = memory;
    memset(s->memory, 0, s->layout.total_bytes);
    result = galaxy_hab_transport_export(&s->transport, s->memory,
                                         s->layout.total_bytes, &s->export_id);
    if (result) goto failed;
    if (!s->export_id) { result = -EPROTO; goto failed; }
    s->session = session;
    s->sequence = 0;
    s->sign = sign;
    s->sign_context = sign_context;
    result = galaxy_hab_encode_auth(auth, &s->layout, s->export_id, session, nonce,
                                    timestamp_us, sign, sign_context);
    if (result) goto failed;
    result = galaxy_hab_transport_send(&s->transport, auth, sizeof(auth));
    if (result) goto failed;
    result = galaxy_hab_encode_init(packet, &s->layout, s->export_id, session,
        galaxy_hab_sample_hash(s->memory, s->layout.total_bytes));
    if (result) goto failed;
    result = galaxy_hab_transport_send(&s->transport, packet, sizeof(packet));
    if (result) goto failed;
    result = galaxy_hab_transport_receive(&s->transport, packet, sizeof(packet), 0, &received);
    if (result) goto failed;
    result = galaxy_hab_check_ack(packet, received);
    if (result) goto failed;
    s->ready = 1;
    return 0;
failed:
    /* 未完成open不发STOP。保留主错误；清理函数另外努力释放所有资源。 */
    (void)galaxy_hab_sender_close(s);
    return result;
}

int galaxy_hab_sender_frame(struct galaxy_hab_sender *s, uint32_t index,
                            const uint8_t *source, size_t capacity,
                            size_t row_stride, size_t pixel_stride)
{
    uint8_t packet[GALAXY_HAB_PACKET_SIZE], *slot;
    const struct galaxy_hab_stream_layout *stream;
    uint32_t hash = 0;
    int result;
    if (!s || !s->ready) return -ENOTCONN;
    if (index >= s->layout.count || !source || !capacity) return -EINVAL;
    stream = &s->layout.streams[index];
    ++s->sequence; /* 样本为全局u32序号，首帧slot1，转换错误也消耗已分配的序号。 */
    slot = s->memory + stream->base + (size_t)(s->sequence % stream->slots) * stream->slot_bytes;
    result = galaxy_hab_pack_bgra(slot, stream->slot_bytes, stream->stride,
                                  source, capacity, row_stride, pixel_stride,
                                  stream->target.width, stream->target.height);
    if (result) return result;
    atomic_thread_fence(memory_order_seq_cst); /* AArch64编译为DMB ISH，对标样本提交前屏障。 */
    if (s->sequence % 30u == 0) hash = galaxy_hab_sample_hash(slot, stream->slot_bytes);
    result = galaxy_hab_encode_frame(packet, &s->layout, s->export_id, s->sequence, index, hash);
    if (result) return result;
    return galaxy_hab_transport_send(&s->transport, packet, sizeof(packet));
}

int galaxy_hab_sender_z_order(struct galaxy_hab_sender *s, int32_t index, int32_t z_order)
{
    uint8_t packet[GALAXY_HAB_PACKET_SIZE];
    int result;
    if (!s || !s->ready) return -ENOTCONN;
    result = galaxy_hab_encode_control(packet, 5, s->sequence, s->export_id,
                                       s->session, index, z_order, s->sign, s->sign_context);
    if (result) return result;
    result = galaxy_hab_transport_send(&s->transport, packet, sizeof(packet));
    /* 发送失败不把本地布局提前变成对端已应用的新层级。 */
    if (!result && index >= 0 && (uint32_t)index < s->layout.count)
        s->layout.streams[index].target.z_order = z_order;
    return result;
}
