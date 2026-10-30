/* SPDX-License-Identifier: GPL-3.0-only */
#ifndef GALAXY_HAB_PROBE_COMMON_H
#define GALAXY_HAB_PROBE_COMMON_H
#include "galaxy_hab_hmac.h"
#include <signal.h>
extern volatile sig_atomic_t probe_stopping;
void probe_text(const char *text);
void probe_number(const char *label, int64_t value);
int probe_number_arg(const char *text, uint32_t maximum, uint32_t *result);
int probe_key_file(const char *path, struct galaxy_hab_hmac_key *key);
void probe_wipe(struct galaxy_hab_hmac_key *key);
int probe_signals(void);
int probe_selftest(void);
#endif
