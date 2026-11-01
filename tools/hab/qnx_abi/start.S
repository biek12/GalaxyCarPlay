/* SPDX-License-Identifier: GPL-3.0-only
 * 自有最小诊断入口：目标habtest VA0x1c1c–0x1ca8核验的栈/auxv/_init_libc接点。
 * 本诊断无自身constructor；非SDK CRT，不用于宣称正式QNX编译。
 */
.text
.global _start
.type _start,%function
_start:
    mov x4, x0
    ldr w0, [sp]
    add x1, sp, #8
    add x2, x1, x0, lsl #3
    add x2, x2, #8
    mov x3, x2
1:  ldr x16, [x3], #8
    cbnz x16, 1b
    mov x19, x0
    mov x20, x1
    mov x21, x2
    bl _init_libc
    bl __get_errno_ptr
    str wzr, [x0]
    mov w0, w19
    mov x1, x20
    mov x2, x21
    bl main
    bl exit
    b .
