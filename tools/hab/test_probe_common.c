/* SPDX-License-Identifier: GPL-3.0-only */
#define _POSIX_C_SOURCE 200809L
#include "probe_common.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

int main(void)
{
    struct galaxy_hab_hmac_key key;
    uint8_t fixture[33];
    uint32_t number = 123;
    char name[] = "/tmp/galaxy-hab-synthetic-XXXXXX";
    int fd = mkstemp(name);
    assert(fd >= 0);
    for (size_t i = 0; i < sizeof(fixture); ++i) fixture[i] = (uint8_t)i;
    assert(write(fd, fixture, 32) == 32 && close(fd) == 0);
    assert(probe_key_file(name, &key) == 0);
    for (size_t i = 0; i < 32; ++i) assert(key.bytes[i] == i);
    probe_wipe(&key);
    for (size_t i = 0; i < 32; ++i) assert(key.bytes[i] == 0);
    for (size_t size = 31; size <= 33; size += 2) {
        fd = open(name, O_WRONLY | O_TRUNC);
        assert(fd >= 0 && write(fd, fixture, size) == (ssize_t)size && close(fd) == 0);
        assert(probe_key_file(name, &key) == -EINVAL);
        for (size_t i = 0; i < 32; ++i) assert(key.bytes[i] == 0);
    }
    assert(unlink(name) == 0);
    assert(probe_number_arg("4294967295", UINT32_MAX, &number) == 0 && number == UINT32_MAX);
    assert(probe_number_arg("4294967296", UINT32_MAX, &number) == -EINVAL);
    assert(probe_number_arg("9999999999999999999999999999999", UINT32_MAX, &number) == -EINVAL);
    assert(probe_number_arg("-1", 120, &number) == -EINVAL);
    assert(probe_number_arg("", 120, &number) == -EINVAL);
    assert(probe_number_arg("30x", 120, &number) == -EINVAL);
    assert(probe_selftest() == 0);
    puts("probe CLI parsing, synthetic key input/wipe and selftest contracts passed");
    return 0;
}
