/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "galaxy_hab_receiver.h"
#include "galaxy_hab_hmac.h"
#include "galaxy_hab_owned.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

/* 本机对照旧QNX的scratch→中转→Screen与新协商消费确认后的共享slot→Screen；不打开HAB/Screen。
 * 共享映射为合成像素，保留30号hash路径；不把主机时间冒充8155/真实显示延迟。
 */
static double now_ms(void)
{
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) exit(2);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1000000.0;
}
static double cpu_ms(void)
{
    struct rusage r;
    if (getrusage(RUSAGE_SELF, &r)) exit(2);
    return (r.ru_utime.tv_sec + r.ru_stime.tv_sec) * 1000.0 +
        (r.ru_utime.tv_usec + r.ru_stime.tv_usec) / 1000.0;
}
static int compare(const void *a, const void *b)
{
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}
int main(void)
{
    struct galaxy_hab_receiver receiver;
    struct galaxy_hab_hmac_key key = {{0}}; /* 公开合成fixture。 */
    struct galaxy_hab_target target = {2, 1920, 532, 4};
    struct galaxy_hab_layout layout;
    struct galaxy_hab_frame_info info;
    uint8_t auth[184], packet[64], ack[64];
    const size_t row = 1920u * 4u, stride = row + 64u, height = 532u;
    const unsigned iterations = 600, warmup = 60;
    uint8_t *mapping, *screen, *legacy;
    double timings[600];
    if (galaxy_hab_layout_build(&layout, &target, 1, 6)) return 2;
    mapping = calloc(1, layout.total_bytes); screen = malloc(stride * height);
    if (!mapping || !screen) return 2;
    for (size_t i = 0; i < layout.total_bytes; ++i) mapping[i] = (uint8_t)(i % 251u);
    galaxy_hab_receiver_init(&receiver, galaxy_hab_hmac_sign, &key);
    if (galaxy_hab_encode_auth(auth, &layout, 91, 99, 7, 1234, galaxy_hab_hmac_sign, &key) ||
        galaxy_hab_receiver_auth(&receiver, auth, sizeof(auth)) ||
        galaxy_hab_encode_init(packet, &layout, 91, 99, galaxy_hab_sample_hash(mapping, layout.total_bytes)) ||
        galaxy_hab_receiver_start(&receiver, packet, sizeof(packet), mapping, layout.total_bytes, ack)) return 2;
    puts("{\"scope\":\"host synthetic receiver copy only\",\"width\":1920,\"height\":532,\"results\":[");
    /* ABBA顺序，观察缓存/温度/顺序噪声；每组均先预热，不把单次最优值作为结论。 */
    for (int batch = 0; batch < 4; ++batch) {
        int old = batch == 0 || batch == 3;
        double start, cpu, elapsed;
        galaxy_hab_receiver_close(&receiver);
        if (galaxy_hab_receiver_auth(&receiver, auth, sizeof(auth)) ||
            galaxy_hab_encode_init(packet, &layout, 91, 99, galaxy_hab_sample_hash(mapping, layout.total_bytes)) ||
            galaxy_hab_receiver_start(&receiver, packet, sizeof(packet), mapping, layout.total_bytes, ack)) return 2;
        if (!old && (galaxy_hab_owned_encode(packet, GALAXY_HAB_OWNED_HELLO, 0, 91, 0, 99,
                galaxy_hab_hmac_sign, &key) ||
            galaxy_hab_receiver_enable_consumption(&receiver, packet, sizeof(packet), ack))) return 2;
        legacy = old ? malloc(row * height) : NULL;
        if (old && !legacy) return 2;
        start = cpu = 0;
        for (unsigned i = 0; i < iterations + warmup; ++i) {
            uint32_t sequence = i + 1, slot = sequence % 6u;
            uint32_t hash = sequence % 30u ? 0 : galaxy_hab_sample_hash(
                mapping + slot * layout.streams[0].slot_bytes, layout.streams[0].slot_bytes);
            if (galaxy_hab_encode_frame(packet, &layout, 91, sequence, 0, hash)) return 2;
            if (i == warmup) { start = now_ms(); cpu = cpu_ms(); }
            double frame_start = now_ms();
            int result;
            if (old) {
                result = galaxy_hab_receiver_frame(&receiver, packet, sizeof(packet), legacy, row * height, &info);
                if (!result) for (size_t y = 0; y < height; ++y)
                    memcpy(screen + y * stride, legacy + y * row, row);
            } else result = galaxy_hab_receiver_frame_strided(&receiver, packet, sizeof(packet),
                screen, stride * height, stride, &info);
            if (result || screen[0] != mapping[slot * layout.streams[0].slot_bytes]) return 2;
            if (i >= warmup) timings[i - warmup] = now_ms() - frame_start;
        }
        elapsed = now_ms() - start; cpu = cpu_ms() - cpu;
        qsort(timings, iterations, sizeof(double), compare);
        printf("%s{\"path\":\"%s\",\"frames\":%u,\"cpu_ms\":%.3f,\"wall_ms\":%.3f,"
            "\"frame_p50_ms\":%.3f,\"frame_p95_ms\":%.3f,\"frame_p99_ms\":%.3f,\"staging_bytes\":%zu}",
            batch ? ",\n" : "", old ? "before" : "after", iterations, cpu, elapsed,
            timings[iterations / 2], timings[iterations * 95 / 100], timings[iterations * 99 / 100], old ? row * height + layout.streams[0].slot_bytes : 0);
        free(legacy);
    }
    puts("\n]}");
    galaxy_hab_receiver_close(&receiver); free(mapping); free(screen);
    return 0;
}
