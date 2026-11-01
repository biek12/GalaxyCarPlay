/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_PROTOCOL_H
#define GALAXY_HAB_PROTOCOL_H
#include <stddef.h>
#include <stdint.h>

#define GALAXY_HAB_PACKET_SIZE 64u
#define GALAXY_HAB_AUTH_SIZE 184u
#define GALAXY_HAB_MAGIC UINT32_C(0x48465231)
#define GALAXY_HAB_AUTH_MAGIC UINT32_C(0x48464131)
#define GALAXY_HAB_MMID UINT32_C(0x580259)

struct galaxy_hab_target { int32_t display_id; uint32_t width, height; int32_t z_order; };
struct galaxy_hab_stream_layout {
    struct galaxy_hab_target target;
    uint32_t stride, slots, slot_bytes, base;
};
struct galaxy_hab_layout {
    uint32_t count, total_bytes;
    struct galaxy_hab_stream_layout streams[2];
};

/* 认证运算由调用方提供；无默认密钥、空签名或跳过认证的成功路径。
 * AUTH签184B（尾32B为0）；STOP/ZORDER签规范36B。输出32B SHA256 HMAC。
 * 此接口不证明调用方拥有与原receiver互通的认证输入。
 */
typedef int (*galaxy_hab_sign)(void *context, const uint8_t *message,
                              size_t size, uint8_t digest[32]);
int galaxy_hab_layout_build(struct galaxy_hab_layout *layout,
                            const struct galaxy_hab_target *targets,
                            uint32_t count, uint32_t slots);
uint32_t galaxy_hab_sample_hash(const uint8_t *memory, uint32_t size);
int galaxy_hab_encode_auth(uint8_t *packet,
                           const struct galaxy_hab_layout *layout, uint32_t export_id,
                           uint64_t session, uint64_t nonce, uint64_t timestamp_us,
                           galaxy_hab_sign sign, void *sign_context);
int galaxy_hab_encode_init(uint8_t *packet,
                           const struct galaxy_hab_layout *layout, uint32_t export_id,
                           uint64_t session, uint32_t memory_hash);
int galaxy_hab_encode_frame(uint8_t *packet,
                            const struct galaxy_hab_layout *layout, uint32_t export_id,
                            uint32_t sequence, uint32_t stream, uint32_t slot_hash);
int galaxy_hab_encode_control(uint8_t *packet,
                              uint32_t type, uint32_t sequence, uint32_t export_id,
                              uint64_t session, int32_t stream, int32_t z_order,
                              galaxy_hab_sign sign, void *sign_context);
/* 按样本检查magic/type/status/长度，不伪造未知ACK字段的语义。 */
int galaxy_hab_check_ack(const uint8_t *packet, size_t size);
#endif
