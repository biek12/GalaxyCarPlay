/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_transport.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

struct fake {
    uint32_t commands[16], fail_command;
    int count, opens, closes, open_error, close_error;
    size_t receive_size;
};
static int fake_open(void *context)
{
    struct fake *f = context;
    ++f->opens;
    return f->open_error ? f->open_error : 7;
}
static int fake_close(void *context, int fd)
{
    struct fake *f = context;
    assert(fd == 7);
    ++f->closes;
    return f->close_error;
}
static int fake_control(void *context, int fd, uint32_t command, void *data)
{
    struct fake *f = context;
    assert(fd == 7);
    assert(f->count < 16);
    f->commands[f->count++] = command;
    if (command == f->fail_command) return -EIO;
    switch (command) {
        case GALAXY_HAB_VC_OPEN: {
            struct galaxy_hab_open_request *q = data;
            assert(q->vcid == 0 && q->mmid == 0x580259 && q->timeout_ms == 10000 && q->flags == 0);
            q->vcid = 42;
            break;
        }
        case GALAXY_HAB_EXPORT: {
            struct galaxy_hab_export_request *q = data;
            assert(q->vcid == 42 && q->size == 4096 && q->export_id == 0 && q->flags == 0);
            assert((q->address & 4095u) == 0);
            q->export_id = 91;
            break;
        }
        case GALAXY_HAB_SEND: {
            struct galaxy_hab_data_request *q = data;
            assert(q->vcid == 42 && q->size == 64 && !q->flags && !q->reserved);
            assert(((unsigned char *)(uintptr_t)q->address)[63] == 0xa5);
            break;
        }
        case GALAXY_HAB_RECV: {
            struct galaxy_hab_data_request *q = data;
            assert(q->vcid == 42 && q->size == 64 && q->flags == 0 && q->reserved == 0);
            q->size = (uint32_t)f->receive_size;
            break;
        }
        case GALAXY_HAB_UNEXPORT: {
            struct galaxy_hab_unexport_request *q = data;
            assert(q->vcid == 42 && q->export_id == 91 && q->flags == 0);
            break;
        }
        case GALAXY_HAB_VC_CLOSE: {
            struct galaxy_hab_close_request *q = data;
            assert(q->vcid == 42 && q->flags == 0);
            break;
        }
        default: assert(0);
    }
    return 0;
}
static void init(struct galaxy_hab_transport *t, struct fake *f)
{
    struct galaxy_hab_io io = {f, fake_open, fake_control, fake_close};
    memset(f, 0, sizeof(*f));
    f->receive_size = 64;
    galaxy_hab_transport_init(t, io);
}
static void open_transport(struct galaxy_hab_transport *t)
{
    assert(galaxy_hab_transport_open(t, 0x580259, 10000) == 0);
}
int main(void)
{
    struct galaxy_hab_transport t;
    struct fake f;
    _Alignas(4096) unsigned char memory[4096];
    unsigned char message[64] = {0};
    uint32_t export_id = 999;
    size_t received = 999;
    message[63] = 0xa5;

    init(&t, &f);
    assert(galaxy_hab_transport_send(&t, message, 64) == -ENOTCONN);
    assert(galaxy_hab_transport_close(&t) == 0 && f.closes == 0);
    open_transport(&t);
    assert(galaxy_hab_transport_open(&t, 0x580259, 10000) == -EALREADY);
    assert(f.opens == 1 && f.count == 1);
    assert(galaxy_hab_transport_export(&t, memory + 1, 4096, &export_id) == -EINVAL);
    assert(galaxy_hab_transport_export(&t, memory, 4095, &export_id) == -EINVAL);
    assert(export_id == 999 && f.count == 1);
    assert(galaxy_hab_transport_export(&t, memory, 4096, &export_id) == 0 && export_id == 91);
    assert(galaxy_hab_transport_export(&t, memory, 4096, &export_id) == -EALREADY);
    assert(galaxy_hab_transport_send(&t, NULL, 64) == -EINVAL);
    assert(galaxy_hab_transport_send(&t, message, 0) == -EINVAL);
    assert(galaxy_hab_transport_send(&t, message, 64) == 0);
    assert(galaxy_hab_transport_receive(&t, message, 64, 0, &received) == 0 && received == 64);
    assert(galaxy_hab_transport_close(&t) == 0);
    assert(f.commands[4] == GALAXY_HAB_UNEXPORT && f.commands[5] == GALAXY_HAB_VC_CLOSE);
    assert(f.closes == 1 && !t.channel_open && !t.memory_exported && t.fd == -1);
    assert(galaxy_hab_transport_close(&t) == 0 && f.closes == 1);

    init(&t, &f);
    f.open_error = -EACCES;
    assert(galaxy_hab_transport_open(&t, 0x580259, 10000) == -EACCES);
    assert(t.fd == -1 && f.count == 0 && f.closes == 0);
    init(&t, &f);
    f.fail_command = GALAXY_HAB_VC_OPEN;
    assert(galaxy_hab_transport_open(&t, 0x580259, 10000) == -EIO);
    assert(t.fd == -1 && !t.channel_open && f.closes == 1);

    init(&t, &f);
    open_transport(&t);
    f.fail_command = GALAXY_HAB_EXPORT;
    export_id = 999;
    assert(galaxy_hab_transport_export(&t, memory, 4096, &export_id) == -EIO);
    assert(!t.memory_exported && export_id == 999);
    assert(galaxy_hab_transport_close(&t) == 0 && f.count == 3);
    assert(f.commands[2] == GALAXY_HAB_VC_CLOSE);

    init(&t, &f);
    open_transport(&t);
    f.receive_size = 65;
    received = 999;
    assert(galaxy_hab_transport_receive(&t, message, 64, 0, &received) == -EMSGSIZE);
    assert(received == 999);
    f.fail_command = GALAXY_HAB_RECV;
    assert(galaxy_hab_transport_receive(&t, message, 64, 0, &received) == -EIO && received == 999);
    assert(galaxy_hab_transport_close(&t) == 0);

    init(&t, &f);
    open_transport(&t);
    assert(galaxy_hab_transport_export(&t, memory, 4096, &export_id) == 0);
    f.fail_command = GALAXY_HAB_UNEXPORT;
    f.close_error = -EINTR;
    assert(galaxy_hab_transport_close(&t) == -EIO);
    assert(f.commands[3] == GALAXY_HAB_VC_CLOSE && f.closes == 1 && t.fd == -1);
    assert(galaxy_hab_transport_close(&t) == 0);

    init(&t, &f);
    open_transport(&t);
    f.fail_command = GALAXY_HAB_SEND;
    assert(galaxy_hab_transport_send(&t, message, 64) == -EIO);
    assert(galaxy_hab_transport_close(&t) == 0);
    puts("HAB transport ABI/lifecycle/error contracts passed; fake I/O only");
    return 0;
}
