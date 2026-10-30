/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "galaxy_hab_qnx.h"
#include "galaxy_hab_hmac.h"
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>

static volatile sig_atomic_t stopping;
static void request_stop(int signal_number) { (void)signal_number; stopping = 1; }
static int cancelled(void *context) { (void)context; return stopping != 0; }

static int number(const char *text, int *output)
{
    char *end;
    long value;
    errno = 0;
    value = strtol(text, &end, 10);
    if (errno || end == text || *end || value < INT_MIN || value > INT_MAX) return -EINVAL;
    *output = (int)value;
    return 0;
}

/* 独立QNX进程，使用QNX SDK的CRT/libc；不使用Android头文件、手写_start或占位链接库。
 * key文件必须由设备操作授权的操作者显式提供，恰好32B；不创建/打印/内置认证材料。
 * 信号仅请求退出；驱动若不响应EINTR，不能保证阻塞recv即时取消。
 */
int main(int argc, char **argv)
{
    struct galaxy_hab_qnx owner;
    struct galaxy_hab_hmac_key key;
    struct galaxy_hab_target target;
    struct sigaction action = {0};
    FILE *input;
    int display, width, height, z, result, closed;
    size_t i;
    volatile uint8_t *secret = key.bytes;
    if (argc != 6 || number(argv[2], &display) || number(argv[3], &width) ||
        number(argv[4], &height) || number(argv[5], &z) || display < 0 || width <= 0 || height <= 0) {
        fprintf(stderr, "usage: galaxy_hab_qnx_receiver <external-32-byte-key-file> <qnx-display-id> <width> <height> <z-order>\n");
        return 2;
    }
    input = fopen(argv[1], "rb");
    if (!input) return 2;
    result = fread(key.bytes, 1, sizeof(key.bytes), input) == sizeof(key.bytes) && fgetc(input) == EOF && !ferror(input);
    closed = fclose(input);
    if (!result || closed) {
        for (i = 0; i < sizeof(key.bytes); ++i) secret[i] = 0;
        return 2;
    }
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    result = 0;
    if (sigaction(SIGTERM, &action, NULL) || sigaction(SIGINT, &action, NULL))
        result = -errno;
    galaxy_hab_qnx_init(&owner, galaxy_hab_hmac_sign, &key);
    owner.cancelled = cancelled;
    target.display_id = display;
    target.width = (uint32_t)width;
    target.height = (uint32_t)height;
    target.z_order = z;
    if (!result && !stopping) result = galaxy_hab_qnx_open(&owner, &target, 1, 60000);
    while (!result && !stopping) result = galaxy_hab_qnx_step_timeout(&owner, 5000);
    closed = galaxy_hab_qnx_close(&owner);
    for (i = 0; i < sizeof(key.bytes); ++i) secret[i] = 0;
    if (result < 0 && !(stopping && result == -EINTR)) {
        fprintf(stderr, "HAB receiver failed: %d\n", result);
        return 1;
    }
    return closed ? 1 : 0;
}
