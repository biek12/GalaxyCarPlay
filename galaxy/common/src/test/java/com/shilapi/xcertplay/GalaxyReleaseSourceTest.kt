package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class GalaxyReleaseSourceTest {
    @Test fun noImplicitLegacyEndpoint() {
        assertNull(GalaxyUpdateManifest.endpoint(""))
        try {
            GalaxyUpdateTransfer().latest("com.ecarx.carplay", GalaxyUpdateRequest())
            fail("未配置时不应发起网络")
        } catch (error: GalaxyUpdateException) {
            assertEquals(GalaxyUpdateFailure.NOT_CONFIGURED, error.reason)
        }
    }
    @Test fun acceptsExplicitHttpsOnlyWithoutEmbeddedCredentialsOrParameters() {
        assertEquals(URI("https://releases.example.test/latest.json"),
            GalaxyUpdateManifest.endpoint("https://releases.example.test/latest.json"))
        for (url in listOf("http://example.test/latest.json", "https://user:password@example.test/a", "https://example.test/a?token=x")) {
            try { GalaxyUpdateManifest.endpoint(url); fail("不应接受不安全源") }
            catch (_: GalaxyUpdateException) { }
        }
    }
}
