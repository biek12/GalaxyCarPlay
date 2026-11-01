/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_protocol.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

/* 仅校验编码器传给签名者的字节，不是有效AutoDisplay认证，也不包含真实密钥。 */
struct signer { uint8_t input[184]; size_t size; int fail; };
static int fixture_sign(void *context, const uint8_t *message, size_t size, uint8_t digest[32])
{
    struct signer *s = context;
    size_t i;
    assert(size == 36 || size == 184);
    memcpy(s->input, message, size);
    s->size = size;
    if (size == 184) for (i = 152; i < 184; ++i) assert(message[i] == 0);
    if (s->fail) return -EACCES;
    for (i = 0; i < 32; ++i) digest[i] = (uint8_t)(0x80 + i);
    return 0;
}
static uint32_t word(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
int main(void)
{
    struct galaxy_hab_target targets[2] = {{1, 3, 2, 1}, {-1, 5, 3, -7}};
    struct galaxy_hab_layout layout, unchanged;
    struct signer signer = {{0}, 0, 0};
    uint8_t packet[184], before[184], memory[70000];
    const uint8_t expected_init[64] = {
        0x31,0x52,0x46,0x48, 1,0,0,0, 1,0,0,0, 0,0,0,0,
        9,0,0,0, 3,0,0,0, 2,0,0,0, 12,0,0,0,
        0,0x20,0,0, 1,0,0,0, 0,0,0,0, 0x78,0x56,0x34,0x12,
        2,0,0,0, 0,0,0,0, 0,0x10,0,0, 0x88,0x77,0x66,0x55,
    };
    uint64_t session = UINT64_C(0x1122334455667788);
    size_t i;
    assert(galaxy_hab_layout_build(&layout, targets, 1, 2) == 0);
    assert(layout.count == 1 && layout.total_bytes == 8192);
    assert(layout.streams[0].stride == 12 && layout.streams[0].slot_bytes == 4096);
    unchanged = layout;
    assert(galaxy_hab_layout_build(&layout, targets, 3, 2) == -EINVAL);
    assert(memcmp(&layout, &unchanged, sizeof(layout)) == 0);
    assert(galaxy_hab_layout_build(&layout, targets, 1, 9) == -EINVAL);
    targets[0].width = UINT32_MAX;
    assert(galaxy_hab_layout_build(&layout, targets, 1, 2) != 0);
    targets[0].width = 3;
    targets[0].height = UINT32_MAX;
    assert(galaxy_hab_layout_build(&layout, targets, 1, 2) == -EOVERFLOW);
    targets[0].height = 2;

    assert(galaxy_hab_encode_init(packet, &layout, 9, session, 0x12345678) == 0);
    assert(memcmp(packet, expected_init, 64) == 0);
    assert(galaxy_hab_encode_frame(packet, &layout, 9, 1, 0, 123) == 0);
    assert(word(packet + 8) == 2 && word(packet + 12) == 1 && word(packet + 0x34) == 1);
    assert(word(packet + 0x2c) == 0 && word(packet + 0x3c) == 0);
    assert(galaxy_hab_encode_frame(packet, &layout, 9, 30, 0, 123) == 0);
    assert(word(packet + 0x2c) == 123 && word(packet + 0x34) == 0);
    assert(galaxy_hab_encode_frame(packet, &layout, 9, 0, 0, 321) == 0);
    assert(word(packet + 0x2c) == 321); /* u32 sequence按样本允许回绕。 */
    assert(galaxy_hab_encode_frame(packet, &layout, 9, 1, 1, 0) == -EINVAL);

    memset(packet, 0xa5, sizeof(packet));
    memcpy(before, packet, sizeof(packet));
    assert(galaxy_hab_encode_auth(packet, &layout, 9, session, 7, 1234, NULL, NULL) == -EACCES);
    assert(memcmp(before, packet, sizeof(packet)) == 0);
    signer.fail = 1;
    assert(galaxy_hab_encode_auth(packet, &layout, 9, session, 7, 1234, fixture_sign, &signer) == -EACCES);
    assert(memcmp(before, packet, sizeof(packet)) == 0);
    signer.fail = 0;
    assert(galaxy_hab_encode_auth(packet, &layout, 9, session, 7, 1234, fixture_sign, &signer) == 0);
    assert(signer.size == 184 && word(packet) == 0x48464131 && word(packet + 12) == 184);
    assert(word(packet + 0x10) == 0x55667788 && word(packet + 0x14) == 0x11223344);
    assert(word(packet + 0x18) == 7 && word(packet + 0x20) == 1234);
    assert(word(packet + 0x28) == 9 && word(packet + 0x4c) == 1 && word(packet + 0x54) == 1);
    for (i = 0x5c; i < 0x98; i += 8) assert(word(packet + i) == 0); /* 单流的第二项为0。 */
    for (i = 0; i < 32; ++i) assert(packet[152 + i] == (uint8_t)(0x80 + i));

    assert(galaxy_hab_layout_build(&layout, targets, 2, 6) == 0);
    assert(layout.total_bytes == 49152 && layout.streams[1].base == 24576);
    assert(galaxy_hab_encode_auth(packet, &layout, 9, session, 7, 1234, fixture_sign, &signer) == 0);
    assert(word(packet + 0x54) == 2);
    assert(word(packet + 0x58) == 3 && word(packet + 0x5c) == 5);
    assert(word(packet + 0x60) == 2 && word(packet + 0x64) == 3);
    assert(word(packet + 0x68) == 12 && word(packet + 0x6c) == 20);
    assert(word(packet + 0x70) == 4096 && word(packet + 0x74) == 4096);
    assert(word(packet + 0x78) == 6 && word(packet + 0x7c) == 6);
    assert(word(packet + 0x80) == 0 && word(packet + 0x84) == 24576);
    assert(word(packet + 0x88) == 1 && word(packet + 0x8c) == UINT32_MAX);
    assert(word(packet + 0x90) == 1 && word(packet + 0x94) == (uint32_t)-7);
    assert(galaxy_hab_encode_frame(packet, &layout, 9, 7, 1, 0) == 0);
    assert(word(packet + 0x3c) == 1 && word(packet + 0x34) == 1);

    assert(galaxy_hab_encode_control(packet, 3, 42, 9, session, 0, 0, fixture_sign, &signer) == 0);
    assert(signer.size == 36 && word(signer.input + 24) == 0x55667788);
    assert(word(signer.input + 28) == 0x55667788 && word(signer.input + 32) == 0x11223344);
    assert(word(packet + 0x2c) == 0x83828180 && word(packet + 0x3c) == 0x55667788);
    assert(galaxy_hab_encode_control(packet, 5, 42, 9, session, -2, -7, fixture_sign, &signer) == 0);
    assert(word(packet + 0x34) == UINT32_MAX && word(packet + 0x3c) == (uint32_t)-7);
    assert(word(signer.input + 20) == UINT32_MAX && word(signer.input + 24) == (uint32_t)-7);
    assert(galaxy_hab_encode_control(packet, 5, 42, 9, session, 0, 1, NULL, NULL) == -EACCES);

    memset(packet, 0, 64);
    packet[0] = 0x31; packet[1] = 0x52; packet[2] = 0x46; packet[3] = 0x48;
    packet[8] = 4;
    assert(galaxy_hab_check_ack(packet, 64) == 0);
    assert(galaxy_hab_check_ack(packet, 63) == -EPROTO);
    packet[12] = 1;
    assert(galaxy_hab_check_ack(packet, 64) == -EPROTO);
    packet[12] = 0; packet[8] = 2;
    assert(galaxy_hab_check_ack(packet, 64) == -EPROTO);
    for (i = 0; i < sizeof(memory); ++i) memory[i] = (uint8_t)i;
    /* 独立Python逐字节计算的golden，覆盖65536切换97步长边界。 */
    assert(galaxy_hab_sample_hash(memory, 0) == UINT32_C(0x050c5d1f));
    assert(galaxy_hab_sample_hash(memory, 65536) == UINT32_C(0x1cda5d1f));
    assert(galaxy_hab_sample_hash(memory, 65537) == UINT32_C(0xa2da9130));
    puts("HAB wire layout/auth-signing/ACK/hash contracts passed; synthetic signer only");
    return 0;
}
