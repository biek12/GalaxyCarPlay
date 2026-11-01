/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "probe_common.h"
#include "galaxy_hab_sender.h"
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* Android API29/aarch64诊断CLI；只发送人工色条/位置标记，无CarPlay载荷或原车控制。 */
static int transport(void)
{
    struct galaxy_hab_transport t;
    uint8_t message[64];
    size_t received = 0;
    int result, closed;
    galaxy_hab_transport_init(&t, galaxy_hab_linux_io());
    probe_text("TRANSPORT_OPEN_BEGIN");
    result = galaxy_hab_transport_open(&t, GALAXY_HAB_MMID, 10000);
    probe_number("TRANSPORT_OPEN", result);
    memset(message, 0xa5, sizeof(message));
    if (!result) result = galaxy_hab_transport_send(&t, message, sizeof(message));
    probe_number("TRANSPORT_SEND", result);
    if (!result) result = galaxy_hab_transport_receive(&t, message, sizeof(message), 0, &received);
    if (!result && received != sizeof(message)) result = -EPROTO;
    for (size_t i = 0; !result && i < received; ++i) if (message[i] != 0x5a) result = -EBADMSG;
    probe_number("TRANSPORT_REPLY", result);
    closed = galaxy_hab_transport_close(&t);
    probe_number("TRANSPORT_CLOSE", closed);
    return result || closed ? 1 : 0;
}
static void pattern(uint8_t *image, uint32_t width, uint32_t height, uint32_t frame)
{
    static const uint8_t rgb[4][3] = {{255,0,0},{0,255,0},{0,0,255},{255,255,255}};
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            size_t offset = ((size_t)y * width + x) * 4;
            const uint8_t *color = rgb[((uint64_t)x * 4 / width) % 4];
            int marker = x / 32 == (frame * 3) % ((width + 31) / 32) && y > height * 3 / 4;
            image[offset] = marker ? 0 : color[0];
            image[offset+1] = marker ? 0 : color[1];
            image[offset+2] = marker ? 0 : color[2];
            image[offset+3] = 255;
        }
    }
}
int main(int argc, char **argv)
{
    struct galaxy_hab_sender sender;
    struct galaxy_hab_hmac_key key;
    struct galaxy_hab_target target;
    uint32_t display, width, height, z, frames, delay;
    uint8_t *image;
    uint64_t session = 0, nonce = 0;
    struct timespec now;
    int result, closed, fd;
    if (argc == 2 && !strcmp(argv[1], "self-test")) return probe_selftest();
    if (probe_signals()) return 1;
    if (argc == 2 && !strcmp(argv[1], "transport")) return transport();
    if (argc != 10 || strcmp(argv[1], "frames") ||
        probe_number_arg(argv[3], 16, &display) || probe_number_arg(argv[4], 3840, &width) ||
        probe_number_arg(argv[5], 2160, &height) || probe_number_arg(argv[6], 16, &z) ||
        probe_number_arg(argv[7], 120, &frames) || probe_number_arg(argv[8], 2000, &delay) ||
        strcmp(argv[9], "synthetic-only") || width < 16 || height < 16 || !frames || delay < 50) {
        probe_text("usage: android_probe self-test | transport | frames KEY DISPLAY WIDTH HEIGHT Z FRAMES DELAY_MS synthetic-only");
        return 2;
    }
    result = probe_key_file(argv[2], &key);
    if (result) { probe_number("KEY_INPUT_ERROR", result); return 2; }
    /* 使用独立系统随机输入，避免硬编码重放session；不写日志。 */
    fd = open("/dev/urandom", O_RDONLY);
    if (fd < 0 || read(fd, &session, sizeof(session)) != sizeof(session) ||
        read(fd, &nonce, sizeof(nonce)) != sizeof(nonce) || !session || !nonce) {
        if (fd >= 0) close(fd);
        probe_wipe(&key); return 1;
    }
    close(fd);
    if (clock_gettime(CLOCK_REALTIME, &now)) { probe_wipe(&key); return 1; }
    image = malloc((size_t)width * height * 4);
    if (!image) { probe_wipe(&key); return 1; }
    target.display_id = (int32_t)display; target.width = width;
    target.height = height; target.z_order = (int32_t)z;
    galaxy_hab_sender_init(&sender, galaxy_hab_linux_io());
    probe_text("SENDER_OPEN_BEGIN");
    result = galaxy_hab_sender_open(&sender, &target, 1, 6, 10000, session, nonce,
        (uint64_t)now.tv_sec * 1000000u + (uint64_t)now.tv_nsec / 1000u, galaxy_hab_hmac_sign, &key);
    probe_number("SENDER_AUTH_INIT_ACK", result);
    for (uint32_t i = 0; !result && !probe_stopping && i < frames; ++i) {
        struct timespec pause = {(time_t)(delay / 1000), (long)(delay % 1000) * 1000000L};
        pattern(image, width, height, i);
        result = galaxy_hab_sender_frame(&sender, 0, image, (size_t)width*height*4, (size_t)width*4, 4);
        probe_number("SENDER_FRAME_SEQUENCE", sender.sequence);
        probe_number("SENDER_FRAME_RESULT", result);
        while (!probe_stopping && nanosleep(&pause, &pause) && errno == EINTR) { }
    }
    closed = galaxy_hab_sender_close(&sender);
    probe_number("SENDER_STOP_CLOSE", closed);
    free(image); probe_wipe(&key);
    return result || closed ? 1 : 0;
}
