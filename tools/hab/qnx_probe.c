/* SPDX-License-Identifier: GPL-3.0-only */
#include "probe_common.h"
#include "galaxy_hab_qnx.h"
#include <errno.h>
#include <limits.h>
#include <string.h>

extern int habmm_socket_open(int *, unsigned, unsigned, unsigned);
extern int habmm_socket_recv(int, void *, unsigned *, unsigned, unsigned);
extern int habmm_socket_send(int, const void *, unsigned, unsigned);
extern int habmm_socket_close(int);

static int transport(void)
{
    int vc = 0, opened = 0, result, closed = 0;
    unsigned length = 64;
    uint8_t message[64];
    probe_text("TRANSPORT_OPEN_BEGIN");
    result = habmm_socket_open(&vc, GALAXY_HAB_MMID, 10000, 0);
    probe_number("TRANSPORT_OPEN", result);
    if (!result) { opened = 1; result = habmm_socket_recv(vc, message, &length, 0, 0); }
    if (!result && length != 64) result = -EPROTO;
    for (unsigned i = 0; !result && i < length; ++i) if (message[i] != 0xa5) result = -EBADMSG;
    probe_number("TRANSPORT_RECEIVE", result);
    memset(message, 0x5a, sizeof(message));
    if (!result) result = habmm_socket_send(vc, message, sizeof(message), 0);
    probe_number("TRANSPORT_REPLY", result);
    if (opened) closed = habmm_socket_close(vc);
    probe_number("TRANSPORT_CLOSE", closed);
    return result || closed ? 1 : 0;
}
static int inventory(void)
{
    screen_context_t context = NULL;
    screen_display_t displays[16] = {NULL};
    int result = screen_create_context(&context, SCREEN_APPLICATION_CONTEXT), count = 0;
    if (!result) result = screen_get_context_property_iv(context, SCREEN_PROPERTY_DISPLAY_COUNT, &count);
    if (!result && (count < 1 || count > 16)) result = -EPROTO;
    if (!result) result = screen_get_context_property_pv(context, SCREEN_PROPERTY_DISPLAYS, (void **)displays);
    for (int i = 0; !result && i < count; ++i) {
        int id = 0, size[2] = {0, 0};
        result = screen_get_display_property_iv(displays[i], SCREEN_PROPERTY_ID, &id);
        if (!result) result = screen_get_display_property_iv(displays[i], SCREEN_PROPERTY_SIZE, size);
        if (!result) { probe_number("QNX_DISPLAY_ID", id); probe_number("WIDTH", size[0]); probe_number("HEIGHT", size[1]); }
    }
    if (context) {
        int closed = screen_destroy_context(context);
        if (!result) result = closed;
    }
    probe_number("INVENTORY_RESULT", result);
    return result ? 1 : 0;
}
static void diagnostic_trace(void *context, const char *stage, int result)
{
    (void)context;
    if (result == INT_MIN) { probe_text("BEGIN"); probe_text(stage); }
    else probe_number("RETURN", result);
}
static int cancelled(void *context) { (void)context; return probe_stopping != 0; }
int main(int argc, char **argv)
{
    struct galaxy_hab_hmac_key key;
    struct galaxy_hab_qnx q;
    struct galaxy_hab_target target;
    uint32_t display, width, height, z, frames = 0, sessions = 0;
    unsigned consecutive_failures = 0;
    int result, closed, serving;
#ifdef GALAXY_QNX_ABI_CANDIDATE
    probe_text("BUILD=QNX_RECONSTRUCTED_ABI_CANDIDATE");
#else
    probe_text("BUILD=QNX_SDK");
#endif
    if (argc == 2 && !strcmp(argv[1], "self-test")) return probe_selftest();
    if (probe_signals()) return 1;
    if (argc == 2 && !strcmp(argv[1], "inventory")) return inventory();
    if (argc == 2 && !strcmp(argv[1], "transport")) return transport();
    serving = (argc == 7 || (argc == 8 && !strcmp(argv[7], "trace"))) && !strcmp(argv[1], "serve");
    if ((!serving && (argc != 8 || strcmp(argv[1], "receive"))) ||
        probe_number_arg(argv[3], 16, &display) || probe_number_arg(argv[4], 3840, &width) ||
        probe_number_arg(argv[5], 2160, &height) || probe_number_arg(argv[6], 16, &z) ||
        (!serving && strcmp(argv[7], "trace")) || width < 16 || height < 16) {
        probe_text("usage: qnx_probe self-test | inventory | transport | receive KEY DISPLAY WIDTH HEIGHT Z trace | serve KEY DISPLAY WIDTH HEIGHT Z");
        return 2;
    }
    result = probe_key_file(argv[2], &key);
    if (result) { probe_number("KEY_INPUT_ERROR", result); return 2; }
    target.display_id = (int32_t)display; target.width = width;
    target.height = height; target.z_order = (int32_t)z;
    do {
        frames = 0;
        probe_number("RECEIVER_LISTEN_SESSION", ++sessions);
        galaxy_hab_qnx_init(&q, galaxy_hab_hmac_sign, &key);
        q.trace = !serving || argc == 8 ? diagnostic_trace : NULL;
        q.cancelled = cancelled;
        result = galaxy_hab_qnx_open(&q, &target, 1, serving ? 600000 : 10000);
        probe_number("RECEIVER_READY_ACK", result);
        while (!result && !probe_stopping && (serving || frames < 120)) {
            result = serving ? galaxy_hab_qnx_step_timeout(&q, 5000) : galaxy_hab_qnx_step(&q);
            if (!result) {
                ++frames;
                if (!serving || frames % 100u == 0) probe_number("RECEIVER_FRAME_OR_CONTROL_COUNT", frames);
            }
        }
        probe_number("RECEIVER_LOOP_RESULT", result);
        probe_number("RECEIVER_PROCESSED_EVENTS", frames);
        probe_number("RECEIVER_SIGNAL_STOP_REQUESTED", probe_stopping != 0);
        closed = galaxy_hab_qnx_close(&q);
        probe_number("RECEIVER_CLOSE", closed);
        if (!serving || probe_stopping || closed) break;
        /* 正常STOP后回到无窗口监听；已显示会话的断线也先完全释放再接受下一代。
         * 不通过反复写窗口保活；连续三次建链/空会话失败停止，认证/协议错误立即停止。 */
        if (result == 1) { consecutive_failures = 0; continue; }
        if (result == -EACCES || result == -EPROTO || result == -EBADMSG || result == -EINVAL) break;
        if (frames) consecutive_failures = 0;
        if (++consecutive_failures >= 3) break;
    } while (!probe_stopping);
    probe_wipe(&key);
    return ((result < 0 && !(probe_stopping && result == -EINTR)) || closed) ? 1 : 0;
}
