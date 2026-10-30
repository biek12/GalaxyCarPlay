/* SPDX-License-Identifier: GPL-3.0-only
 * 重建ABI候选专用，非SDK头。目标libpmem_client.so在0x59b0/0x6d04
 * 将0x02000004传给msync；官方QNX文档规定CACHE_ONLY|INVALIDATE用于数据cache。
 */
#ifndef GALAXY_QNX_ABI_CANDIDATE
#error Reconstructed declarations require explicit candidate build
#endif
#include <stddef.h>
#define MS_CACHE_ONLY 0x02000000
#define MS_INVALIDATE 0x00000004
extern int msync(void *, size_t, int);
