/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_transport.h"
#include <errno.h>
#include <limits.h>
#include <string.h>

/* 数字和布局均与v3.6.2 arm64边界逐项比对；host测试不模拟Android符号。 */
_Static_assert(sizeof(struct galaxy_hab_open_request) == 16, "open ABI");
_Static_assert(sizeof(struct galaxy_hab_close_request) == 8, "close ABI");
_Static_assert(sizeof(struct galaxy_hab_data_request) == 24, "data ABI");
_Static_assert(offsetof(struct galaxy_hab_data_request, vcid) == 8, "vcid ABI");
_Static_assert(offsetof(struct galaxy_hab_data_request, flags) == 16, "flags ABI");
_Static_assert(sizeof(struct galaxy_hab_export_request) == 24, "export ABI");
_Static_assert(offsetof(struct galaxy_hab_export_request, export_id) == 16, "exportid ABI");
_Static_assert(sizeof(struct galaxy_hab_unexport_request) == 12, "unexport ABI");

void galaxy_hab_transport_init(struct galaxy_hab_transport *t, struct galaxy_hab_io io)
{
    memset(t, 0, sizeof(*t));
    t->io = io;
    t->fd = -1;
}

int galaxy_hab_transport_open(struct galaxy_hab_transport *t, uint32_t mmid,
                              uint32_t timeout_ms)
{
    struct galaxy_hab_open_request request = {0, mmid, timeout_ms, 0};
    int result;
    if (!t || !t->io.open_device || !t->io.control || !t->io.close_device ||
        !mmid || !timeout_ms) return -EINVAL;
    if (t->fd >= 0 || t->channel_open) return -EALREADY;
    result = t->io.open_device(t->io.context);
    if (result < 0) return result;
    t->fd = result;
    result = t->io.control(t->io.context, t->fd, GALAXY_HAB_VC_OPEN, &request);
    if (result != 0) {
        (void)t->io.close_device(t->io.context, t->fd);
        t->fd = -1;
        return result;
    }
    t->vcid = request.vcid;
    t->channel_open = 1;
    return 0;
}

int galaxy_hab_transport_export(struct galaxy_hab_transport *t, void *memory,
                                size_t size, uint32_t *export_id)
{
    struct galaxy_hab_export_request request;
    int result;
    if (!t || !t->channel_open) return -ENOTCONN;
    if (!memory || !export_id || !size || size > UINT32_MAX ||
        ((uintptr_t)memory & 4095u) || (size & 4095u)) return -EINVAL;
    if (t->memory_exported) return -EALREADY;
    memset(&request, 0, sizeof(request));
    request.address = (uint64_t)(uintptr_t)memory;
    request.vcid = t->vcid;
    request.size = (uint32_t)size;
    result = t->io.control(t->io.context, t->fd, GALAXY_HAB_EXPORT, &request);
    if (result != 0) return result;
    t->export_id = request.export_id;
    t->memory_exported = 1;
    *export_id = request.export_id;
    return 0;
}

int galaxy_hab_transport_send(struct galaxy_hab_transport *t,
                              const void *message, size_t size)
{
    struct galaxy_hab_data_request request;
    if (!t || !t->channel_open) return -ENOTCONN;
    if (!message || !size || size > UINT32_MAX) return -EINVAL;
    memset(&request, 0, sizeof(request));
    request.address = (uint64_t)(uintptr_t)message;
    request.vcid = t->vcid;
    request.size = (uint32_t)size;
    return t->io.control(t->io.context, t->fd, GALAXY_HAB_SEND, &request);
}

int galaxy_hab_transport_receive(struct galaxy_hab_transport *t, void *message,
                                 size_t capacity, uint32_t flags, size_t *received)
{
    struct galaxy_hab_data_request request;
    int result;
    if (!t || !t->channel_open) return -ENOTCONN;
    if (!message || !received || !capacity || capacity > UINT32_MAX) return -EINVAL;
    memset(&request, 0, sizeof(request));
    request.address = (uint64_t)(uintptr_t)message;
    request.vcid = t->vcid;
    request.size = (uint32_t)capacity;
    request.flags = flags;
    result = t->io.control(t->io.context, t->fd, GALAXY_HAB_RECV, &request);
    if (result != 0) return result;
    if (request.size > capacity) return -EMSGSIZE;
    *received = request.size;
    return 0;
}

int galaxy_hab_transport_close(struct galaxy_hab_transport *t)
{
    int first_error = 0, result;
    if (!t) return -EINVAL;
    if (t->fd < 0) return 0;
    if (t->memory_exported) {
        struct galaxy_hab_unexport_request request = {t->vcid, t->export_id, 0};
        result = t->io.control(t->io.context, t->fd, GALAXY_HAB_UNEXPORT, &request);
        if (result != 0) first_error = result;
        t->memory_exported = 0;
        t->export_id = 0;
    }
    if (t->channel_open) {
        struct galaxy_hab_close_request request = {t->vcid, 0};
        result = t->io.control(t->io.context, t->fd, GALAXY_HAB_VC_CLOSE, &request);
        if (!first_error && result != 0) first_error = result;
        t->channel_open = 0;
        t->vcid = 0;
    }
    result = t->io.close_device(t->io.context, t->fd);
    t->fd = -1;
    if (!first_error && result != 0) first_error = result;
    return first_error;
}
