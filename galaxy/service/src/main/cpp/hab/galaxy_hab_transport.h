/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_TRANSPORT_H
#define GALAXY_HAB_TRANSPORT_H

#include <stddef.h>
#include <stdint.h>

/* 按样本 arm64 ioctl 边界重新声明，不依赖或加载 libuhab/libautodisplaycamera。 */
#define GALAXY_HAB_VC_OPEN  UINT32_C(0xc0100a04)
#define GALAXY_HAB_VC_CLOSE UINT32_C(0x40080a05)
#define GALAXY_HAB_SEND     UINT32_C(0x40180a02)
#define GALAXY_HAB_RECV     UINT32_C(0xc0180a03)
#define GALAXY_HAB_EXPORT   UINT32_C(0xc0180a06)
#define GALAXY_HAB_UNEXPORT UINT32_C(0x400c0a08)

struct galaxy_hab_open_request {
    int32_t vcid;
    uint32_t mmid, timeout_ms, flags;
};
struct galaxy_hab_close_request { int32_t vcid; uint32_t flags; };
struct galaxy_hab_data_request {
    uint64_t address;
    int32_t vcid;
    uint32_t size, flags, reserved;
};
struct galaxy_hab_export_request {
    uint64_t address;
    int32_t vcid;
    uint32_t size, export_id, flags;
};
struct galaxy_hab_unexport_request { int32_t vcid; uint32_t export_id, flags; };

/* 所有函数返回0/非负成功值或负errno；测试替换I/O，不访问真实/dev/hab。 */
struct galaxy_hab_io {
    void *context;
    int (*open_device)(void *context);
    int (*control)(void *context, int fd, uint32_t request, void *argument);
    int (*close_device)(void *context, int fd);
};

/* 单会话、单共享区；调用方串行使用，并在close后才释放共享区。 */
struct galaxy_hab_transport {
    struct galaxy_hab_io io;
    int fd;
    int32_t vcid;
    uint32_t export_id;
    int channel_open, memory_exported;
};

void galaxy_hab_transport_init(struct galaxy_hab_transport *transport,
                               struct galaxy_hab_io io);
int galaxy_hab_transport_open(struct galaxy_hab_transport *transport,
                              uint32_t mmid, uint32_t timeout_ms);
int galaxy_hab_transport_export(struct galaxy_hab_transport *transport,
                                void *aligned_memory, size_t size,
                                uint32_t *export_id);
int galaxy_hab_transport_send(struct galaxy_hab_transport *transport,
                              const void *message, size_t size);
/* 不虚构timeout字段：样本的RECV ioctl没有超时参数，等待策略由上层决定。 */
int galaxy_hab_transport_receive(struct galaxy_hab_transport *transport,
                                 void *message, size_t capacity, uint32_t flags,
                                 size_t *received);
int galaxy_hab_transport_close(struct galaxy_hab_transport *transport);

/* 仅Linux/Android提供，取得函数表不会打开设备；无构造器或自动启动。 */
#if defined(__linux__)
struct galaxy_hab_io galaxy_hab_linux_io(void);
#endif
#endif
