/* SPDX-License-Identifier: GPL-3.0-only; QNX aarch64 LP64 diagnostic ABI only. */
#include <stddef.h>
typedef long ssize_t;
extern ssize_t read(int, void *, size_t);
extern ssize_t write(int, const void *, size_t);
extern int close(int);
