/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "galaxy_hab_transport.h"
#if defined(__linux__)
#include <errno.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

/* 不复刻libuhab的进程全局fd：每个Galaxy会话独占设备fd和VC。 */
static int device_open(void *context)
{
    int fd;
    (void)context;
    fd = open("/dev/hab", O_RDWR | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
}
static int device_control(void *context, int fd, uint32_t request, void *argument)
{
    int result;
    (void)context;
    result = ioctl(fd, (unsigned long)request, argument);
    return result < 0 ? -errno : result;
}
static int device_close(void *context, int fd)
{
    int result;
    (void)context;
    result = close(fd);
    return result < 0 ? -errno : result;
}
struct galaxy_hab_io galaxy_hab_linux_io(void)
{
    struct galaxy_hab_io io = {NULL, device_open, device_control, device_close};
    return io;
}
#endif
