/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "probe_common.h"
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

volatile sig_atomic_t probe_stopping;
static void stop(int number) { (void)number; probe_stopping = 1; }
static void output(const char *text, size_t length)
{
    while (length) {
        ssize_t n = write(1, text, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return;
        text += n; length -= (size_t)n;
    }
}
void probe_text(const char *text)
{
    output(text, strlen(text)); output("\n", 1);
}
/* 无printf/FILE/可变参数，便于分离重建CRT与协议/Screen问题；绝不打印地址、key或载荷。 */
void probe_number(const char *label, int64_t value)
{
    char digits[24];
    size_t count = 0;
    uint64_t n = value < 0 ? (uint64_t)(-(value + 1)) + 1 : (uint64_t)value;
    output(label, strlen(label)); output("=", 1);
    if (value < 0) output("-", 1);
    do { digits[count++] = (char)('0' + n % 10); n /= 10; } while (n);
    while (count) output(&digits[--count], 1);
    output("\n", 1);
}
int probe_number_arg(const char *text, uint32_t maximum, uint32_t *result)
{
    uint64_t n = 0;
    if (!text || !*text) return -EINVAL;
    for (; *text; ++text) {
        if (*text < '0' || *text > '9') return -EINVAL;
        n = n * 10 + (unsigned)(*text - '0');
        if (n > maximum) return -EINVAL;
    }
    *result = (uint32_t)n; return 0;
}
int probe_key_file(const char *path, struct galaxy_hab_hmac_key *key)
{
    size_t used = 0;
    uint8_t extra;
    int fd = open(path, O_RDONLY), result = 0;
    if (fd < 0) return -errno;
    while (used < sizeof(key->bytes)) {
        ssize_t n = read(fd, key->bytes + used, sizeof(key->bytes) - used);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { result = n < 0 ? -errno : -EINVAL; break; }
        used += (size_t)n;
    }
    if (!result) {
        ssize_t n;
        do { n = read(fd, &extra, 1); } while (n < 0 && errno == EINTR);
        if (n != 0) result = n < 0 ? -errno : -EINVAL;
    }
    if (close(fd) < 0 && !result) result = -errno;
    if (result) probe_wipe(key);
    return result;
}
void probe_wipe(struct galaxy_hab_hmac_key *key)
{
    volatile uint8_t *p = key->bytes;
    for (size_t i = 0; i < sizeof(key->bytes); ++i) p[i] = 0;
}
int probe_signals(void)
{
    if (signal(SIGINT, stop) == SIG_ERR || signal(SIGTERM, stop) == SIG_ERR) return -errno;
    return 0;
}
int probe_selftest(void)
{
    /* RFC4231公开向量：key0b*20补零至32B，与外部测试认证key无关。 */
    static const uint8_t expected[32] = {
        0xb0,0x34,0x4c,0x61,0xd8,0xdb,0x38,0x53,0x5c,0xa8,0xaf,0xce,0xaf,0x0b,0xf1,0x2b,
        0x88,0x1d,0xc2,0x00,0xc9,0x83,0x3d,0xa7,0x26,0xe9,0x37,0x6c,0x2e,0x32,0xcf,0xf7
    };
    struct galaxy_hab_hmac_key key = {{0}};
    uint8_t digest[32];
    memset(key.bytes, 0x0b, 20);
    int result = galaxy_hab_hmac_sign(&key, (const uint8_t *)"Hi There", 8, digest);
    if (!result && memcmp(digest, expected, sizeof(digest))) result = -EBADMSG;
    probe_wipe(&key);
    probe_text(result ? "SELFTEST_FAIL" : "SELFTEST_PASS");
    return result ? 1 : 0;
}
