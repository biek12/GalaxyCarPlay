/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_owned.h"
#include <errno.h>
#include <string.h>

static void put32(uint8_t *p, uint32_t value)
{
    unsigned i;
    for (i = 0; i < 4; ++i) p[i] = (uint8_t)(value >> (i * 8));
}
int galaxy_hab_owned_encode(uint8_t *packet, uint32_t type, uint32_t sequence,
    uint32_t export_id, uint32_t stream, uint64_t session, galaxy_hab_sign sign, void *context)
{
    uint8_t bytes[GALAXY_HAB_PACKET_SIZE] = {0}, digest[32];
    int result;
    if (!packet || !sign || !session || !export_id || type < 1 || type > 4) return -EINVAL;
    put32(bytes, GALAXY_HAB_OWNED_MAGIC); put32(bytes + 4, GALAXY_HAB_OWNED_VERSION); put32(bytes + 8, type);
    put32(bytes + 12, sequence); put32(bytes + 16, export_id); put32(bytes + 20, stream);
    put32(bytes + 24, (uint32_t)session); put32(bytes + 28, (uint32_t)(session >> 32));
    result = sign(context, bytes, sizeof(bytes), digest);
    if (result) return result;
    memcpy(bytes + 32, digest, sizeof(digest));
    memcpy(packet, bytes, sizeof(bytes));
    return 0;
}
int galaxy_hab_owned_check(const uint8_t *packet, size_t size, uint32_t type,
    uint32_t sequence, uint32_t export_id, uint32_t stream, uint64_t session,
    galaxy_hab_sign sign, void *context)
{
    uint8_t expected[GALAXY_HAB_PACKET_SIZE], difference = 0;
    size_t i;
    int result;
    if (!packet || size != sizeof(expected)) return -EPROTO;
    result = galaxy_hab_owned_encode(expected, type, sequence, export_id, stream, session, sign, context);
    if (result) return result;
    for (i = 0; i < size; ++i) difference |= expected[i] ^ packet[i];
    return difference ? -EPROTO : 0;
}
