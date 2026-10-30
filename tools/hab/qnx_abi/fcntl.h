/* SPDX-License-Identifier: GPL-3.0-only; only read-only open is needed for synthetic key input. */
#define O_RDONLY 0
extern int open(const char *, int, ...);
