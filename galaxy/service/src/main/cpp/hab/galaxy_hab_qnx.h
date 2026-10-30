/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_QNX_H
#define GALAXY_HAB_QNX_H
#include "galaxy_hab_receiver.h"
#include <screen/screen.h>

/* QNX平台owner，与Android ioctl/fd后端分开编译。串行调用，不自动重连或接管原车窗口。
 * targets必须由已确认车型配置显式传入，display_id为QNX SCREEN_PROPERTY_ID，非Android屏号。
 * signer由外部提供；不内置样本密钥。open/step的recv可能阻塞，不能宣称有帧超时保证。
 */
struct galaxy_hab_qnx {
    struct galaxy_hab_receiver receiver;
    int vcid, channel_open, pmem_active, imported;
    void *import_handle, *mapping;
    uint32_t export_id;
    screen_context_t context;
    screen_window_t windows[2];
    uint8_t *pixels;
    size_t pixel_capacity;
    /* 可选本地诊断：stage是静态操作名；INT_MIN表示调用前，其余为返回值，不含载荷/指针。 */
    void (*trace)(void *context, const char *stage, int result);
    void *trace_context;
    /* 由会话owner提供；阻塞open/recv返回后、尤其ACK之前再次核对停止请求。 */
    int (*cancelled)(void *context);
    void *cancel_context;
};

void galaxy_hab_qnx_init(struct galaxy_hab_qnx *owner,
                          galaxy_hab_sign sign, void *sign_context);
/* AUTH→受限车型配置核对→PMEM import/map→INIT→隐藏窗口/可写buffer准备→ACK。
 * 任何阶段失败均清理本次已取得的资源，保留主错误。不能通过ACK掩盖显示端未准备好。
 */
int galaxy_hab_qnx_open(struct galaxy_hab_qnx *owner,
                          const struct galaxy_hab_target *targets, uint32_t count,
                          uint32_t open_timeout_ms);
/* 0=已提交FRAME或ZORDER，1=STOP并已释放，负值=失败并已释放；没有逐帧ACK。 */
int galaxy_hab_qnx_step(struct galaxy_hab_qnx *owner);
/* 自有长期会话使用有限空闲期；非零timeout沿已核验habmm_socket_recv参数传递。 */
int galaxy_hab_qnx_step_timeout(struct galaxy_hab_qnx *owner, uint32_t timeout_ms);
/* hide/flush/destroy自己的窗口→协议scratch→unmap→unimport→VC close→PMEM deinit。
 * 退出不更改原车窗口层级；清理失败仍继续其他释放，返回首个错误；重复close幂等。
 */
int galaxy_hab_qnx_close(struct galaxy_hab_qnx *owner);
#endif
