/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_OWNED_H
#define GALAXY_HAB_OWNED_H
#include "galaxy_hab_protocol.h"

/* 自有可选扩展，不属于AutoDisplay还原字段。64B: magic/version/type/sequence/
 * exportId/stream/session(64bit)/HMAC-SHA256。HMAC输入为末32B清零的完整64B。
 * HELLO/READY后sender必须等POSTED才再写共享slot；POSTED指Screen post调用成功返回，
 * v2要求每帧采样校验及QNX导入cache同步；旧v1握手不能冒充支持这些能力。
 * 不是面板扫描完成，不将两个虚机的单调时钟直接相减。
 */
#define GALAXY_HAB_OWNED_MAGIC UINT32_C(0x32434c47)
#define GALAXY_HAB_OWNED_VERSION 2u
#define GALAXY_HAB_OWNED_HELLO 1u
#define GALAXY_HAB_OWNED_READY 2u
#define GALAXY_HAB_OWNED_POSTED 3u
/* 已通过报文身份校验，但共享slot的采样hash失败；同序号最多重写两次，不提交坏帧。 */
#define GALAXY_HAB_OWNED_RETRY 4u
int galaxy_hab_owned_encode(uint8_t *packet, uint32_t type, uint32_t sequence,
    uint32_t export_id, uint32_t stream, uint64_t session, galaxy_hab_sign sign, void *context);
int galaxy_hab_owned_check(const uint8_t *packet, size_t size, uint32_t type,
    uint32_t sequence, uint32_t export_id, uint32_t stream, uint64_t session,
    galaxy_hab_sign sign, void *context);
#endif
