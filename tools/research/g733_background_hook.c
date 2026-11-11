/* SPDX-License-Identifier: GPL-3.0-only */
/* Fixed G733 research probe. Preload into an explicitly launched HMI instance. */
#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>

#ifdef G733_HOST_TEST
#include <fcntl.h>
#define open probe_open
#define eglSwapBuffers probe_swap
#else
extern void *dlsym(void *, const char *);
extern char *getenv(const char *);
extern char *getcwd(char *, size_t);
extern long lseek(int, long, int);
__attribute__((visibility("hidden"))) void g733_scene_install(uintptr_t *, uintptr_t, const char *, const char *);
__attribute__((visibility("hidden"))) void g733_scene_after_swap(void);
#define O_RDONLY 0
#define O_CREAT 0x100
#define RTLD_DEFAULT ((void *)-2L)
#define RTLD_NEXT ((void *)-3L)
#endif

#ifndef G733_OEM_DIR
#define G733_OEM_DIR "/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI"
#endif
#ifndef G733_CANDIDATE
#define G733_CANDIDATE "/var/data/galaxy-background-field/background-probe.kzb"
#endif

typedef int (*OpenFile)(const char *, int, ...);
typedef unsigned int (*SwapBuffers)(void *, void *);
static OpenFile real_open;
static SwapBuffers real_swap;
static uintptr_t resource_caller;
static int replace_background;
static int resource_runtime __attribute__((unused));
static unsigned candidate_opened;
static unsigned observed_resource;
static unsigned observed_swap;
static unsigned reported_rejection;

static void report(const char *message) {
    int saved_errno = errno;
    (void)write(2, message, strlen(message));
    errno = saved_errno;
}

#ifndef G733_HOST_TEST
__attribute__((constructor))
static void load_probe(void) {
    real_open = (OpenFile)dlsym(RTLD_NEXT, "open");
    real_swap = (SwapBuffers)dlsym(RTLD_NEXT, "eglSwapBuffers");
    uintptr_t *table = dlsym(RTLD_DEFAULT, "_ZTV15ClusterFunction");
    if (!real_open || !real_swap || !table) {
        report("G733_HOOK_SYMBOLS_UNAVAILABLE\n");
        return;
    }

    /* Validate the two relocated lifecycle slots without changing either slot. */
    uintptr_t base = (uintptr_t)table - 0xa28dc0;
    if (table[0x60 / sizeof(uintptr_t)] != base + 0x27d2a0 ||
        table[0x48 / sizeof(uintptr_t)] != base + 0x27d2a8) {
        report("G733_HOOK_ABI_MISMATCH\n");
        return;
    }
    resource_caller = base + 0x60df6c;
    const char *mode = getenv("GALAXY_G733_BACKGROUND_PROBE");
    replace_background = mode && strcmp(mode, "replace") == 0;
    const char *scene_mode = getenv("GALAXY_G733_SCENE_MODE");
    resource_runtime = scene_mode && strcmp(scene_mode, "runtime") == 0;
    report(replace_background ? "G733_HOOK_READY_REPLACE\n" : "G733_HOOK_READY_OBSERVE\n");
    g733_scene_install(table, base, getenv("GALAXY_G733_SCENE_MODE"), getenv("GALAXY_G733_SR_BENCH"));
}
#endif

static int background_request(const char *path, int flags, uintptr_t caller) {
    if (!resource_caller || caller != resource_caller || flags != O_RDONLY)
        return 0;
    if (strcmp(path, G733_OEM_DIR "/background.kzb") == 0)
        return 1;
    if (strcmp(path, "background.kzb") && strcmp(path, "./background.kzb"))
        return 0;
    char cwd[256];
    return getcwd(cwd, sizeof(cwd)) && strcmp(cwd, G733_OEM_DIR) == 0;
}

/* Check the exact bytes on the descriptor handed back to Kanzi. SHA-256 is
 * also verified when building and staging this fixed sample. */
