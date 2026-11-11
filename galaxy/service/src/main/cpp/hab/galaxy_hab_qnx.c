/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef _QNX_SOURCE
#define _QNX_SOURCE 1
#endif
#include "galaxy_hab_qnx.h"
#include "galaxy_hab_owned.h"
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>

/* 目标libuhab-qvmhost: import VA0x2ee4 / unimport VA0x3580及现场配套释放记录。
 * flags=0x10000返回PMEM opaque handle，不能按Linux fd关闭或把handle当映射地址。
 * 只链接目标系统库，不携带OEM二进制。Screen声明使用QNX SDK，不使用Android libc启动。
 */
extern int habmm_socket_open(int *, unsigned, unsigned, unsigned);
extern int habmm_socket_recv(int, void *, unsigned *, unsigned, unsigned);
extern int habmm_socket_send(int, const void *, unsigned, unsigned);
extern int habmm_socket_close(int);
extern int habmm_import(int, void **, unsigned, unsigned, unsigned);
extern int habmm_unimport(int, unsigned, void *, unsigned);
extern int pmem_init(void);
extern int pmem_deinit(void);
extern void *pmem_map_handle(void *, unsigned *);
extern int pmem_unmap_handle(void *, void *);

#define PMEM_HANDLE_IMPORT 0x10000u
#define MAX_DISPLAYS 16
#define WINDOW_BUFFERS 2

