/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_RECEIVER_H
#define GALAXY_HAB_RECEIVER_H
#include "galaxy_hab_protocol.h"

/* 自有可移植协议接收核心，不是原receiver反编译实现，也不是QNX Screen后端。
 * owner串行调用；映射由平台导入/持有，必须存活至close/STOP返回，不由此核心unmap。
 * key/sign context由调用者持有。此核心校验包和HMAC，不提供跨重启防重放或显示授权。
 */
struct galaxy_hab_receiver {
    struct galaxy_hab_layout layout;
    uint32_t export_id;
    uint64_t session, nonce, timestamp_us;
    galaxy_hab_sign sign;
    void *sign_context;
    const uint8_t *mapping;
    size_t mapping_size;
    uint8_t *scratch;
    size_t scratch_size;
    int authenticated, ready;
};
struct galaxy_hab_frame_info {
    uint32_t sequence, stream, width, height, stride;
    int32_t display_id;
    size_t bytes;
};
struct galaxy_hab_control_event { uint32_t type, sequence; int32_t stream, z_order; };

void galaxy_hab_receiver_init(struct galaxy_hab_receiver *receiver,
                              galaxy_hab_sign sign, void *sign_context);
int galaxy_hab_receiver_auth(struct galaxy_hab_receiver *receiver,
                             const uint8_t *packet, size_t size);
/* 映射必须对应已认证exportId的完整只读共享区；此处不实现QNX import。
 * ACK为本实现定义的最小成功ACK（满足已恢复的sender读取合同）；无逐帧ACK。
 */
int galaxy_hab_receiver_start(struct galaxy_hab_receiver *receiver,
                              const uint8_t *init_packet, size_t size,
                              const uint8_t *mapping, size_t mapping_size,
                              uint8_t *ack);
/* 复制完整slot到私有scratch后验证采样hash，再复制有效BGRA像素到output。
 * 未采样帧无法检测撕裂；没有消费fence，不能以此宣称跨域并发读取安全。
 * 失败不修改output/info；不允许output/info别名覆盖借用映射或核心scratch。
 */
int galaxy_hab_receiver_frame(struct galaxy_hab_receiver *receiver,
                              const uint8_t *packet, size_t size,
                              uint8_t *output, size_t capacity,
                              struct galaxy_hab_frame_info *info);
/* STOP只结束协议会话并释放scratch；OEM窗口恢复/unimport由平台owner负责。
 * 未知streamIndex仅作为已认证事件返回，不猜负值/越界值的窗口含义。
 */
int galaxy_hab_receiver_control(struct galaxy_hab_receiver *receiver,
                                const uint8_t *packet, size_t size,
                                struct galaxy_hab_control_event *event);
void galaxy_hab_receiver_close(struct galaxy_hab_receiver *receiver);
#endif
