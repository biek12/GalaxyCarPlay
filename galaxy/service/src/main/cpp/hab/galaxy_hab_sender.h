/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_SENDER_H
#define GALAXY_HAB_SENDER_H
#include "galaxy_hab_protocol.h"
#include "galaxy_hab_transport.h"

/* 单线程owner；Android/JNI/图像生产者接入前先验证此独立C状态机。
 * 此结构不持有原厂handle，不调用任何AutoDisplay函数。
 */
struct galaxy_hab_sender {
    struct galaxy_hab_transport transport;
    struct galaxy_hab_layout layout;
    uint8_t *memory;
    uint32_t export_id, sequence, checksum_retries;
    uint32_t checksum_sequence, checksum_expected, checksum_local, local_hash_failures;
    uint64_t session;
    galaxy_hab_sign sign;
    void *sign_context;
    int ready, owned_consumption, owned_failed;
};
void galaxy_hab_sender_init(struct galaxy_hab_sender *sender, struct galaxy_hab_io io);
/* open/ACK可能阻塞；不得在Android主线程调用；不承诺驱动未实现的ACK超时。 */
int galaxy_hab_sender_open(struct galaxy_hab_sender *sender,
                           const struct galaxy_hab_target *targets, uint32_t count,
                           uint32_t slots, uint32_t open_timeout_ms,
                           uint64_t session, uint64_t nonce, uint64_t timestamp_us,
                           galaxy_hab_sign sign, void *sign_context);
int galaxy_hab_sender_frame(struct galaxy_hab_sender *sender, uint32_t stream,
                            const uint8_t *source, size_t capacity,
                            size_t row_stride, size_t pixel_stride);
/* 自有实时链路在READY前调用；旧协议对照测试仍可只调用open，不冒充原版逐帧ACK。 */
int galaxy_hab_sender_enable_consumption(struct galaxy_hab_sender *sender);
int galaxy_hab_sender_z_order(struct galaxy_hab_sender *sender, int32_t stream,
                              int32_t z_order);
int galaxy_hab_sender_close(struct galaxy_hab_sender *sender);
#endif
