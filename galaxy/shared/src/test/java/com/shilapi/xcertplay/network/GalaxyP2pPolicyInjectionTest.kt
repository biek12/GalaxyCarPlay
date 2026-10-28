package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

/** 参数进入真实建组计划与返回组校验；不以只读UI选项存在代替接线。 */
class GalaxyP2pPolicyInjectionTest {
    private val mainland = GalaxyP2pRegion.MAINLAND_CHINA.policy.channels

    @Test fun twoGhzOnlyRadioNeverRequestsFiveGhzEvenWithRememberedSuccess() {
        val remembered = P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5745)
        val plan = P2pStartupRecovery.plan(null, remembered, fiveGhz = false, allowedChannels = mainland)
        assertEquals(listOf(2437, 2412, 2462), plan.map { it.frequencyMHz })
    }

    @Test fun bandPreferenceIsIntersectedWithRegionWithoutUnpinnedBandRequest() {
        val five = P2pStartupRecovery.plan(null, preferredChannel = WifiP2pChannels.AUTO_5_GHZ,
            allowedChannels = mainland)
        assertEquals(listOf(5745, 5765, 5785, 5805, 5825), five.map { it.frequencyMHz })
        assertTrue(five.all { it.mode == P2pCreationMode.FIXED_5_GHZ })
        assertThrows(WirelessStartupException::class.java) {
            P2pStartupRecovery.plan(null, preferredChannel = WifiP2pChannels.AUTO_5_GHZ,
                allowedChannels = GalaxyP2pRegion.OTHER_CONSERVATIVE.policy.channels)
        }
    }

    @Test fun actualReturnedGroupMustRemainInsideFrozenPolicy() {
        P2pStartupRecovery.validateCreatedFrequency(5745, mainland)
        assertThrows(WirelessStartupException::class.java) {
            P2pStartupRecovery.validateCreatedFrequency(5180, mainland)
        }
        assertThrows(WirelessStartupException::class.java) {
            P2pStartupRecovery.validateCreatedFrequency(null, mainland)
        }
        P2pStartupRecovery.validateCreatedFrequency(5180, null)
    }

    @Test fun policyCanContainAnySupportedConcreteChannelWithoutAssumingSixOneEleven() {
        val plan = P2pStartupRecovery.plan(null, allowedChannels = listOf(3))
        assertEquals(listOf(2422), plan.map { it.frequencyMHz })
        for (invalid in listOf(emptyList(), listOf(0), listOf(-5), listOf(149, 149))) {
            assertThrows(IllegalArgumentException::class.java) {
                P2pStartupRecovery.plan(null, allowedChannels = invalid)
            }
        }
    }

    @Test fun runtimeConfigurationFreezesCollectionAndDoesNotChangeOfficialDefault() {
        val supplied = mutableListOf(149, 153, 6)
        val runtime = config(supplied)
        supplied.clear()
        assertEquals(listOf(149, 153, 6), runtime.wifiP2pAllowedChannels)
        @Suppress("UNCHECKED_CAST")
        val exposed = runtime.wifiP2pAllowedChannels as MutableList<Int>
        assertThrows(UnsupportedOperationException::class.java) { exposed.clear() }
        assertNull(config(null).wifiP2pAllowedChannels)
        assertEquals(5180, P2pStartupRecovery.plan(null).first().frequencyMHz)
    }

    private fun config(channels: List<Int>?) = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
            serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        wifiP2pAllowedChannels = channels,
    )
}
