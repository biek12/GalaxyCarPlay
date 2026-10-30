/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_qnx.h"
#include "galaxy_hab_hmac.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

struct window {
    int live, visible, usage, format, sensitivity, size[2], z, posts;
    struct buffer { int stride, bytes; uint8_t pixels[128]; struct window *window; } buffers[2];
};
static struct {
    struct galaxy_hab_layout layout;
    struct galaxy_hab_hmac_key key;
    uint8_t memory[65536], packet[184];
    int pmem, channel, imported, mapped, context, windows, buffers, ack, posts;
    int calls, fail_call, receive_phase, bad_pointer, short_stride, short_capacity, wrong_format;
    int missing_display, fail_cleanup, close_count, unmap_count, unimport_count, requested_type;
    int trace_begin, trace_end, last_receive_timeout, receive_timeout_failure;
    int cancel_after, cancel_checks;
    struct window window[2];
} f;
static int handle_token, display_tokens[2], context_token;
static int cancellation_fixture(void *context)
{
    assert(context == &f);
    return ++f.cancel_checks >= f.cancel_after;
}
static void trace_fixture(void *context, const char *stage, int result)
{
    assert(context == &f && stage && *stage);
    if (result == (-2147483647 - 1)) ++f.trace_begin;
    else ++f.trace_end;
    errno = EACCES; /* 日志写入不得污染真正平台调用的errno。 */
}
static int call(void)
{
    ++f.calls;
    if (f.calls == f.fail_call) { errno = EIO; return -1; }
    return 0;
}
#define CALL() do { if (call()) return -1; } while (0)
int pmem_init(void) { CALL(); assert(!f.pmem); f.pmem = 1; return 0; }
int pmem_deinit(void)
{
    assert(f.pmem && !f.channel && !f.imported && !f.mapped && !f.context);
    f.pmem = 0; return f.fail_cleanup ? EIO : 0;
}
int habmm_socket_open(int *vc, unsigned mmid, unsigned timeout, unsigned flags)
{
    CALL(); assert(f.pmem && mmid == 0x580259 && timeout == 10000 && flags == 0);
    *vc = 733; f.channel = 1; return 0;
}
int habmm_socket_close(int vc)
{
    assert(vc == 733 && f.channel && !f.imported && !f.mapped && !f.context);
    f.channel = 0; ++f.close_count; return f.fail_cleanup ? EIO : 0;
}
int habmm_socket_recv(int vc, void *packet, unsigned *size, unsigned timeout, unsigned flags)
{
    CALL(); assert(vc == 733 && f.channel && !flags);
    f.last_receive_timeout = (int)timeout;
    if (f.receive_timeout_failure) { errno = ETIMEDOUT; return -1; }
    if (f.receive_phase == 0) {
        assert(*size == 184); memcpy(packet, f.packet, 184); *size = 184;
    } else if (f.receive_phase == 1) {
        assert(*size == 64);
        assert(galaxy_hab_encode_init(packet, &f.layout, 91, 99,
            galaxy_hab_sample_hash(f.memory, f.layout.total_bytes)) == 0); *size = 64;
    } else {
        assert(*size == 64 && f.ack); memcpy(packet, f.packet, 64); *size = 64;
    }
    ++f.receive_phase;
    return 0;
}
int habmm_socket_send(int vc, const void *packet, unsigned size, unsigned flags)
{
    CALL(); assert(vc == 733 && !flags && size == 64 && !f.ack);
    /* ACK绝不能早于窗口及可写buffer准备；只有一次握手ACK，没有帧ACK。 */
    assert(f.windows == (int)f.layout.count && f.buffers == (int)f.layout.count);
    for (uint32_t i = 0; i < f.layout.count; ++i) assert(!f.window[i].visible);
    assert(galaxy_hab_check_ack(packet, size) == 0); ++f.ack; return 0;
}
int habmm_import(int vc, void **handle, unsigned size, unsigned export_id, unsigned flags)
{
    CALL(); assert(vc == 733 && f.pmem && f.channel && !f.imported && export_id == 91);
    assert(size == f.layout.total_bytes && flags == 0x10000);
    *handle = &handle_token; f.imported = 1; return 0;
}
int habmm_unimport(int vc, unsigned export_id, void *handle, unsigned flags)
{
    assert(vc == 733 && export_id == 91 && handle == &handle_token && flags == 0x10000);
    assert(f.imported && !f.mapped && !f.context); f.imported = 0; ++f.unimport_count;
    return f.fail_cleanup ? EIO : 0;
}
void *pmem_map_handle(void *handle, unsigned *size)
{
    if (call()) return NULL;
    assert(handle == &handle_token && f.imported);
    f.mapped = 1; *size = f.layout.total_bytes; return f.memory;
}
int pmem_unmap_handle(void *handle, void *pointer)
{
    assert(handle == &handle_token && pointer == f.memory && f.mapped && !f.context);
    f.mapped = 0; ++f.unmap_count; return f.fail_cleanup ? EIO : 0;
}
int screen_create_context(screen_context_t *context, int type)
{
    CALL(); assert(type == 0 && f.mapped); f.context = 1; *context = &context_token; return 0;
}
int screen_flush_context(screen_context_t context, int flags)
{
    assert(context == &context_token && !flags); return f.fail_cleanup ? EIO : 0;
}
int screen_destroy_context(screen_context_t context)
{
    assert(context == &context_token && !f.windows); f.context = 0; return f.fail_cleanup ? EIO : 0;
}
int screen_get_context_property_iv(screen_context_t context, int property, int *value)
{
    CALL(); assert(context == &context_token && property == 59); *value = 2; return 0;
}
int screen_get_context_property_pv(screen_context_t context, int property, void **value)
{
    CALL(); assert(context == &context_token && property == 60);
    value[0] = &display_tokens[0]; value[1] = &display_tokens[1]; return 0;
}
int screen_get_display_property_iv(screen_display_t display, int property, int *value)
{
    CALL(); assert(property == 87);
    /* 故意让枚举下标与ID不同，防止把display2写成数组[2]。 */
    *value = display == &display_tokens[0] ? (f.missing_display ? 7 : 2) : 8; return 0;
}
int screen_create_window(screen_window_t *window, screen_context_t context)
{
    CALL(); assert(context == &context_token && f.windows < 2);
    memset(&f.window[f.windows], 0, sizeof(f.window[f.windows]));
    *window = &f.window[f.windows]; f.window[f.windows].live = 1; ++f.windows; return 0;
}
int screen_destroy_window(screen_window_t window)
{
    struct window *w = window;
    assert(w->live && !w->visible); w->live = 0; --f.windows;
    return f.fail_cleanup ? EIO : 0;
}
int screen_set_window_property_iv(screen_window_t window, int property, const int *value)
{
    struct window *w = window;
    if (property != 51 || *value != 0) { CALL(); }
    assert(w->live);
    switch (property) {
        case 51: w->visible = *value; break;
        case 48: assert(*value == 4); w->usage = *value; break; /* WRITE，READ=2不可写。 */
        case 14: assert(*value == 9); w->format = *value; break;
        case 57: assert(*value == 2); w->sensitivity = *value; break;
        case 5: case 40: w->size[0] = value[0]; w->size[1] = value[1]; break;
        case 54: w->z = *value; break;
        default: assert(0);
    }
    return 0;
}
int screen_set_window_property_pv(screen_window_t window, int property, void **value)
{
    CALL(); assert(((struct window *)window)->live && property == 11);
    assert(*value == &display_tokens[0] || *value == &display_tokens[1]); return 0;
}
int screen_create_window_buffers(screen_window_t window, int count)
{
    struct window *w = window;
    CALL(); assert(count == 2 && w->usage == 4 && w->format == 9 && w->sensitivity == 2);
    for (int i = 0; i < count; ++i) {
        w->buffers[i].window = w; w->buffers[i].stride = w->size[0]*4 + 8;
        w->buffers[i].bytes = w->buffers[i].stride*w->size[1];
        assert(w->buffers[i].bytes <= 128); memset(w->buffers[i].pixels, 0xa5, 128);
    }
    ++f.buffers; return 0;
}
int screen_get_window_property_iv(screen_window_t window, int property, int *value)
{
    CALL(); assert(((struct window *)window)->live && property == 53); *value = 2; return 0;
}
int screen_get_window_property_pv(screen_window_t window, int property, void **value)
{
    struct window *w = window;
    CALL(); assert(property == 37);
    value[0] = &w->buffers[w->posts % 2]; value[1] = &w->buffers[(w->posts + 1) % 2]; return 0;
}
int screen_get_buffer_property_iv(screen_buffer_t buffer, int property, int *value)
{
    struct buffer *b = buffer;
    CALL();
    switch (property) {
        case 44: *value = f.short_stride ? 1 : b->stride; break;
        case 40: *value = f.short_capacity ? 1 : b->bytes; break; /* SIZE=字节数，单int。 */
        case 5: value[0] = b->window->size[0]; value[1] = b->window->size[1]; break;
        case 14: *value = f.wrong_format ? 21 : b->window->format; break;
        case 48: *value = b->window->usage; break;
        default: assert(0);
    }
    return 0;
}
int screen_get_buffer_property_pv(screen_buffer_t buffer, int property, void **pointer)
{
    CALL(); assert(property == 34);
    *pointer = f.bad_pointer ? NULL : ((struct buffer *)buffer)->pixels; return 0;
}
int screen_post_window(screen_window_t window, screen_buffer_t buffer, int count, const int *rects, int flags)
{
    struct window *w = window;
    CALL(); assert(w->visible && count == 0 && !rects && !flags);
    assert(buffer == &w->buffers[w->posts % 2]); ++w->posts; ++f.posts; return 0;
}

