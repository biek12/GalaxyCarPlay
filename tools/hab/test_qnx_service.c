/* SPDX-License-Identifier: GPL-3.0-only */
#define main receiver_main
#include "qnx_probe.c"
#undef main
#include <assert.h>
#include <stdio.h>

volatile sig_atomic_t probe_stopping;
static int scenario, opens, steps, closes, in_session;
void probe_text(const char *text) { (void)text; }
void probe_number(const char *label, int64_t value) { (void)label; (void)value; }
int probe_number_arg(const char *text, uint32_t maximum, uint32_t *value)
{
    unsigned result = 0;
    while (*text) { result = result * 10 + (unsigned)(*text++ - '0'); }
    assert(result <= maximum); *value = result; return 0;
}
int probe_key_file(const char *path, struct galaxy_hab_hmac_key *key)
{ (void)path; memset(key, 0, sizeof(*key)); return 0; }
void probe_wipe(struct galaxy_hab_hmac_key *key) { memset(key, 0, sizeof(*key)); }
int probe_signals(void) { return 0; }
int probe_selftest(void) { return 0; }
int galaxy_hab_hmac_sign(void *context, const uint8_t *bytes, size_t size, uint8_t digest[32])
{ (void)context; (void)bytes; (void)size; memset(digest, 0, 32); return 0; }
void galaxy_hab_qnx_init(struct galaxy_hab_qnx *q, galaxy_hab_sign sign, void *context)
{ (void)sign; (void)context; memset(q, 0, sizeof(*q)); }
int galaxy_hab_qnx_open(struct galaxy_hab_qnx *q, const struct galaxy_hab_target *targets,
                         uint32_t count, uint32_t timeout)
{
    assert(!in_session && count == 1 && targets[0].display_id == 2 && timeout == 600000);
    assert(q->cancelled && !q->cancelled(q->cancel_context));
    ++opens;
    if (scenario == 1) return -ENODEV;
    if (scenario == 2) return -EACCES;
    if (scenario == 3) { probe_stopping = 1; return -EINTR; }
    in_session = 1; return 0;
}
int galaxy_hab_qnx_step_timeout(struct galaxy_hab_qnx *q, uint32_t timeout)
{
    (void)q; assert(in_session && timeout == 5000); ++steps;
    if (steps % 2) return 0;
    if (opens == 3) probe_stopping = 1;
    return 1;
}
int galaxy_hab_qnx_step(struct galaxy_hab_qnx *q) { return galaxy_hab_qnx_step_timeout(q, 5000); }
int galaxy_hab_qnx_close(struct galaxy_hab_qnx *q)
{ (void)q; ++closes; in_session = 0; return 0; }
int habmm_socket_open(int *vc, unsigned mmid, unsigned timeout, unsigned flags)
{ (void)vc; (void)mmid; (void)timeout; (void)flags; return -1; }
int habmm_socket_recv(int vc, void *p, unsigned *size, unsigned timeout, unsigned flags)
{ (void)vc; (void)p; (void)size; (void)timeout; (void)flags; return -1; }
int habmm_socket_send(int vc, const void *p, unsigned size, unsigned flags)
{ (void)vc; (void)p; (void)size; (void)flags; return -1; }
int habmm_socket_close(int vc) { (void)vc; return 0; }
int screen_create_context(screen_context_t *context, int type)
{ (void)context; (void)type; return -1; }
int screen_destroy_context(screen_context_t context) { (void)context; return 0; }
int screen_get_context_property_iv(screen_context_t context, int property, int *value)
{ (void)context; (void)property; (void)value; return -1; }
int screen_get_context_property_pv(screen_context_t context, int property, void **value)
{ (void)context; (void)property; (void)value; return -1; }
int screen_get_display_property_iv(screen_display_t display, int property, int *value)
{ (void)display; (void)property; (void)value; return -1; }
int main(void)
{
    char *args[] = {"receiver", "serve", "/explicit/key", "2", "1920", "532", "4"};
    for (scenario = 0; scenario < 4; ++scenario) {
        opens = steps = closes = in_session = 0; probe_stopping = 0;
        int result = receiver_main(7, args);
        if (scenario == 0) assert(result == 0 && opens == 3 && closes == 3 && steps == 6);
        if (scenario == 1) assert(result == 1 && opens == 3 && closes == 3);
        if (scenario == 2) assert(result == 1 && opens == 1 && closes == 1);
        if (scenario == 3) assert(result == 0 && opens == 1 && closes == 1 && !steps);
    }
    puts("QNX multi-session, bounded failures and cancellation passed");
    return 0;
}
