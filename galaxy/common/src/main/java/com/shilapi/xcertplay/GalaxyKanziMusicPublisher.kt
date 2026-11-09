package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.galaxy.auto.client.AutoClient
import com.galaxy.auto.contract.AutoContract
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.util.UUID

/** CarPlay的产品适配；Shell及Root仍只走AutoService。发布有界当前态，不保留听歌历史。 */
internal class GalaxyKanziMusicPublisher(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val session = UUID(nextEpoch(), UUID.randomUUID().leastSignificantBits)
    private var revision = 0L
    private var client: AutoClient? = null
    private var ready = false
    private var busy = false
    private var closed = false
    private var ownFocus = false
    private var value = CarPlayNowPlaying()
    private var lyrics = GalaxyOemLyrics()
    private var writeTicket = 0L
    private var timeout: Runnable? = null
    private var announced: Boolean? = null
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            publish()
            if (ownFocus) main.postDelayed(this, 1000)
        }
    }

    init { main.post {
        if (!closed) client = AutoClient(app, AutoContract.OBSERVER,
            onSnapshot = { if (!closed) { ready = true; publish() } },
            onState = { state -> if (state in setOf("service_disconnected", "binding_died", "binding_rejected", "service_unavailable")) ready = false }).also { it.connect() }
    } }

    fun update(next: CarPlayNowPlaying, phoneLyrics: GalaxyOemLyrics, focused: Boolean) = main.post {
        if (!closed) {
            value = next; lyrics = phoneLyrics; ownFocus = focused
            main.removeCallbacks(tick)
            publish()
            if (focused) main.postDelayed(tick, 1000)
        }
    }

    private fun publish(finishing: Boolean = false) {
        if ((!finishing && closed) || !ready || busy) return
        val file = File.createTempFile("kanzi-music-", ".bin", app.cacheDir)
        try { file.writeBytes(GalaxyKanziMusicFrame.encode(value, lyrics, ownFocus, session,
            ++revision, System.currentTimeMillis())) }
        catch (error: Exception) { file.delete(); report(false); return }
        busy = true
        val ticket = ++writeTicket
        fun complete(ok: Boolean) {
            file.delete()
            if (ticket != writeTicket || !busy) return
            timeout?.let(main::removeCallbacks); timeout = null; busy = false
            report(ok)
            if (closed) finish()
        }
        timeout = Runnable { complete(false) }.also { main.postDelayed(it, 6500) }
        // 实车shared/images拒绝Android Root写入；使用已核验可写的OTA桥自有目录，不改OEM权限。
        // 原始文本只在二进制文件内，不拼接到Shell。
        val destination = DESTINATION
        val temporary = "$destination.$session.tmp"
        val command = "mkdir -p '$DIRECTORY' && chmod 755 '$DIRECTORY' && cat '${file.absolutePath}' > '$temporary' && chmod 644 '$temporary' && mv '$temporary' '$destination'"
        client?.executeShell("ROOT", command, 5000) { result ->
            val ok = result.getInt("exitCode", -1) == 0 && !result.getBoolean("timedOut") && !result.containsKey("error")
            complete(ok) // 串行发布退出态；超时和迟到回调均绑定本次ticket。
        }
    }

    private fun report(ok: Boolean) {
        if (announced != ok) { announced = ok; L7DebugLog.record("KanziMusic: current-state written=$ok") }
    }

    private var stopping = false
    private fun finish() {
        if (busy || stopping) return
        stopping = true
        // 关闭后不续租；7秒租约到期后由原生组件轮询恢复。能通信时额外写一份立即退出态。
        val file = File.createTempFile("kanzi-stop-", ".bin", app.cacheDir)
        runCatching { file.writeBytes(GalaxyKanziMusicFrame.encode(value, GalaxyOemLyrics(), false,
            session, ++revision, System.currentTimeMillis())) }.onFailure { file.delete() }
        val service = client
        if (ready && service != null && file.exists()) {
            val destination = DESTINATION
            var done = false
            val cleanup = Runnable { if (!done) { done = true; file.delete(); service.close(); client = null } }
            main.postDelayed(cleanup, 6500)
            service.executeShell("ROOT", "mkdir -p '$DIRECTORY' && chmod 755 '$DIRECTORY' && cat '${file.absolutePath}' > '$destination.$session.tmp' && chmod 644 '$destination.$session.tmp' && mv '$destination.$session.tmp' '$destination'", 5000) {
                main.removeCallbacks(cleanup); cleanup.run()
            }
        } else { file.delete(); service?.close(); client = null }
    }

    companion object {
        private const val DIRECTORY = "/data/vendor/nfs/nfs_ota/galaxy-kanzi-field"
        private const val DESTINATION = "$DIRECTORY/galaxy-carplay-kanzi-music.bin"
        /** 只有显式构建的Kanzi试验APK携带此合同；普通CarPlay包不启动本试验的数据发布。 */
        fun bundled(context: Context): Boolean = runCatching {
            context.assets.open("kanzi-music/component.json").use { input ->
                org.json.JSONObject(input.bufferedReader().readText()).getInt("schema") == 1
            }
        }.getOrDefault(false)
        private val epoch = java.util.concurrent.atomic.AtomicLong()
        // 合同中session首个64位为发布时间代次；原生端可拒绝旧会话迟到的退出态。
        private fun nextEpoch(): Long = epoch.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
    }
    override fun close() { main.post {
        if (!closed) { closed = true; ownFocus = false; main.removeCallbacks(tick); finish() }
    } }
}
