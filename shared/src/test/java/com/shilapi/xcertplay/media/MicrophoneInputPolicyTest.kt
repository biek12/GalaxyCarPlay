package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneInputPolicyTest {
    @Test fun appUplinkUsesProductCommunicationPathWithoutChangingSiriOrOrdinaryHosts() {
        for (type in listOf("default", "compatibility")) {
            assertTrue(MicrophoneInputPolicy.communication(type, true))
            assertFalse(MicrophoneInputPolicy.communication(type, false))
            assertEquals(AudioChannel.PHONE, MicrophoneInputPolicy.channel(type))
        }
        for (type in listOf("telephony", "facetime")) {
            assertTrue(MicrophoneInputPolicy.communication(type, false))
        }
        assertFalse(MicrophoneInputPolicy.communication("speechrecognition", true))
        assertEquals(AudioChannel.ASSISTANT, MicrophoneInputPolicy.channel("speechrecognition"))
        assertFalse(MicrophoneInputPolicy.communication("media", true))
    }
}
