/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "galaxy_hab_sender.h"
#include "galaxy_hab_hmac.h"
#include "galaxy_hab_owned.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/random.h>
#include <time.h>
#include <unistd.h>

/* APK内的自有PIE子进程：stdin先32B应用自生成key，随后LE32长度/stride/pixelStride/保留字+RGBA。
 * 0长度为STOP，UINT32_MAX为心跳。单帧有界、无帧排队；不记录key或像素。
 * 阻塞驱动隔离于本进程。SIGTERM打断等待并正常清理，父进程有独立的强制回收期限。
 */
static volatile sig_atomic_t stopping;
static void stop(int number) { (void)number; stopping = 1; }
static uint32_t word(const uint8_t *p)
{ return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static int read_all(void *buffer, size_t size)
{
    uint8_t *p = buffer;
    while (size && !stopping) {
        ssize_t count = read(STDIN_FILENO, p, size);
        if (count < 0) { if (errno == EINTR && !stopping) continue; return -errno; }
        if (!count) return -EPIPE;
        p += count; size -= (size_t)count;
    }
    return stopping ? -EINTR : 0;
}
static void trace_checksum(const struct galaxy_hab_sender *sender, uint32_t *reported)
{
    if (sender->checksum_retries == *reported && !sender->local_hash_failures) return;
    *reported = sender->checksum_retries;
    printf("HAB: checksum-retry sequence=%u retries=%u expected=%08x local=%08x localFailures=%u\n",
        sender->checksum_sequence, sender->checksum_retries, sender->checksum_expected,
        sender->checksum_local, sender->local_hash_failures);
    fflush(stdout);
}
int main(void)
{
    struct galaxy_hab_sender sender;
    struct galaxy_hab_hmac_key key = {{0}};
    struct galaxy_hab_target target = {2, 1920, 532, 4};
    struct sigaction action = {0};
    struct timespec now;
    uint64_t random[2];
    uint8_t header[16], *pixels = NULL;
    uint32_t last_size = 0, last_stride = 0, last_pixel_stride = 0, reported_retries = 0;
    size_t i;
    const size_t maximum = 16u * 1024u * 1024u;
    int result, closed;
    action.sa_handler = stop;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, NULL) || sigaction(SIGINT, &action, NULL) ||
        sigaction(SIGALRM, &action, NULL)) return 2;
    signal(SIGPIPE, SIG_IGN);
    galaxy_hab_sender_init(&sender, galaxy_hab_linux_io());
    alarm(12);
    result = read_all(key.bytes, sizeof(key.bytes));
    if (!result && getrandom(random, sizeof(random), 0) != (ssize_t)sizeof(random)) result = -EIO;
    if (!result && clock_gettime(CLOCK_MONOTONIC, &now)) result = -errno;
    if (!result) result = galaxy_hab_sender_open(&sender, &target, 1, 6, 10000,
        random[0] | 1u, random[1] | 1u, (uint64_t)now.tv_sec * 1000000u + (uint64_t)now.tv_nsec / 1000u,
        galaxy_hab_hmac_sign, &key);
    if (!result) result = galaxy_hab_sender_enable_consumption(&sender);
    if (!result) {
        pixels = malloc(maximum);
        if (!pixels) result = -ENOMEM;
    }
    if (!result) {
        printf("HAB: consumption protocol=GLC2 version=%u frameChecksum=every-frame\n", GALAXY_HAB_OWNED_VERSION);
        puts("HAB: ready display=2 size=1920x532 slots=6"); fflush(stdout);
    }
    while (!result && !stopping) {
        uint32_t size;
        alarm(12);
        result = read_all(header, sizeof(header));
        if (result) break;
        size = word(header);
        if (!size) break;
        if (size == UINT32_MAX) {
            /* 静态画面仍送保留帧：对端的有限失联回收不依赖View持续重绘。 */
            alarm(2);
            if (last_size) result = galaxy_hab_sender_frame(&sender, 0, pixels, last_size,
                last_stride, last_pixel_stride);
            trace_checksum(&sender, &reported_retries);
            if (!result) { printf("HAB: frame-posted ticket=%u sequence=%u\n", word(header + 12), sender.sequence); fflush(stdout); }
            continue;
        }
        if (size > maximum) { result = -EMSGSIZE; break; }
        result = read_all(pixels, size);
        alarm(2);
        if (!result) result = galaxy_hab_sender_frame(&sender, 0, pixels, size,
            word(header + 4), word(header + 8));
        trace_checksum(&sender, &reported_retries);
        if (!result) { last_size = size; last_stride = word(header + 4); last_pixel_stride = word(header + 8); }
        if (!result) { printf("HAB: frame-posted ticket=%u sequence=%u\n", word(header + 12), sender.sequence); fflush(stdout); }
        if (!result && sender.sequence % 100u == 0) {
            printf("HAB: submitted frames=%u checksumRetries=%u\n", sender.sequence, sender.checksum_retries); fflush(stdout);
        }
    }
    alarm(2);
    closed = galaxy_hab_sender_close(&sender);
    free(pixels);
    for (i = 0; i < sizeof(key.bytes); ++i) ((volatile uint8_t *)key.bytes)[i] = 0;
    printf("HAB: closed result=%d cleanup=%d\n", result, closed);
    return ((result && result != -EPIPE && !(stopping && result == -EINTR)) || closed) ? 1 : 0;
}
