/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_receiver.h"
#include "galaxy_hab_sender.h"
#include "galaxy_hab_hmac.h"
#include "galaxy_hab_owned.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

struct bridge {
    struct galaxy_hab_receiver receiver;
    struct galaxy_hab_hmac_key key;
    uint8_t *memory;
    size_t memory_size;
    uint8_t ack[64], last_packet[64], output[5000];
    struct galaxy_hab_frame_info frame;
    struct galaxy_hab_control_event event;
    int opens, closes, exports, unexports, receives, frames, ack_ready;
    int corrupt_posted;
};
static uint32_t word(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static void put(uint8_t *p, uint32_t value)
{
    for (size_t i = 0; i < 4; ++i) p[i] = (uint8_t)(value >> (8*i));
}
static void resign(uint8_t auth[184], struct galaxy_hab_hmac_key *key)
{
    uint8_t digest[32];
    memset(auth + 152, 0, 32);
    assert(galaxy_hab_hmac_sign(key, auth, 184, digest) == 0);
    memcpy(auth + 152, digest, 32);
}
static int open_device(void *context)
{
    ++((struct bridge *)context)->opens;
    return 7;
}
static int close_device(void *context, int fd)
{
    struct bridge *b = context;
    assert(fd == 7);
    ++b->closes;
    assert(!b->receiver.ready && !b->receiver.scratch);
    return 0;
}
static int control(void *context, int fd, uint32_t operation, void *argument)
{
    struct bridge *b = context;
    assert(fd == 7);
    switch (operation) {
        case GALAXY_HAB_VC_OPEN:
            ((struct galaxy_hab_open_request *)argument)->vcid = 42;
            return 0;
        case GALAXY_HAB_EXPORT: {
            struct galaxy_hab_export_request *q = argument;
            b->memory = (uint8_t *)(uintptr_t)q->address;
            b->memory_size = q->size;
            q->export_id = 91;
            ++b->exports;
            return 0;
        }
        case GALAXY_HAB_SEND: {
            struct galaxy_hab_data_request *q = argument;
            const uint8_t *packet = (const uint8_t *)(uintptr_t)q->address;
            if (q->size == 184) return galaxy_hab_receiver_auth(&b->receiver, packet, q->size);
            assert(q->size == 64);
            memcpy(b->last_packet, packet, 64);
            if (word(packet) == GALAXY_HAB_OWNED_MAGIC) {
                int result = galaxy_hab_receiver_enable_consumption(&b->receiver, packet, 64, b->ack);
                b->ack_ready = result == 0;
                return result;
            }
            switch (word(packet + 8)) {
                case 1: {
                    int result = galaxy_hab_receiver_start(&b->receiver, packet, 64,
                        b->memory, b->memory_size, b->ack);
                    b->ack_ready = result == 0;
                    return result;
                }
                case 2: {
                    int result = galaxy_hab_receiver_frame(&b->receiver, packet, 64,
                        b->output, sizeof(b->output), &b->frame);
                    if (!result) {
                        ++b->frames;
                        if (b->receiver.owned_consumption) {
                            result = galaxy_hab_owned_encode(b->ack, GALAXY_HAB_OWNED_POSTED,
                                b->frame.sequence, b->receiver.export_id, b->frame.stream,
                                b->receiver.session, galaxy_hab_hmac_sign, &b->key);
                            b->ack_ready = result == 0;
                        }
                    }
                    return result;
                }
                case 3: case 5:
                    return galaxy_hab_receiver_control(&b->receiver, packet, 64, &b->event);
                default: return -EPROTO;
            }
        }
        case GALAXY_HAB_RECV: {
            struct galaxy_hab_data_request *q = argument;
            assert(b->ack_ready && q->size == 64);
            if (b->frames && b->corrupt_posted == 3) return -EIO;
            if (b->frames && b->corrupt_posted == 2) assert(galaxy_hab_owned_encode(b->ack, GALAXY_HAB_OWNED_POSTED,
                b->frame.sequence, b->receiver.export_id, b->frame.stream, b->receiver.session + 1, galaxy_hab_hmac_sign, &b->key) == 0);
            if (b->frames && b->corrupt_posted == 1) b->ack[32] ^= 1;
            memcpy((void *)(uintptr_t)q->address, b->ack, 64);
            ++b->receives;
            b->ack_ready = 0;
            return 0;
        }
        case GALAXY_HAB_UNEXPORT:
            ++b->unexports;
            return 0;
        case GALAXY_HAB_VC_CLOSE:
            galaxy_hab_receiver_close(&b->receiver);
            return 0;
        default: return -EINVAL;
    }
}
static void init_bridge(struct bridge *b, struct galaxy_hab_sender *sender)
{
    struct galaxy_hab_io io = {b, open_device, control, close_device};
    memset(b, 0, sizeof(*b));
    for (size_t i = 0; i < 32; ++i) b->key.bytes[i] = (uint8_t)i; /* 非真实密钥。 */
    galaxy_hab_receiver_init(&b->receiver, galaxy_hab_hmac_sign, &b->key);
    galaxy_hab_sender_init(sender, io);
}
int main(void)
{
    struct galaxy_hab_target targets[2] = {{1,3,2,1}, {3,1025,1,0}};
    struct galaxy_hab_sender sender;
    struct bridge b;
    uint8_t rgba[4100], old_output[5000], packet[184], modified[184], ack[64];
    struct galaxy_hab_frame_info old_info;
    size_t i;
    init_bridge(&b, &sender);
    assert(galaxy_hab_sender_open(&sender, targets, 2, 8, 10000,
        UINT64_C(0x1122334455667788), 7, 1234, galaxy_hab_hmac_sign, &b.key) == 0);
    assert(sender.ready && b.receiver.ready && b.receives == 1);
    assert(b.receiver.layout.streams[1].base == 32768 && b.receiver.scratch_size == 8192);
    for (i = 0; i < sizeof(rgba); ++i) rgba[i] = (uint8_t)(i % 251);
    assert(galaxy_hab_sender_frame(&sender, 0, rgba, sizeof(rgba), 12, 4) == 0);
    assert(b.frame.stream == 0 && b.frame.display_id == 1 && b.frame.sequence == 1 && b.frame.bytes == 24);
    for (i = 0; i < 6; ++i) {
        assert(b.output[i*4] == rgba[i*4+2] && b.output[i*4+1] == rgba[i*4+1]);
        assert(b.output[i*4+2] == rgba[i*4] && b.output[i*4+3] == 255);
    }
    sender.sequence = 29;
    assert(galaxy_hab_sender_frame(&sender, 1, rgba, sizeof(rgba), 4100, 4) == 0);
    assert(b.frame.stream == 1 && b.frame.sequence == 30 && b.frame.width == 1025 && b.frame.bytes == 4100);
    assert(b.receives == 1 && b.frames == 2); /* 不存在帧级ACK。 */
    old_info = b.frame;
    memcpy(old_output, b.output, sizeof(old_output));
    memcpy(packet, b.last_packet, 64);
    /* 篡改报文和共享slot都不能在失败时污染上次已验证的输出。 */
    for (i = 0; i < 6; ++i) {
        const size_t offsets[] = {0, 0x10, 0x1c, 0x30, 0x34, 0x3c};
        memcpy(modified, packet, 64);
        modified[offsets[i]] ^= 0x80;
        assert(galaxy_hab_receiver_frame(&b.receiver, modified, 64, b.output, sizeof(b.output), &b.frame) != 0);
        assert(memcmp(old_output, b.output, sizeof(old_output)) == 0 && memcmp(&old_info, &b.frame, sizeof(old_info)) == 0);
    }
    {
        size_t offset = 32768 + 6 * 8192;
        b.memory[offset + 5000] ^= 1; /* slot尾部也在30号hash范围内。 */
        assert(galaxy_hab_receiver_frame(&b.receiver, packet, 64, b.output, sizeof(b.output), &b.frame) == -EBADMSG);
        assert(memcmp(old_output, b.output, sizeof(old_output)) == 0);
        b.memory[offset + 5000] ^= 1;
    }
    assert(galaxy_hab_receiver_frame(&b.receiver, packet, 64, b.output, 4099, &b.frame) == -EMSGSIZE);
    assert(galaxy_hab_receiver_frame(&b.receiver, packet, 63, b.output, sizeof(b.output), &b.frame) != 0);
    assert(galaxy_hab_receiver_frame(&b.receiver, packet, 64, b.memory, b.memory_size, &b.frame) == -EINVAL);
    assert(galaxy_hab_receiver_frame(&b.receiver, packet, 64, b.output, sizeof(b.output), (void *)b.output) == -EINVAL);
    /* 平台buffer带padding：跨行写入、容量尾界和hash失败都须保护像素/padding。 */
    {
        uint8_t padded[48], before[48], first_packet[64];
        struct galaxy_hab_frame_info info, previous;
        const struct galaxy_hab_stream_layout *s = &b.receiver.layout.streams[0];
        size_t slot = s->base + (size_t)(30 % s->slots) * s->slot_bytes;
        assert(galaxy_hab_encode_frame(first_packet, &b.receiver.layout, 91, 30, 0,
            galaxy_hab_sample_hash(b.memory + slot, s->slot_bytes)) == 0);
        memset(padded, 0xa5, sizeof(padded));
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            padded, 32, 20, &info) == 0);
        assert(memcmp(padded, b.memory + slot, 12) == 0);
        assert(memcmp(padded + 20, b.memory + slot + 12, 12) == 0);
        for (i = 12; i < 20; ++i) assert(padded[i] == 0xa5);
        for (i = 32; i < sizeof(padded); ++i) assert(padded[i] == 0xa5);
        memcpy(before, padded, sizeof(before)); previous = info;
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            padded, 31, 20, &info) == -EMSGSIZE);
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            padded, sizeof(padded), 11, &info) == -EINVAL);
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            padded, sizeof(padded), SIZE_MAX, &info) == -EINVAL);
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            b.memory, b.memory_size, 20, &info) == -EINVAL);
        b.memory[slot] ^= 1;
        assert(galaxy_hab_receiver_frame_strided(&b.receiver, first_packet, 64,
            padded, sizeof(padded), 20, &info) == -EBADMSG);
        b.memory[slot] ^= 1;
        assert(memcmp(before, padded, sizeof(before)) == 0 && memcmp(&previous, &info, sizeof(info)) == 0);
    }
    sender.sequence = UINT32_MAX;
    assert(galaxy_hab_sender_frame(&sender, 1, rgba, sizeof(rgba), 4100, 4) == 0 && b.frame.sequence == 0);

    assert(galaxy_hab_sender_z_order(&sender, 1, -7) == 0);
    assert(b.event.type == 5 && b.event.stream == 1 && b.event.z_order == -7);
    assert(b.receiver.layout.streams[1].target.z_order == -7);
    memcpy(modified, b.last_packet, 64);
    modified[0x3c] ^= 1;
    assert(galaxy_hab_receiver_control(&b.receiver, modified, 64, &b.event) == -EACCES);
    assert(b.receiver.layout.streams[1].target.z_order == -7);
    /* 已认证的负index只作为事件传出，不猜“所有屏幕”等窗口语义。 */
    assert(galaxy_hab_sender_z_order(&sender, -2, 5) == 0 && b.event.stream == -1);
    assert(b.receiver.layout.streams[1].target.z_order == -7);
    assert(galaxy_hab_sender_close(&sender) == 0);
    assert(b.event.type == 3 && !b.receiver.ready && !b.receiver.authenticated && !b.receiver.scratch);
    assert(b.exports == 1 && b.unexports == 1 && b.closes == 1);
    galaxy_hab_receiver_close(&b.receiver);

    /* 重新使用公开合成key构造认证fixture；检查单流、错误key和冗余布局。 */
    {
        struct galaxy_hab_layout layout;
        struct galaxy_hab_hmac_key wrong_key = {{0}};
        struct galaxy_hab_receiver receiver;
        uint8_t mapping[8192] = {0};
        assert(galaxy_hab_layout_build(&layout, targets, 1, 2) == 0);
        assert(galaxy_hab_encode_auth(packet, &layout, 91, 99, 7, 1234, galaxy_hab_hmac_sign, &b.key) == 0);
        galaxy_hab_receiver_init(&receiver, NULL, NULL);
        assert(galaxy_hab_receiver_auth(&receiver, packet, 184) == -EACCES);
        galaxy_hab_receiver_init(&receiver, galaxy_hab_hmac_sign, &wrong_key);
        assert(galaxy_hab_receiver_auth(&receiver, packet, 184) == -EACCES && !receiver.authenticated);
        galaxy_hab_receiver_init(&receiver, galaxy_hab_hmac_sign, &b.key);
        for (i = 0; i < 184; ++i) {
            memcpy(modified, packet, 184);
            modified[i] ^= 1;
            assert(galaxy_hab_receiver_auth(&receiver, modified, 184) != 0);
            assert(!receiver.authenticated && !receiver.ready && !receiver.scratch);
        }
        memcpy(modified, packet, 184);
        put(modified + 0x38, 1); /* 重签的不一致total同样拒绝，不仅检HMAC。 */
        resign(modified, &b.key);
        assert(galaxy_hab_receiver_auth(&receiver, modified, 184) == -EACCES);
        assert(galaxy_hab_receiver_auth(&receiver, packet, 183) != 0);
        assert(galaxy_hab_receiver_auth(&receiver, packet, 184) == 0);
        assert(galaxy_hab_receiver_auth(&receiver, packet, 184) == -EALREADY);
        assert(galaxy_hab_encode_init(modified, &layout, 91, 99,
            galaxy_hab_sample_hash(mapping, sizeof(mapping))) == 0);
        memset(ack, 0xa5, sizeof(ack));
        assert(galaxy_hab_receiver_start(&receiver, modified, 64, mapping, 8191, ack) == -EINVAL);
        assert(ack[0] == 0xa5 && !receiver.scratch);
        modified[0x14] ^= 1;
        assert(galaxy_hab_receiver_start(&receiver, modified, 64, mapping, sizeof(mapping), ack) == -EPROTO);
        assert(ack[0] == 0xa5 && !receiver.ready);
        modified[0x14] ^= 1;
        assert(galaxy_hab_receiver_start(&receiver, modified, 64, mapping, sizeof(mapping), mapping) == -EINVAL);
        assert(galaxy_hab_receiver_start(&receiver, modified, 64, mapping, sizeof(mapping), ack) == 0);
        assert(galaxy_hab_check_ack(ack, sizeof(ack)) == 0);
        assert(galaxy_hab_encode_control(modified, 3, 1, 91, 99, 0, 0, galaxy_hab_hmac_sign, &b.key) == 0);
        modified[0x2c] ^= 1;
        assert(galaxy_hab_receiver_control(&receiver, modified, 64, &b.event) == -EACCES && receiver.ready);
        modified[0x2c] ^= 1;
        assert(galaxy_hab_receiver_control(&receiver, modified, 64, &b.event) == 0 && !receiver.ready);
        assert(galaxy_hab_receiver_frame(&receiver, packet, 64, b.output, sizeof(b.output), &b.frame) == -ENOTCONN);
        galaxy_hab_receiver_close(&receiver);
    }
    /* 自有HELLO协商与消费ACK：旧v1对照路径仍保留，不能把它称为原版能力。 */
    for (int failure = 0; failure <= 3; ++failure) {
        init_bridge(&b, &sender);
        assert(galaxy_hab_sender_open(&sender, targets, 2, 8, 10000,
            UINT64_C(0x1122334455667788), 7, 1234, galaxy_hab_hmac_sign, &b.key) == 0);
        assert(galaxy_hab_owned_encode(packet, GALAXY_HAB_OWNED_HELLO, 0, 91, 0,
            sender.session, galaxy_hab_hmac_sign, &b.key) == 0);
        for (i = 0; i < 64; ++i) {
            memcpy(modified, packet, 64); modified[i] ^= 1;
            assert(galaxy_hab_receiver_enable_consumption(&b.receiver, modified, 64, ack) == -EPROTO);
            assert(!b.receiver.owned_consumption && b.receiver.scratch);
        }
        assert(galaxy_hab_sender_enable_consumption(&sender) == 0);
        assert(sender.owned_consumption && b.receiver.owned_consumption && b.receiver.scratch && b.receiver.scratch_size);
        assert(galaxy_hab_sender_enable_consumption(&sender) == -EALREADY);
        b.corrupt_posted = failure;
        sender.sequence = 29;
        int result = galaxy_hab_sender_frame(&sender, 1, rgba, sizeof(rgba), 4100, 4);
        if (!failure) {
            assert(result == 0 && b.frame.sequence == 30 && b.receives == 3);
            sender.sequence = UINT32_MAX;
            assert(galaxy_hab_sender_frame(&sender, 0, rgba, sizeof(rgba), 12, 4) == 0);
            assert(b.frame.sequence == 0 && b.receives == 4);
        } else {
            assert(result == (failure == 3 ? -EIO : -EPROTO));
            assert(sender.owned_failed && b.frames == 1);
            assert(galaxy_hab_sender_frame(&sender, 1, rgba, sizeof(rgba), 4100, 4) == -EPIPE && b.frames == 1);
        }
        assert(galaxy_hab_sender_close(&sender) == 0);
        assert(!sender.memory && !b.receiver.ready && !b.receiver.scratch);
    }
    puts("HAB sender/receiver/HMAC, owned consumption negotiation, ACK/session rejection and wrap contracts passed; no QNX or real driver");
    return 0;
}
