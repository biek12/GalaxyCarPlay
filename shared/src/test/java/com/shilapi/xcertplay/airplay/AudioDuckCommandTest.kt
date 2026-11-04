package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** duckAudio and unduckAudio mark each navigation prompt (#946); they reach the sink as one duck state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AudioDuckCommandTest {
    @Test fun commandPreservesIphoneDbAndRampAndOldSessionCannotUnduckNewPrompt() {
        val values = mutableListOf<Triple<Boolean, Float, Int?>>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioDuck(ducked: Boolean, volumeDb: Float, durationMs: Int?) {
                values += Triple(ducked, volumeDb, durationMs)
            }
        })
        val first = session(engine)
        val second = session(engine)
        try {
            val body = BplistCodec.encode(mapOf("type" to "duckAudio", "params" to mapOf("volume" to -18.0, "durationMs" to 250)))
            AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(first,
                    RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), body))
            engine.onAudioDuck(second, true, -12f, 100)
            engine.onAudioDuck(first, false)
            assertEquals(listOf(Triple(true, -18f, 250), Triple(true, -12f, 100)), values)
            engine.onSessionClosed(second)
        } finally { first.close(); second.close() }
    }
    @Test fun duckCommandsReachTheMediaHandlerAndOtherCommandsDoNot() {
        val ducks = mutableListOf<Boolean>()
        val commands = mutableListOf<String>()
        val session = session(
            object : AirPlayMediaHandler {
                override fun onAudioDuck(session: AirPlaySession, ducked: Boolean) { ducks += ducked }
            },
            object : AirPlaySessionListener {
                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) { commands += type }
            },
        )
        try {
            for (type in listOf("duckAudio", "modesChanged", "unduckAudio", "requestUI")) command(session, type)
        } finally { session.close() }
        assertEquals(listOf(true, false), ducks)
        assertEquals(listOf("duckAudio", "modesChanged", "unduckAudio", "requestUI"), commands)
    }

    @Test fun engineForwardsDucksAndEndsAPromptWhoseSessionCloses() {
        val ducks = mutableListOf<Boolean>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioDuck(ducked: Boolean) { ducks += ducked }
        })
        val first = session(engine)
        val other = session(engine)
        try {
            engine.onAudioDuck(first, true)
            engine.onAudioDuck(first, false)
            engine.onSessionClosed(first)
            assertEquals(listOf(true, false), ducks)

            engine.onAudioDuck(first, true)
            engine.onSessionClosed(other)
            assertEquals(listOf(true, false, true), ducks)
            engine.onSessionClosed(first)
            assertEquals(listOf(true, false, true, false), ducks)
        } finally {
            first.close()
            other.close()
        }
    }

    private fun command(session: AirPlaySession, type: String) {
        val body = BplistCodec.encode(mapOf("type" to type, "params" to mapOf("durationMs" to 500L)))
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }
            .invoke(session, RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), body))
    }

    private fun session(
        media: AirPlayMediaHandler,
        listener: AirPlaySessionListener = object : AirPlaySessionListener {},
    ): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
            main = AirPlayDisplayConfig(800, 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = listener,
        media = media,
    )
}
