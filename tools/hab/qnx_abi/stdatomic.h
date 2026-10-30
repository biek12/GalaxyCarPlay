/* SPDX-License-Identifier: GPL-3.0-only
 * 诊断只使用编译器fence intrinsic，不复用NDK stdatomic中的Android cdefs依赖。
 */
#define memory_order_acquire __ATOMIC_ACQUIRE
#define memory_order_seq_cst __ATOMIC_SEQ_CST
#define atomic_thread_fence(order) __atomic_thread_fence(order)