/* HAB/PMEM有正错误码；Screen是-1+errno，统一成负错误并保留成功0。 */
static int platform_result(int result)
{
    if (result == -1) return errno ? -errno : -EIO;
    return result > 0 ? -result : result;
}
static uint32_t word(const uint8_t *p)
{
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static void first_error(int *first, int result)
{
    if (!*first) *first = result;
}
static void trace(struct galaxy_hab_qnx *q, const char *stage, int result)
{
    if (q->trace) {
        int saved = errno;
        q->trace(q->trace_context, stage, result);
        errno = saved;
    }
}
static int cancellation(struct galaxy_hab_qnx *q)
{
    return q->cancelled && q->cancelled(q->cancel_context) ? -EINTR : 0;
}

/* HAB消息到达只给出发布顺序，不替导入映射做CPU cache维护。目标PMEM库在
 * 0x59b0/0x6d04使用同一CACHE_ONLY|INVALIDATE组合；这里只操作本次import。
 * 必须先失效再复制私有快照，不能把DMB或重算hash冒充跨VM数据可见性。
 */
static int synchronize_import(struct galaxy_hab_qnx *q, size_t offset, size_t bytes)
{
    int result;
    if (!q->mapping || offset > q->receiver.layout.total_bytes ||
        !bytes || bytes > q->receiver.layout.total_bytes - offset) return -EINVAL;
    result = cancellation(q);
    if (result) return result;
    trace(q, "msync imported frame cache", INT_MIN);
    result = platform_result(msync((uint8_t *)q->mapping + offset, bytes,
                                  MS_CACHE_ONLY | MS_INVALIDATE));
    trace(q, "msync imported frame cache", result);
    ++q->cache_syncs;
    q->cache_last_error = result;
    if (result) ++q->cache_failures;
    return result;
}

void galaxy_hab_qnx_init(struct galaxy_hab_qnx *q, galaxy_hab_sign sign, void *context)
{
    memset(q, 0, sizeof(*q));
    galaxy_hab_receiver_init(&q->receiver, sign, context);
}

int galaxy_hab_qnx_close(struct galaxy_hab_qnx *q)
{
    int result = 0, hidden = 0;
    uint32_t i;
    if (!q) return -EINVAL;
#define RELEASE(call) do { \
    int code; trace(q, #call, INT_MIN); code = platform_result(call); \
    trace(q, #call, code); first_error(&result, code); \
} while (0)
    if (q->consumer.close) q->consumer.close(q->consumer.context);
    for (i = 0; i < 2; ++i)
        if (q->windows[i]) RELEASE(screen_set_window_property_iv(q->windows[i], SCREEN_PROPERTY_VISIBLE, &hidden));
    if (q->context) RELEASE(screen_flush_context(q->context, 0));
    for (i = 0; i < 2; ++i) {
        if (q->windows[i]) RELEASE(screen_destroy_window(q->windows[i]));
        q->windows[i] = NULL;
    }
    if (q->context) RELEASE(screen_destroy_context(q->context));
    q->context = NULL;
    galaxy_hab_receiver_close(&q->receiver);
    if (q->mapping) RELEASE(pmem_unmap_handle(q->import_handle, q->mapping));
    q->mapping = NULL;
    if (q->imported) RELEASE(habmm_unimport(q->vcid, q->export_id, q->import_handle, PMEM_HANDLE_IMPORT));
    q->imported = 0;
    q->import_handle = NULL;
    q->export_id = 0;
    if (q->channel_open) RELEASE(habmm_socket_close(q->vcid));
    q->channel_open = 0;
    q->vcid = 0;
    if (q->pmem_active) RELEASE(pmem_deinit());
    q->pmem_active = 0;
    return result;
#undef RELEASE
}

/* 每帧重新查询当前可写buffer，不能缓存上一帧已交给Screen的buffer/pointer。
 * SIZE对buffer是单个字节数；BUFFER_SIZE是两个像素维度，二者不能混用。
 */
static int writable_buffer(struct galaxy_hab_qnx *q, screen_window_t window, const struct galaxy_hab_stream_layout *stream,
                          screen_buffer_t *buffer, uint8_t **pixels, int *stride)
{
    screen_buffer_t buffers[WINDOW_BUFFERS] = {NULL, NULL};
    int count = 0, bytes = 0, format = 0, usage = 0, size[2] = {0, 0}, result;
    void *pointer = NULL;
#define SCREEN_CHECK(call) do { \
    trace(q, #call, INT_MIN); result = platform_result(call); trace(q, #call, result); \
    if (result) return result; \
} while (0)
    SCREEN_CHECK(screen_get_window_property_iv(window, SCREEN_PROPERTY_RENDER_BUFFER_COUNT, &count));
    if (count < 1 || count > WINDOW_BUFFERS) return -EPROTO;
    SCREEN_CHECK(screen_get_window_property_pv(window, SCREEN_PROPERTY_RENDER_BUFFERS, (void **)buffers));
    if (!buffers[0]) return -EFAULT;
    SCREEN_CHECK(screen_get_buffer_property_iv(buffers[0], SCREEN_PROPERTY_STRIDE, stride));
    SCREEN_CHECK(screen_get_buffer_property_iv(buffers[0], SCREEN_PROPERTY_SIZE, &bytes));
    SCREEN_CHECK(screen_get_buffer_property_iv(buffers[0], SCREEN_PROPERTY_BUFFER_SIZE, size));
    SCREEN_CHECK(screen_get_buffer_property_iv(buffers[0], SCREEN_PROPERTY_FORMAT, &format));
    SCREEN_CHECK(screen_get_buffer_property_iv(buffers[0], SCREEN_PROPERTY_USAGE, &usage));
    if (*stride <= 0 || bytes <= 0 || size[0] < (int)stream->target.width ||
        size[1] < (int)stream->target.height || (uint32_t)*stride < stream->stride ||
        (uint64_t)*stride * stream->target.height > (uint32_t)bytes ||
        format != SCREEN_FORMAT_RGBX8888 || !(usage & SCREEN_USAGE_WRITE)) return -EPROTO;
    SCREEN_CHECK(screen_get_buffer_property_pv(buffers[0], SCREEN_PROPERTY_POINTER, &pointer));
    if (!pointer || pointer == (void *)(intptr_t)-1) return -EFAULT;
    *buffer = buffers[0];
    *pixels = pointer;
    return 0;
#undef SCREEN_CHECK
}

static int prepare_windows(struct galaxy_hab_qnx *q)
{
    screen_display_t displays[MAX_DISPLAYS] = {NULL};
    int count = 0, i, result;
    uint32_t stream;
#define PREPARE(call) do { \
    trace(q, #call, INT_MIN); result = platform_result(call); trace(q, #call, result); \
    if (result) return result; \
} while (0)
    PREPARE(screen_create_context(&q->context, SCREEN_APPLICATION_CONTEXT));
    PREPARE(screen_get_context_property_iv(q->context, SCREEN_PROPERTY_DISPLAY_COUNT, &count));
    if (count < 1 || count > MAX_DISPLAYS) return -EPROTO;
    PREPARE(screen_get_context_property_pv(q->context, SCREEN_PROPERTY_DISPLAYS, (void **)displays));
    for (stream = 0; stream < q->receiver.layout.count; ++stream) {
        const struct galaxy_hab_stream_layout *s = &q->receiver.layout.streams[stream];
        screen_display_t display = NULL;
        screen_buffer_t buffer = NULL;
        uint8_t *pointer;
        int id, stride, size[2] = {(int)s->target.width, (int)s->target.height};
        int hidden = 0, usage = SCREEN_USAGE_WRITE, format = SCREEN_FORMAT_RGBX8888;
        int sensitivity = SCREEN_SENSITIVITY_NEVER, z = s->target.z_order;
        for (i = 0; i < count; ++i) {
            PREPARE(screen_get_display_property_iv(displays[i], SCREEN_PROPERTY_ID, &id));
            if (id == s->target.display_id) display = displays[i];
        }
        if (!display) return -ENODEV;
        PREPARE(screen_create_window(&q->windows[stream], q->context));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_VISIBLE, &hidden));
        PREPARE(screen_set_window_property_pv(q->windows[stream], SCREEN_PROPERTY_DISPLAY, (void **)&display));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_SENSITIVITY, &sensitivity));
        /* QNX RGBX8888以高位R低位B解释32位像素；aarch64le内存为B,G,R,X，匹配HAB BGRA。 */
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_USAGE, &usage));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_FORMAT, &format));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_BUFFER_SIZE, size));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_SIZE, size));
        PREPARE(screen_set_window_property_iv(q->windows[stream], SCREEN_PROPERTY_ZORDER, &z));
        PREPARE(screen_create_window_buffers(q->windows[stream], WINDOW_BUFFERS));
        result = writable_buffer(q, q->windows[stream], s, &buffer, &pointer, &stride);
        if (result) return result;
    }
    return 0;
