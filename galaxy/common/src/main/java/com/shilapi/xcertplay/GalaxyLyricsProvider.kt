package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 歌词查询只发送曲名和艺人；不发送专辑、时长、手机或车辆标识。 */
internal data class GalaxyLyricQuery(val title: String, val artist: String)

/** Parses synchronized LRC and plain text without retaining service JSON or writing lyrics to disk. */
internal object GalaxyLrcParser {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]")
    private val offset = Regex("(?m)^\\[offset:([+-]?\\d+)]\\s*$", RegexOption.IGNORE_CASE)
    private const val MAX_TEXT_CHARS = 512 * 1024
    private const val MAX_LINES = 5_000

    fun fromResponse(json: JSONObject): List<ClusterLyricLine> {
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        if (synced != null) parseSynced(synced).takeIf { it.isNotEmpty() }?.let { return it }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" } ?: return emptyList()
        return parsePlain(plain)
    }

    fun parseSynced(input: String): List<ClusterLyricLine> {
        if (input.isEmpty() || input.length > MAX_TEXT_CHARS) return emptyList()
        val adjustment = (offset.find(input)?.groupValues?.get(1)?.toLongOrNull() ?: 0L).coerceIn(-120_000L, 120_000L)
        val lines = ArrayList<Pair<Int, ClusterLyricLine>>()
        for ((order, raw) in input.lineSequence().take(MAX_LINES * 2).withIndex()) {
            if (lines.size >= MAX_LINES) break
            val matches = timestamp.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = timestamp.replace(raw, "").trim()
            if (text.isEmpty()) continue
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                if (seconds !in 0..59) continue
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    1 -> fraction.toLongOrNull()?.times(100L)
                    2 -> fraction.toLongOrNull()?.times(10L)
                    3 -> fraction.toLongOrNull()
                    else -> 0L
                } ?: 0L
                val time = (minutes * 60_000L + seconds * 1_000L + millis + adjustment).coerceAtLeast(0L)
                lines += order to ClusterLyricLine(time, text)
                if (lines.size >= MAX_LINES) break
            }
        }
        return lines.sortedWith(compareBy<Pair<Int, ClusterLyricLine>> { it.second.timeMillis }.thenBy { it.first })
            .map { it.second }
            .distinctBy { it.timeMillis to it.text }
    }

    fun parsePlain(input: String): List<ClusterLyricLine> {
        if (input.isEmpty() || input.length > MAX_TEXT_CHARS) return emptyList()
        return input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .take(MAX_LINES).map { ClusterLyricLine(null, it) }.toList()
    }
}

