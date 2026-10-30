/* SPDX-License-Identifier: GPL-3.0-only; minimal diagnostic ABI; no guessed sigaction struct. */
typedef int sig_atomic_t;
#define SIGINT 2
#define SIGTERM 15
#define SIG_ERR ((void (*)(int))-1)
extern void (*signal(int, void (*)(int)))(int);
