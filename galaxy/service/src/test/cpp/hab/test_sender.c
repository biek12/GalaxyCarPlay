/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_sender.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

struct fake {
    int controls, fail_at, opens, closes, unexports, receives, invalid_ack, zero_export, sign_fail;
    uint8_t messages[40][184];
    uint32_t sizes[40], sends;
    uint8_t *memory;
    uint32_t memory_size, expected_bytes, events[64];
    size_t event_count;
};
static uint32_t word(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static int fixture_sign(void *context, const uint8_t *data, size_t size, uint8_t digest[32])
{
    struct fake *f = context;
    (void)data;
    assert(size == 36 || size == 184);
    if (f->sign_fail) return -EACCES;
    memset(digest, 0x5a, 32); /* 假签名，仅供自有状态机测试，不能认证原receiver。 */
    return 0;
}
static int open_device(void *context)
{
    struct fake *f = context;
    ++f->opens;
    f->events[f->event_count++] = 1;
    return 12;
}
static int close_device(void *context, int fd)
{
    struct fake *f = context;
    assert(fd == 12);
    ++f->closes;
    f->events[f->event_count++] = 2;
    if (f->memory) { volatile uint8_t live = f->memory[0]; (void)live; }
    return 0;
}
static int control(void *context, int fd, uint32_t command, void *argument)
{
    struct fake *f = context;
    assert(fd == 12);
    ++f->controls;
    assert(f->event_count < 64);
    f->events[f->event_count++] = command;
    if (f->fail_at == f->controls) return -EIO;
    switch (command) {
        case GALAXY_HAB_VC_OPEN: {
            struct galaxy_hab_open_request *request = argument;
            assert(request->mmid == GALAXY_HAB_MMID && request->timeout_ms == 10000);
            request->vcid = 31;
            break;
        }
        case GALAXY_HAB_EXPORT: {
            struct galaxy_hab_export_request *request = argument;
            size_t i;
            assert(request->vcid == 31 && request->size == f->expected_bytes && !(request->address & 4095u));
            f->memory = (uint8_t *)(uintptr_t)request->address;
            f->memory_size = request->size;
            for (i = 0; i < request->size; ++i) assert(f->memory[i] == 0);
            request->export_id = f->zero_export ? 0 : 91;
            break;
        }
        case GALAXY_HAB_SEND: {
            struct galaxy_hab_data_request *request = argument;
            uint8_t *packet = (uint8_t *)(uintptr_t)request->address;
            assert(request->vcid == 31 && request->flags == 0 && f->sends < 40);
            assert(request->size == 64 || request->size == 184);
            memcpy(f->messages[f->sends], packet, request->size);
            f->sizes[f->sends++] = request->size;
            break;
        }
        case GALAXY_HAB_RECV: {
            struct galaxy_hab_data_request *request = argument;
            uint8_t *packet = (uint8_t *)(uintptr_t)request->address;
            assert(f->sends >= 2 && f->sizes[f->sends - 2] == 184 && f->sizes[f->sends - 1] == 64);
            assert(word(f->messages[f->sends - 2] + 8) == 6 && word(f->messages[f->sends - 1] + 8) == 1);
            assert(request->size == 64);
            memset(packet, 0, 64);
            packet[0] = 0x31; packet[1] = 0x52; packet[2] = 0x46; packet[3] = 0x48;
            packet[8] = 4; packet[12] = (uint8_t)f->invalid_ack;
            ++f->receives;
            break;
        }
        case GALAXY_HAB_UNEXPORT: {
            volatile uint8_t live = f->memory[0]; (void)live;
            assert(((struct galaxy_hab_unexport_request *)argument)->vcid == 31);
            ++f->unexports;
            break;
        }
        case GALAXY_HAB_VC_CLOSE: {
            if (f->memory) { volatile uint8_t live = f->memory[0]; (void)live; }
            assert(((struct galaxy_hab_close_request *)argument)->vcid == 31);
            break;
        }
        default: assert(0);
    }
    return 0;
}
static void init(struct galaxy_hab_sender *sender, struct fake *f)
{
    struct galaxy_hab_io io = {f, open_device, control, close_device};
    memset(f, 0, sizeof(*f));
    f->expected_bytes = 16384;
    galaxy_hab_sender_init(sender, io);
}
static int start(struct galaxy_hab_sender *sender, struct fake *f)
{
    struct galaxy_hab_target targets[2] = {{1, 2, 2, 1}, {3, 1, 1, 0}};
    return galaxy_hab_sender_open(sender, targets, 2, 2, 10000, 0x1122334455667788,
                                   7, 1234, fixture_sign, f);
}
int main(void)
{
    struct galaxy_hab_sender sender;
    struct fake f;
    uint8_t rgba[] = {1,2,3,0, 4,5,6,0, 7,8,9,0, 10,11,12,0};
    const uint8_t expected[] = {3,2,1,255, 6,5,4,255, 9,8,7,255, 12,11,10,255};
    int i;
    init(&sender, &f);
    assert(start(&sender, &f) == 0 && sender.ready);
    assert(sender.layout.streams[1].base == 8192 && f.sends == 2);
    assert(galaxy_hab_sender_frame(&sender, 0, rgba, sizeof(rgba), 8, 4) == 0);
    assert(sender.sequence == 1 && memcmp(sender.memory + 4096, expected, 16) == 0);
    assert(sender.memory[0] == 0 && sender.memory[4096 + 16] == 0);
    assert(word(f.messages[2] + 0x34) == 1 && word(f.messages[2] + 0x3c) == 0);
    assert(galaxy_hab_sender_frame(&sender, 1, rgba, sizeof(rgba), 4, 4) == 0);
    assert(sender.sequence == 2 && memcmp(sender.memory + 8192, expected, 4) == 0);
    assert(word(f.messages[3] + 0x3c) == 1 && word(f.messages[3] + 0x34) == 0);
    for (i = 3; i <= 30; ++i)
        assert(galaxy_hab_sender_frame(&sender, 0, rgba, sizeof(rgba), 8, 4) == 0);
    assert(sender.sequence == 30 && f.receives == 1); /* 不伪造逐帧ACK。 */
    assert(word(f.messages[31] + 0x2c) == galaxy_hab_sample_hash(sender.memory, 4096));
    assert(galaxy_hab_sender_z_order(&sender, 0, 5) == 0);
    assert(sender.layout.streams[0].target.z_order == 5 && word(f.messages[32] + 8) == 5);
    assert(galaxy_hab_sender_frame(&sender, 0, rgba, 1, 8, 4) == -EMSGSIZE);
    assert(sender.sequence == 31);
    assert(galaxy_hab_sender_close(&sender) == 0);
    assert(word(f.messages[33] + 8) == 3 && word(f.messages[33] + 12) == 31);
    assert(f.unexports == 1 && f.closes == 1 && !sender.ready && !sender.memory);
    assert(f.events[f.event_count - 4] == GALAXY_HAB_SEND);
    assert(f.events[f.event_count - 3] == GALAXY_HAB_UNEXPORT);
    assert(f.events[f.event_count - 2] == GALAXY_HAB_VC_CLOSE);
    assert(f.events[f.event_count - 1] == 2);
    assert(galaxy_hab_sender_close(&sender) == 0 && f.closes == 1);
    assert(galaxy_hab_sender_frame(&sender, 0, rgba, sizeof(rgba), 8, 4) == -ENOTCONN);
    assert(start(&sender, &f) == 0); /* close后显式重新open，不沿用旧sequence。 */
    assert(sender.sequence == 0);
    assert(galaxy_hab_sender_close(&sender) == 0);

    for (i = 1; i <= 5; ++i) {
        init(&sender, &f);
        f.fail_at = i;
        assert(start(&sender, &f) == -EIO);
        assert(!sender.ready && !sender.memory && sender.transport.fd == -1 && f.closes == 1);
        /* open失败不发送STOP，清理不会把部分会话伪装成已工作。 */
        for (uint32_t j = 0; j < f.sends; ++j) assert(word(f.messages[j] + 8) != 3);
    }
    init(&sender, &f);
    f.sign_fail = 1;
    assert(start(&sender, &f) == -EACCES && !sender.memory && f.sends == 0 && f.unexports == 1);
    init(&sender, &f);
    f.invalid_ack = 1;
    assert(start(&sender, &f) == -EPROTO && !sender.ready && f.unexports == 1 && !sender.memory);
    init(&sender, &f);
    f.zero_export = 1;
    assert(start(&sender, &f) == -EPROTO && f.sends == 0 && !sender.memory && f.closes == 1);
    /* 不同slotBytes + stream1第30序号，防止误用stream0大小或全共享区hash。 */
    {
        struct galaxy_hab_target large[2] = {{1, 2, 2, 1}, {3, 1025, 1, 0}};
        uint8_t source[4100];
        const uint8_t *slot;
        size_t n;
        init(&sender, &f);
        f.expected_bytes = 98304;
        memset(source, 17, sizeof(source));
        assert(galaxy_hab_sender_open(&sender, large, 2, 8, 10000,
            0x1122334455667788, 7, 1234, fixture_sign, &f) == 0);
        assert(sender.layout.streams[0].slot_bytes == 4096);
        assert(sender.layout.streams[1].slot_bytes == 8192 && sender.layout.streams[1].base == 32768);
        sender.sequence = 29;
        assert(galaxy_hab_sender_frame(&sender, 1, source, sizeof(source), 4100, 4) == 0);
        slot = sender.memory + 32768 + 6 * 8192;
        assert(word(f.messages[2] + 0x34) == 6 && word(f.messages[2] + 0x38) == 8192);
        assert(slot[0] == 17 && slot[3] == 255 && slot[4099] == 255);
        for (n = 4100; n < 8192; ++n) assert(slot[n] == 0);
        assert(word(f.messages[2] + 0x2c) == galaxy_hab_sample_hash(slot, 8192));
        assert(word(f.messages[2] + 0x2c) != galaxy_hab_sample_hash(slot, 4096));
        sender.sequence = UINT32_MAX;
        assert(galaxy_hab_sender_frame(&sender, 1, source, sizeof(source), 4100, 4) == 0);
        assert(sender.sequence == 0 && word(f.messages[3] + 12) == 0 && word(f.messages[3] + 0x34) == 0);
        assert(f.receives == 1 && galaxy_hab_sender_close(&sender) == 0);
    }
    /* ZORDER签名或发送失败不能提前改变本地缓存的已发送配置。 */
    for (i = 0; i < 2; ++i) {
        init(&sender, &f);
        assert(start(&sender, &f) == 0);
        if (i == 0) f.sign_fail = 1;
        else f.fail_at = f.controls + 1;
        assert(galaxy_hab_sender_z_order(&sender, 0, 9) == (i == 0 ? -EACCES : -EIO));
        assert(sender.layout.streams[0].target.z_order == 1);
        f.sign_fail = 0; f.fail_at = 0;
        assert(galaxy_hab_sender_z_order(&sender, 0, 9) == 0);
        assert(sender.layout.streams[0].target.z_order == 9);
        assert(galaxy_hab_sender_close(&sender) == 0);
    }
    /* STOP签名/发送以及VC_CLOSE失败仍按拥有的资源清理，不遗留帧区。 */
    for (i = 0; i < 3; ++i) {
        init(&sender, &f);
        assert(start(&sender, &f) == 0);
        if (i == 0) f.sign_fail = 1;
        else f.fail_at = f.controls + (i == 1 ? 1 : 3);
        assert(galaxy_hab_sender_close(&sender) == (i == 0 ? -EACCES : -EIO));
        assert(f.unexports == 1 && f.closes == 1 && !sender.memory && !sender.ready);
        assert(f.events[f.event_count - 2] == GALAXY_HAB_VC_CLOSE && f.events[f.event_count - 1] == 2);
        assert(galaxy_hab_sender_close(&sender) == 0);
    }
    puts("HAB source sender handshake/ring/conversion/cleanup contracts passed; fake driver and signer only");
    return 0;
}