#undef PREPARE
}

int galaxy_hab_qnx_open(struct galaxy_hab_qnx *q, const struct galaxy_hab_target *targets,
                          uint32_t count, uint32_t timeout)
{
    uint8_t packet[GALAXY_HAB_AUTH_SIZE], ack[GALAXY_HAB_PACKET_SIZE];
    unsigned length, mapped = 0;
    uint32_t i;
    int result;
    if (!q || !targets || count < 1 || count > 2 || !timeout) return -EINVAL;
    if (q->pmem_active || q->channel_open || q->receiver.authenticated) return -EALREADY;
    if (!q->receiver.sign) return -EACCES;
    if (q->consumer.prepare && (!q->consumer.acquire || !q->consumer.present || !q->consumer.close)) return -EINVAL;
    for (i = 0; i < count; ++i) {
        if (targets[i].display_id < 0 || !targets[i].width || !targets[i].height ||
            targets[i].width > INT_MAX / 4 || targets[i].height > INT_MAX) return -EINVAL;
        if (i && targets[0].display_id == targets[i].display_id) return -EINVAL;
    }
#define OPEN(call) do { \
    trace(q, #call, INT_MIN); result = platform_result(call); trace(q, #call, result); \
    if (result) goto failed; \
} while (0)
    OPEN(pmem_init());
    q->pmem_active = 1;
    OPEN(habmm_socket_open(&q->vcid, GALAXY_HAB_MMID, timeout, 0));
    q->channel_open = 1;
    result = cancellation(q);
    if (result) goto failed;
    length = sizeof(packet);
    OPEN(habmm_socket_recv(q->vcid, packet, &length, timeout, 0));
    result = cancellation(q);
    if (result) goto failed;
    result = galaxy_hab_receiver_auth(&q->receiver, packet, length);
    if (result) goto failed;
    if (q->receiver.layout.count != count) { result = -EPROTO; goto failed; }
    for (i = 0; i < count; ++i) {
        const struct galaxy_hab_target *received = &q->receiver.layout.streams[i].target;
        if (received->display_id != targets[i].display_id || received->width != targets[i].width ||
            received->height != targets[i].height || received->z_order != targets[i].z_order) {
            result = -EPROTO; goto failed;
        }
    }
    q->export_id = q->receiver.export_id;
    OPEN(habmm_import(q->vcid, &q->import_handle, q->receiver.layout.total_bytes,
                     q->export_id, PMEM_HANDLE_IMPORT));
    q->imported = 1;
    trace(q, "pmem_map_handle", INT_MIN);
    q->mapping = pmem_map_handle(q->import_handle, &mapped);
    trace(q, "pmem_map_handle", q->mapping && q->mapping != (void *)(intptr_t)-1 ? 0 : -EFAULT);
    if (q->mapping == (void *)(intptr_t)-1) q->mapping = NULL;
    if (!q->mapping || mapped != q->receiver.layout.total_bytes) { result = -EFAULT; goto failed; }
    length = GALAXY_HAB_PACKET_SIZE;
    OPEN(habmm_socket_recv(q->vcid, packet, &length, timeout, 0));
    result = synchronize_import(q, 0, mapped);
    if (result) goto failed;
    result = galaxy_hab_receiver_start(&q->receiver, packet, length, q->mapping, mapped, ack);
    if (result) goto failed;
    result = q->consumer.prepare ? q->consumer.prepare(q->consumer.context, &q->receiver.layout) : prepare_windows(q);
    if (result) goto failed;
    result = cancellation(q);
    if (result) goto failed;
    OPEN(habmm_socket_send(q->vcid, ack, sizeof(ack), 0));
    return 0;
failed:
    (void)galaxy_hab_qnx_close(q);
    return result;
#undef OPEN
}

