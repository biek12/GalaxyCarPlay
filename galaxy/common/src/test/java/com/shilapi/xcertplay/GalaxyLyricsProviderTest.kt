package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GalaxyLyricsProviderTest {
    @Test fun querySendsOnlyEncodedTitleAndArtist() {
        val url = GalaxyLyricsHttpClient().requestUrl(GalaxyLyricQuery("A song & title", "Artist / band"))!!
        assertEquals("https", url.protocol)
        assertEquals("lrclib.net", url.host)
        assertEquals("/api/search", url.path)
        assertTrue(url.query.contains("track_name=A+song+%26+title"))
        assertTrue(url.query.contains("artist_name=Artist+%2F+band"))
        assertFalse(url.query.contains("album"))
        assertFalse(url.query.contains("duration"))
    }

    @Test fun invalidQueryDoesNotBuildRequest() {
        val client = GalaxyLyricsHttpClient()
        assertNull(client.requestUrl(GalaxyLyricQuery(" ", "Artist")))
        assertNull(client.requestUrl(GalaxyLyricQuery("Song", " ")))
        assertNull(client.requestUrl(GalaxyLyricQuery("x".repeat(301), "Artist")))
    }

    @Test fun syncedLrcSupportsFractionsMultipleTimestampsAndOffset() {
        val parsed = GalaxyLrcParser.parseSynced(
            "[ar:Artist]\n[offset:-100]\n[00:01.23]First line\n[00:02.4]Second line\n[00:03.000][00:04.00]Repeated line"
        )
        assertEquals(
            listOf(
                ClusterLyricLine(1_130L, "First line"),
                ClusterLyricLine(2_300L, "Second line"),
                ClusterLyricLine(2_900L, "Repeated line"),
                ClusterLyricLine(3_900L, "Repeated line"),
            ),
            parsed,
        )
    }

    @Test fun malformedAndOversizedLyricsAreIgnoredAndPlainTextIsUnsynchronized() {
        assertTrue(GalaxyLrcParser.parseSynced("[00:99.00]bad\n[meta]tag").isEmpty())
        assertTrue(GalaxyLrcParser.parseSynced("x".repeat(512 * 1024 + 1)).isEmpty())
        assertEquals(
            listOf(ClusterLyricLine(null, "Line 1"), ClusterLyricLine(null, "Line 2")),
            GalaxyLrcParser.parsePlain("Line 1\n\n Line 2 "),
        )
    }

    @Test fun httpClientChecksExactTrackAndArtistAndUsesAppUserAgent() {
        val body = """[{"trackName":"Song","artistName":"Other","syncedLyrics":"[00:01.00]Wrong"},{"trackName":"Song","artistName":"Artist, Guest","syncedLyrics":"[00:01.00]Verse"}]"""
        var seenUrl: URL? = null
        var seenAgent: String? = null
        val client = GalaxyLyricsHttpClient(open = { url ->
            seenUrl = url
            FakeHttpConnection(url, body.toByteArray()) { agent -> seenAgent = agent }
        })
        val lines = client.lookup(GalaxyLyricQuery("Song", "Artist"), GalaxyLyricsHttpClient.Cancellation())
        assertEquals(listOf(ClusterLyricLine(1_000L, "Verse")), lines)
        assertTrue(seenUrl!!.query.contains("track_name=Song"))
        assertTrue(seenUrl!!.query.contains("artist_name=Artist"))
        assertTrue(seenAgent!!.startsWith("GalaxyPlay/"))

        val wrong = GalaxyLyricsHttpClient(open = { url ->
            FakeHttpConnection(url, body.replace("\"artistName\":\"Artist, Guest\"", "\"artistName\":\"Different, Guest\"").toByteArray()) {}
        })
        assertTrue(wrong.lookup(GalaxyLyricQuery("Song", "Artist"), GalaxyLyricsHttpClient.Cancellation())!!.isEmpty())
    }

    private class FakeHttpConnection(url: URL, private val body: ByteArray, private val onAgent: (String?) -> Unit) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = HTTP_OK
        override fun getContentType() = "application/json; charset=utf-8"
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun setRequestProperty(key: String, value: String) {
            super.setRequestProperty(key, value)
            if (key.equals("User-Agent", ignoreCase = true)) onAgent(value)
        }
    }
}
