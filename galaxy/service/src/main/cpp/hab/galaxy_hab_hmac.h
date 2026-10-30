/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_HMAC_H
#define GALAXY_HAB_HMAC_H
#include <stddef.h>
#include <stdint.h>

/* 本仓库自有标准HMAC-SHA256实现。没有内嵌AutoDisplay认证常量或默认密钥。
 * 调用方显式管理32B key的生命周期；库不记录/持久化key。
 */
struct galaxy_hab_hmac_key { uint8_t bytes[32]; };
int galaxy_hab_hmac_sign(void *context, const uint8_t *message,
                         size_t size, uint8_t digest[32]);
#endif