/** 自有歌词聚合服务；严格匹配曲名/艺人，取消请求时同时关闭连接。 */
internal class GalaxyLyricsHttpClient(
    private val open: (URL) -> HttpURLConnection = GalaxyLyricsNetwork::open,
    private val origin: String = com.shilapi.xcertplay.host.BuildConfig.GALAXY_ORIGIN,
    private val userAgent: String = "GalaxyPlay/0.1.0-dev (CarPlay lyric display)",
) {
    @Volatile private var retryAfterElapsedMillis = 0L

    fun requestUrl(query: GalaxyLyricQuery): URL? {
        val title = query.title.trim()
        val artist = query.artist.trim()
        if (title.isEmpty() || artist.isEmpty() || title.length > 300 || artist.length > 300) return null
        val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8.name())
        val encodedArtist = URLEncoder.encode(artist, Charsets.UTF_8.name())
        return URL("${origin.trimEnd('/')}/lyrics/search?track_name=$encodedTitle&artist_name=$encodedArtist")
    }

    fun lookup(query: GalaxyLyricQuery, token: Cancellation): List<ClusterLyricLine>? {
        val url = requestUrl(query) ?: return null
        if (SystemClock.elapsedRealtime() < retryAfterElapsedMillis) return null
        token.check()
        val connection = open(url)
        if (!token.attach(connection)) throw InterruptedIOException("lyrics request cancelled")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            token.check()
            val status = connection.responseCode
            L7DebugLog.record("Lyrics: stage=http status=$status")
            if (status == 429 || status == HttpURLConnection.HTTP_UNAVAILABLE) {
                retryAfterElapsedMillis = SystemClock.elapsedRealtime() + retryDelayMillis(connection.getHeaderField("Retry-After"))
                return null
            }
            if (status != HttpURLConnection.HTTP_OK) return null
            val contentType = connection.contentType.orEmpty().substringBefore(';').trim()
            if (!contentType.equals("application/json", ignoreCase = true) && !contentType.endsWith("+json", ignoreCase = true)) return null
            val body = connection.inputStream.use { readBounded(it, token) }
            token.check()
            val records = JSONArray(body.toString(Charsets.UTF_8))
            val matches = (0 until minOf(records.length(), MAX_SEARCH_RESULTS)).mapNotNull { records.optJSONObject(it) }
                .filter { matches(query.title, it.optString("trackName")) && matchesArtist(query.artist, it.optString("artistName")) }
            L7DebugLog.record("Lyrics: stage=match candidates=${records.length()} matches=${matches.size}")
            val selected = matches.firstOrNull { it.optString("syncedLyrics").isNotBlank() && it.optString("syncedLyrics") != "null" }
                ?: matches.firstOrNull() ?: return emptyList()
            return GalaxyLrcParser.fromResponse(selected).also { lines ->
                L7DebugLog.record("Lyrics: stage=parse lines=${lines.size} timed=${lines.count { it.timeMillis != null }}")
            }
        } finally {
            token.detach(connection)
            connection.disconnect()
        }
    }

    private fun retryDelayMillis(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(1L, MAX_RETRY_DELAY_MILLIS / 1_000L) * 1_000L
        val dateMillis = header?.let { value -> runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).parse(value)?.time
        }.getOrNull() }
        return dateMillis?.let { (it - System.currentTimeMillis()).coerceIn(1_000L, MAX_RETRY_DELAY_MILLIS) }
            ?: DEFAULT_RETRY_DELAY_MILLIS
    }

    private fun readBounded(stream: InputStream, token: Cancellation): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            token.check()
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_RESPONSE_BYTES) throw IllegalArgumentException("lyrics response too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun matches(requested: String, returned: String): Boolean =
        normalize(requested) == normalize(returned).takeIf { it.isNotEmpty() }

    /** LRCLIB may store duplicate/composite artist credits; match a complete comma-separated credit. */
    private fun matchesArtist(requested: String, returned: String): Boolean {
        if (matches(requested, returned)) return true
        val wanted = normalize(requested)
        if (wanted.isEmpty()) return false
        return returned.split(',').any { normalize(it) == wanted }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("").trim().replace(Regex("\\s+"), " ")

    internal class Cancellation {
        private val cancelled = AtomicBoolean(false)
        private val connection = AtomicReference<HttpURLConnection?>(null)
        fun cancel() { cancelled.set(true); connection.getAndSet(null)?.disconnect() }
        fun isCancelled() = cancelled.get()
        fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("lyrics request cancelled") }
        fun attach(value: HttpURLConnection): Boolean {
            if (cancelled.get()) { value.disconnect(); return false }
            connection.set(value)
            if (cancelled.get()) { connection.compareAndSet(value, null); value.disconnect(); return false }
            return true
        }
        fun detach(value: HttpURLConnection) { connection.compareAndSet(value, null) }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_SEARCH_RESULTS = 20
        const val MAX_RETRY_DELAY_MILLIS = 3_600_000L
        const val DEFAULT_RETRY_DELAY_MILLIS = 60_000L
    }
}

/** One in-flight lookup; replacing the track disconnects the prior request and rejects stale results. */
internal class GalaxyLyricsLookup(
    private val client: GalaxyLyricsHttpClient = GalaxyLyricsHttpClient(),
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "GalaxyPlay-lyrics").apply { isDaemon = true } },
    private val main: Executor = Executor { task -> Handler(Looper.getMainLooper()).post(task) },
) : Closeable {
    private val lock = Any()
    private var generation = 0L
    private var future: Future<*>? = null
    private var cancellation: GalaxyLyricsHttpClient.Cancellation? = null
    private var closed = false

    fun lookup(query: GalaxyLyricQuery, callback: (List<ClusterLyricLine>?) -> Unit) {
        val request: Pair<Long, GalaxyLyricsHttpClient.Cancellation> = synchronized(lock) {
            if (closed) return
            cancelLocked()
            val token = GalaxyLyricsHttpClient.Cancellation()
            cancellation = token
            (++generation) to token
        }
        val (ticket, token) = request
        val job = worker.submit {
            val lines = runCatching { client.lookup(query, token) }.onFailure { error ->
                // 不记录异常消息/查询URL，只留类型、原因类型及源码栈。
                L7DebugLog.record("Lyrics: stage=lookup exceptionType=${error.javaClass.simpleName} causeType=${error.cause?.javaClass?.simpleName ?: "none"} " +
                    error.stackTrace.take(10).joinToString(" | "))
            }.getOrNull()
            if (token.isCancelled()) return@submit
            main.execute {
                synchronized(lock) {
                    if (closed || generation != ticket || cancellation !== token) return@execute
                    future = null
                    cancellation = null
                }
                callback(lines)
            }
        }
        synchronized(lock) {
            if (closed || generation != ticket || cancellation !== token) job.cancel(true) else future = job
        }
    }

    fun cancel() = synchronized(lock) { cancelLocked() }

    private fun cancelLocked() {
        generation++
        cancellation?.cancel()
        cancellation = null
        future?.cancel(true)
        future = null
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; cancelLocked() }
        worker.shutdownNow()
    }
}
