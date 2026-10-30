/* SPDX-License-Identifier: GPL-3.0-only */
#include "galaxy_hab_hmac.h"
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void expect(const uint8_t digest[32], const char *hex)
{
    char text[65];
    size_t i;
    for (i = 0; i < 32; ++i) (void)snprintf(text + i*2, 3, "%02x", digest[i]);
    assert(strcmp(text, hex) == 0);
}
int main(void)
{
    struct galaxy_hab_hmac_key key = {{0}};
    uint8_t digest[32], *message = malloc(1000000);
    size_t i;
    /* RFC4231向量1/2，32B key尾部补零与其短key规范化等价。 */
    memset(key.bytes, 0x0b, 20);
    assert(galaxy_hab_hmac_sign(&key, (const uint8_t *)"Hi There", 8, digest) == 0);
    expect(digest, "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    memset(&key, 0, sizeof(key));
    memcpy(key.bytes, "Jefe", 4);
    assert(galaxy_hab_hmac_sign(&key, (const uint8_t *)"what do ya want for nothing?", 28, digest) == 0);
    expect(digest, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    assert(message);
    for (i = 0; i < 32; ++i) key.bytes[i] = (uint8_t)i;
    for (i = 0; i < 1000000; ++i) message[i] = (uint8_t)(i % 251);
    /* Python标准库hmac/hashlib独立计算，覆盖块边界与协议实际签名长度。 */
    {
        const size_t sizes[] = {0,36,55,56,63,64,65,152,184,1000000};
        const char *golden[] = {
            "d38b42096d80f45f826b44a9d5607de72496a415d3f4a1a8c88e3bb9da8dc1cb",
            "ec2a8f0e79cf1ae530b86bb5aba6022485ef6fe2aca31e6f551619c41974367d",
            "b478e4cbd63871759702a8a4c9828359869bc9e20d3df429ecd08f5a5d3d9340",
            "e5d1f65e9e9359d05c577b6890044f08c9a1f7969b683f1237ef07db70e5f862",
            "d37a8dadb82b15310342ceabf0de8cb8991ee9bd55dd3e4813e952081cb24bf1",
            "173206781c3b828a0dc2a716fe0ddb5e6e56ec171170952ff6b3f4de44fa18d7",
            "22084084cc171f63dfdd6ca4bcb0c29be8d4ff1cc6b1d0d21e10e2a2a0bfce9c",
            "34af604d33f668833da05b0f58c9764d0e6ae08ff5280a75f50325f7a7819485",
            "89a4bac8c23587c2a141f6a6dfac9d7325a65636e6b904706f8bd973c5c74470",
            "f2cd17fa63b010fbe5d152e07fc8fed608773600d0e51f082d09acebc10f07fa",
        };
        for (i = 0; i < sizeof(sizes)/sizeof(sizes[0]); ++i) {
            assert(galaxy_hab_hmac_sign(&key, message, sizes[i], digest) == 0);
            expect(digest, golden[i]);
        }
    }
    assert(galaxy_hab_hmac_sign(NULL, message, 0, digest) == -EINVAL);
    assert(galaxy_hab_hmac_sign(&key, NULL, 1, digest) == -EINVAL);
    assert(galaxy_hab_hmac_sign(&key, message, 1, NULL) == -EINVAL);
    free(message);
    puts("HAB HMAC-SHA256 RFC4231/hashlib contracts passed; public synthetic keys only");
    return 0;
}
