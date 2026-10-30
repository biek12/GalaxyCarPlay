/* SPDX-License-Identifier: GPL-3.0-only; minimal candidate ABI declarations, not an SDK. */
#include <stddef.h>
extern void *memset(void *, int, size_t);
extern void *memcpy(void *, const void *, size_t);
extern int memcmp(const void *, const void *, size_t);
extern int strcmp(const char *, const char *);
extern size_t strlen(const char *);
