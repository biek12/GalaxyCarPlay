/* SPDX-License-Identifier: GPL-3.0-only */
#include <fcntl.h>
#include <assert.h>
#include <stdio.h>
#include <sys/stat.h>

#define G733_HOST_TEST 1
#define G733_CANDIDATE_SIZE 3
#define G733_CANDIDATE_FNV UINT64_C(0xe71fa2190541574b) /* FNV-1a of "abc". */
#include "g733_background_hook.c"
#undef open

static unsigned int swap_result;

static unsigned int fake_swap(void *display, void *surface) {
    assert(display == (void *)1 && surface == (void *)2);
    return swap_result;
}

static void put(const char *path, const char *bytes) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0 && write(fd, bytes, 3) == 3 && close(fd) == 0);
}

static void expect_bytes(int fd, const char *wanted) {
    char bytes[3];
    assert(fd >= 0 && read(fd, bytes, sizeof(bytes)) == sizeof(bytes));
    assert(memcmp(bytes, wanted, sizeof(bytes)) == 0 && close(fd) == 0);
}

int main(void) {
    assert(chdir(G733_OEM_DIR) == 0);
    real_open = open;
    real_swap = fake_swap;
    resource_caller = 1;
    assert(background_request("background.kzb", O_RDONLY, 1));
    assert(background_request("./background.kzb", O_RDONLY, 1));
    assert(background_request(G733_OEM_DIR "/background.kzb", O_RDONLY, 1));
    assert(!background_request("background.kzb", O_RDONLY, 2));
    assert(!background_request("background.kzb", O_WRONLY, 1));
    assert(!background_request("warning.kzb", O_RDONLY, 1));

    put("background.kzb", "OEM");
    put(G733_CANDIDATE, "abc");
    expect_bytes(probe_open("background.kzb", O_RDONLY), "OEM");
    assert(probe_swap((void *)1, (void *)2) == 0 && !observed_swap);
    expect_bytes(open_background("background.kzb", real_open), "OEM");
    assert(observed_resource == 2 && !observed_swap);

    observed_resource = 0;
    replace_background = 1;
    expect_bytes(open_background("background.kzb", real_open), "abc");
    assert(probe_swap((void *)1, (void *)2) == 0 && !observed_swap);
    swap_result = 1;
    assert(probe_swap((void *)1, (void *)2) == 1 && observed_swap);
    assert(probe_swap((void *)1, (void *)2) == 1);

    put(G733_CANDIDATE, "bad");
    expect_bytes(open_background("background.kzb", real_open), "OEM");
    assert(reported_rejection == 1);
    assert(unlink(G733_CANDIDATE) == 0);
    expect_bytes(open_background("background.kzb", real_open), "OEM");

    umask(0);
    int created = probe_open("passthrough", O_CREAT | O_WRONLY | O_EXCL, 0640);
    struct stat info;
    assert(created >= 0 && fstat(created, &info) == 0 && close(created) == 0);
    assert((info.st_mode & 0777) == 0640);
    assert(probe_open("absent", O_RDONLY) == -1 && errno == ENOENT);
    puts("G733 background hook host checks passed");
}