static int check_candidate(int fd) {
    uint64_t expected_size=G733_CANDIDATE_SIZE,expected_fingerprint=G733_CANDIDATE_FNV;
#ifdef G733_RESOURCE_BACKGROUND
    if(resource_runtime) {
        unsigned char proof[32],extra;
        int descriptor=real_open("/var/data/autoservice-cluster/background.proof",O_RDONLY);
        if(descriptor<0)return 0;
        ssize_t count=read(descriptor,proof,sizeof(proof));
        int complete=count==(ssize_t)sizeof(proof) && read(descriptor,&extra,1)==0;
        close(descriptor);
        if(!complete || memcmp(proof,"GKP1\1\0\0\0",8))return 0;
        memcpy(&expected_size,proof+8,8);memcpy(&expected_fingerprint,proof+16,8);
        if(expected_size!=G733_CANDIDATE_SIZE)return 0;
    }
#endif
    uint64_t fingerprint = UINT64_C(14695981039346656037);
    size_t total = 0;
    unsigned char buffer[4096];
    for (;;) {
        ssize_t count = read(fd, buffer, sizeof(buffer));
        if (count < 0)
            return 0;
        if (!count)
            break;
        total += (size_t)count;
        if (total > expected_size)
            return 0;
        for (ssize_t i = 0; i < count; ++i)
            fingerprint = (fingerprint ^ buffer[i]) * UINT64_C(1099511628211);
    }
    return total == expected_size && fingerprint == expected_fingerprint &&
        lseek(fd, 0, 0) == 0;
}

static void resource_opened(const char *message) {
    unsigned expected = 0;
    if (__atomic_compare_exchange_n(&observed_resource, &expected, 1, 0,
            __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
        report(message);
        __atomic_store_n(&observed_resource, 2, __ATOMIC_RELEASE);
    }
}

static int open_background(const char *path, OpenFile next) {
    int previous_errno = errno;
    if (replace_background) {
        const char *candidate_path=G733_CANDIDATE;
#ifdef G733_RESOURCE_BACKGROUND
        if(resource_runtime)candidate_path="/var/data/autoservice-cluster/background.kzb";
#endif
        int candidate = next(candidate_path, O_RDONLY);
        if (candidate >= 0 && check_candidate(candidate)) {
            candidate_opened=1;
            errno = previous_errno;
            resource_opened("G733_CANDIDATE_BACKGROUND_OPEN\n");
            return candidate;
        }
        if (candidate >= 0)
            close(candidate);
        if (!__atomic_exchange_n(&reported_rejection, 1, __ATOMIC_RELAXED))
            report("G733_CANDIDATE_REJECTED\n");
    }

    errno = previous_errno;
    int fd = next(path, O_RDONLY);
    if (fd >= 0)
        resource_opened("G733_ORIGINAL_BACKGROUND_OPEN\n");
    return fd;
}

__attribute__((visibility("hidden"))) int g733_background_resource_active(void) {
    return candidate_opened && __atomic_load_n(&observed_swap,__ATOMIC_ACQUIRE);
}

int open(const char *path, int flags, ...) {
    OpenFile next = real_open;
#ifndef G733_HOST_TEST
    if (!next)
        next = (OpenFile)dlsym(RTLD_NEXT, "open");
#endif
    if (!next) {
        errno = EIO;
        return -1;
    }
    int saved_errno = errno;
    int selected = background_request(path, flags, (uintptr_t)__builtin_return_address(0));
    errno = saved_errno;
    if (selected)
        return open_background(path, next);
    if (flags & O_CREAT) {
        va_list args;
        va_start(args, flags);
        unsigned int mode = va_arg(args, unsigned int);
        va_end(args);
        return next(path, flags, mode);
    }
    return next(path, flags);
}

unsigned int eglSwapBuffers(void *display, void *surface) {
    SwapBuffers next = real_swap;
#ifndef G733_HOST_TEST
    if (!next)
        next = (SwapBuffers)dlsym(RTLD_NEXT, "eglSwapBuffers");
#endif
    if (!next)
        return 0;
    unsigned int result = next(display, surface);
#ifndef G733_HOST_TEST
    if (result)
        g733_scene_after_swap();
#endif
    if (result && __atomic_load_n(&observed_resource, __ATOMIC_ACQUIRE) == 2 &&
        !__atomic_exchange_n(&observed_swap, 1, __ATOMIC_RELAXED))
        report("G733_EGL_SWAP_AFTER_RESOURCE_OPEN\n");
    return result;
}
