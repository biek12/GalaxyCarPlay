/* SPDX-License-Identifier: GPL-3.0-only
 * 仅重建ABI诊断构建；不是QNX SDK。数值依据QNX 7.1官方errno表，与Android分离。
 */
#ifndef GALAXY_QNX_ABI_CANDIDATE
#error Reconstructed declarations require explicit candidate build
#endif
extern int *__get_errno_ptr(void);
#define errno (*__get_errno_ptr())
#define EINTR 4
#define EIO 5
#define ENOMEM 12
#define EACCES 13
#define EFAULT 14
#define ENODEV 19
#define EINVAL 22
#define EPROTO 71
#define EBADMSG 77
#define EOVERFLOW 79
#define EALREADY 237
#define EMSGSIZE 240
#define ENOTCONN 257