static struct galaxy_hab_target targets[2] = {{2,3,2,4}, {8,2,1,1}};
static void fixture(struct galaxy_hab_qnx *q, uint32_t count)
{
    memset(&f, 0, sizeof(f));
    for (size_t i = 0; i < 32; ++i) f.key.bytes[i] = (uint8_t)i; /* 公开合成fixture。 */
    assert(galaxy_hab_layout_build(&f.layout, targets, count, 6) == 0);
    assert(f.layout.total_bytes <= sizeof(f.memory));
    assert(galaxy_hab_encode_auth(f.packet, &f.layout, 91, 99, 7, 1234, galaxy_hab_hmac_sign, &f.key) == 0);
    galaxy_hab_qnx_init(q, galaxy_hab_hmac_sign, &f.key);
    q->trace = trace_fixture;
    q->trace_context = &f;
}
static void released(const struct galaxy_hab_qnx *q)
{
    assert(!f.pmem && !f.channel && !f.imported && !f.mapped && !f.context && !f.windows);
    assert(!q->receiver.ready && !q->receiver.scratch && !q->pixels && !q->mapping && !q->import_handle);
}
static void frame(uint32_t sequence, uint32_t stream)
{
    struct galaxy_hab_stream_layout *s = &f.layout.streams[stream];
    uint8_t *p = f.memory + s->base + (sequence % s->slots)*s->slot_bytes;
    for (uint32_t i = 0; i < s->stride*s->target.height; i += 4) {
        p[i] = 17; p[i+1] = 45; p[i+2] = 212; p[i+3] = 255;
    }
    assert(galaxy_hab_encode_frame(f.packet, &f.layout, 91, sequence, stream,
        galaxy_hab_sample_hash(p, s->slot_bytes)) == 0);
}
int main(void)
{
    struct galaxy_hab_qnx q;
    int open_calls, frame_calls, unmap_count;
    fixture(&q, 2);
    assert(galaxy_hab_qnx_open(&q, targets, 2, 10000) == 0 && f.ack == 1);
    open_calls = f.calls;
    assert(galaxy_hab_qnx_open(&q, targets, 2, 10000) == -EALREADY);
    frame(1, 0);
    frame_calls = f.calls;
    assert(galaxy_hab_qnx_step(&q) == 0 && f.posts == 1);
    frame_calls = f.calls - frame_calls;
    assert(f.window[0].buffers[0].pixels[0] == 17 && f.window[0].buffers[0].pixels[2] == 212);
    assert(f.window[0].buffers[0].pixels[12] == 0xa5); /* row padding没有被误当像素。 */
    frame(2, 0);
    assert(galaxy_hab_qnx_step(&q) == 0 && f.window[0].buffers[1].pixels[0] == 17);
    frame(30, 1);
    assert(galaxy_hab_qnx_step(&q) == 0 && f.window[1].posts == 1 && f.ack == 1);
    assert(galaxy_hab_encode_control(f.packet, 5, 30, 91, 99, 0, 5, galaxy_hab_hmac_sign, &f.key) == 0);
    assert(galaxy_hab_qnx_step(&q) == 0 && f.window[0].z == 5);
    assert(galaxy_hab_encode_control(f.packet, 3, 30, 91, 99, 0, 0, galaxy_hab_hmac_sign, &f.key) == 0);
    assert(galaxy_hab_qnx_step(&q) == 1);
    released(&q); unmap_count = f.unmap_count;
    assert(f.trace_begin > 0 && f.trace_begin == f.trace_end);
    assert(galaxy_hab_qnx_close(&q) == 0 && f.unmap_count == unmap_count);
    assert(galaxy_hab_qnx_step(&q) == -ENOTCONN);

    fixture(&q, 1);
    assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == 0);
    frame(1, 0);
    assert(galaxy_hab_qnx_step_timeout(&q, 5000) == 0 && f.last_receive_timeout == 5000);
    f.receive_timeout_failure = 1;
    assert(galaxy_hab_qnx_step_timeout(&q, 5000) == -ETIMEDOUT);
    released(&q); // APK异常死亡/失联后必须隐藏并销毁自有窗口。

    /* 停止请求在阻塞open、AUTH接收、窗口准备阶段到达：旧owner不能再ACK。
     * 尤其不能把一次被取消的listen伪装成ready后立即close。 */
    for (int stage = 1; stage <= 3; ++stage) {
        fixture(&q, 1);
        f.cancel_after = stage;
        q.cancelled = cancellation_fixture;
        q.cancel_context = &f;
        assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == -EINTR);
        assert(!f.ack);
        released(&q);
    }

    /* 每个open平台调用分别拒绝，保证无提前ACK、无资源遗留，并能重新建立会话。 */
    for (int i = 1; i <= open_calls; ++i) {
        fixture(&q, 2); f.fail_call = i;
        assert(galaxy_hab_qnx_open(&q, targets, 2, 10000) < 0 && !f.ack);
        released(&q);
    }
    for (int i = 1; i <= frame_calls; ++i) {
        fixture(&q, 2); assert(galaxy_hab_qnx_open(&q, targets, 2, 10000) == 0);
        frame(1, 0); f.fail_call = f.calls + i;
        assert(galaxy_hab_qnx_step(&q) < 0 && !f.posts); released(&q);
    }
    for (int problem = 0; problem < 5; ++problem) {
        fixture(&q, 1);
        f.bad_pointer = problem == 0; f.short_stride = problem == 1;
        f.short_capacity = problem == 2; f.wrong_format = problem == 3; f.missing_display = problem == 4;
        assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) < 0 && !f.ack);
        released(&q);
    }
    fixture(&q, 1); f.packet[152] ^= 1;
    assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == -EACCES && !f.imported); released(&q);
    fixture(&q, 1);
    { struct galaxy_hab_target wrong = targets[0]; wrong.display_id = 1;
      assert(galaxy_hab_qnx_open(&q, &wrong, 1, 10000) == -EPROTO && !f.ack); released(&q); }
    fixture(&q, 1); assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == 0);
    frame(1, 0); f.bad_pointer = 1;
    assert(galaxy_hab_qnx_step(&q) == -EFAULT && !f.posts); released(&q);
    fixture(&q, 1); assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == 0);
    f.fail_cleanup = 1;
    assert(galaxy_hab_qnx_close(&q) == -EIO); released(&q);
    assert(f.close_count == 1 && f.unmap_count == 1 && f.unimport_count == 1);
    f.fail_cleanup = 0;
    f.receive_phase = 0; f.ack = 0; f.buffers = 0;
    assert(galaxy_hab_qnx_open(&q, targets, 1, 10000) == 0);
    assert(galaxy_hab_qnx_close(&q) == 0); released(&q);
    puts("HAB QNX import/Screen lifecycle, writable buffer, padded BGRA and failure contracts passed; host fake APIs only");
    return 0;
}