int galaxy_hab_qnx_step(struct galaxy_hab_qnx *q)
{
    return galaxy_hab_qnx_step_timeout(q, 0);
}

int galaxy_hab_qnx_step_timeout(struct galaxy_hab_qnx *q, uint32_t timeout_ms)
{
    uint8_t packet[GALAXY_HAB_PACKET_SIZE];
    unsigned length = sizeof(packet);
    int result;
    if (!q || !q->channel_open || !q->receiver.ready) return -ENOTCONN;
    trace(q, "habmm_socket_recv frame/control", INT_MIN);
    result = platform_result(habmm_socket_recv(q->vcid, packet, &length, timeout_ms, 0));
    trace(q, "habmm_socket_recv frame/control", result);
    if (result) goto failed;
    if (length != sizeof(packet)) {
        trace(q, "frame/control size", INT_MIN);
        trace(q, "frame/control size", (int)length);
        result = -EPROTO; goto failed;
    }
    if (word(packet) == GALAXY_HAB_OWNED_MAGIC) {
        uint8_t reply[GALAXY_HAB_PACKET_SIZE];
        result = galaxy_hab_receiver_enable_consumption(&q->receiver, packet, length, reply);
        if (!result) result = platform_result(habmm_socket_send(q->vcid, reply, sizeof(reply), 0));
        if (result) goto failed;
    } else if (word(packet + 8) == 2) {
        struct galaxy_hab_frame_info info;
        screen_buffer_t buffer = NULL;
        uint8_t *destination;
        int stride, visible = 1;
        size_t capacity; uint32_t consumer_stride;
        uint32_t stream = word(packet + 0x3c);
        if (stream >= q->receiver.layout.count) { result = -EPROTO; goto failed; }
        const struct galaxy_hab_stream_layout *layout = &q->receiver.layout.streams[stream];
        size_t offset = layout->base + (size_t)(word(packet + 0x0c) % layout->slots) * layout->slot_bytes;
        result = synchronize_import(q, offset, layout->slot_bytes);
        if (result) goto failed;
        if (q->consumer.prepare) {
            if (!q->consumer.acquire || !q->consumer.present) { result = -EINVAL; goto failed; }
            result = q->consumer.acquire(q->consumer.context, stream, &destination, &capacity, &consumer_stride);
            if (result) goto failed;
            if (consumer_stride > INT_MAX) { result = -EINVAL; goto failed; }
            stride = (int)consumer_stride;
        } else {
            result = writable_buffer(q, q->windows[stream], layout, &buffer, &destination, &stride);
            if (result) goto failed;
            capacity = (size_t)(unsigned)stride * layout->target.height;
        }
        trace(q, "galaxy_hab_receiver_frame", INT_MIN);
        result = galaxy_hab_receiver_frame_strided(&q->receiver, packet, length,
            destination, capacity,
            (unsigned)stride, &info);
        trace(q, "galaxy_hab_receiver_frame", result);
        if (result == -EBADMSG && q->receiver.owned_consumption) {
            uint8_t reply[GALAXY_HAB_PACKET_SIZE];
            uint32_t sequence = word(packet + 0x0c);
            ++q->checksum_mismatches;
            q->checksum_expected = q->receiver.expected_hash;
            q->checksum_observed = q->receiver.observed_hash;
            if (q->checksum_sequence == sequence && q->checksum_stream == stream) ++q->checksum_attempts;
            else { q->checksum_sequence = sequence; q->checksum_stream = stream; q->checksum_attempts = 1; }
            /* 保留最后一张已验证画面；只有同会话HMAC绑定的RETRY允许sender重写同一slot。
             * 不跳过hash、不对坏帧发POSTED。两次仍失败按原有释放路径退出。 */
            if (q->checksum_attempts <= 2) {
                result = galaxy_hab_owned_encode(reply, GALAXY_HAB_OWNED_RETRY, sequence,
                    q->receiver.export_id, stream, q->receiver.session, q->receiver.sign, q->receiver.sign_context);
                if (!result) result = platform_result(habmm_socket_send(q->vcid, reply, sizeof(reply), 0));
                if (!result) return 0;
            }
        }
        if (result) goto failed;
        q->checksum_attempts = 0;
        if (q->consumer.prepare) {
            result = q->consumer.present(q->consumer.context, &info);
            if (result) goto failed;
            ++q->handed_frames;
        } else {
            result = platform_result(screen_set_window_property_iv(q->windows[info.stream], SCREEN_PROPERTY_VISIBLE, &visible));
            if (!result) {
                trace(q,"screen_post_window",INT_MIN);
                result = platform_result(screen_post_window(q->windows[info.stream], buffer, 0, NULL, 0));
                trace(q,"screen_post_window",result);
            }
            if (result) goto failed;
            ++q->posted_frames;
        }
        if (q->receiver.owned_consumption) {
            uint8_t reply[GALAXY_HAB_PACKET_SIZE];
            result = galaxy_hab_owned_encode(reply, GALAXY_HAB_OWNED_POSTED,
                info.sequence, q->receiver.export_id, info.stream, q->receiver.session,
                q->receiver.sign, q->receiver.sign_context);
            if (!result) result = platform_result(habmm_socket_send(q->vcid, reply, sizeof(reply), 0));
            if (result) goto failed;
        }
    } else {
        struct galaxy_hab_control_event event;
        result = galaxy_hab_receiver_control(&q->receiver, packet, length, &event);
        if (result) goto failed;
        if (event.type == 3) {
            result = galaxy_hab_qnx_close(q);
            return result ? result : 1;
        }
        /* 协议保留未知/负stream语义；窗口端只处理本会话的明确stream。 */
        if (event.stream < 0 || (uint32_t)event.stream >= q->receiver.layout.count) {
            result = -EINVAL; goto failed;
        }
        if (q->consumer.prepare) return 0; /* no window/z-order in texture mode */
        result = platform_result(screen_set_window_property_iv(q->windows[event.stream],
                                                               SCREEN_PROPERTY_ZORDER, &event.z_order));
        if (!result) result = platform_result(screen_flush_context(q->context, 0));
        if (result) goto failed;
    }
    return 0;
failed:
    (void)galaxy_hab_qnx_close(q);
    return result;
}
