package com.shilapi.xcertplay

import android.content.Context
import java.lang.reflect.Proxy

/** 复用车机已安装的FDBus/Proto类型，不分发原厂库；本连接只发送已核验的娱乐媒体消息。 */
internal class GalaxyL6OemMediaConnection(context: Context) : AutoCloseable {
    private val loader = context.createPackageContext("com.autolink.diminteraction",
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY).classLoader
    private val type = Class.forName("ipc.fdbus.FdbusClient", true, loader)
    private var client: Any? = null
    @Volatile private var valid = true
    @Volatile private var online = false
    private var lastReply: Int? = null
    private val builders = listOf("PlayMediaInfo", "InteractionData", "InteractionMessage").associateWith {
        Class.forName("com.autolink.interaction.InteractionProto\$$it", true, loader)
    }
    init {
        // 原厂库的JNI注册只在本进程/同一ClassLoader初始化一次。
        synchronized(initialized) {
            if (initialized.add(loader)) try { Class.forName("ipc.fdbus.Fdbus", true, loader).getConstructor().newInstance() }
            catch (error: Throwable) { initialized.remove(loader); throw error }
        }
        val listenerType = Class.forName("ipc.fdbus.FdbusClientListener", true, loader)
        val listener = Proxy.newProxyInstance(loader, arrayOf(listenerType)) { proxy, method, args ->
            when (method.name) {
                "onOnline" -> { if (valid) { online = true; L7DebugLog.record("OemDimMedia: fdbus online=true") }; null }
                "onOffline" -> { online = false; if (valid) L7DebugLog.record("OemDimMedia: fdbus online=false"); null }
                "onReply" -> {
                    if (valid) args?.firstOrNull()?.let { reply ->
                        val status = reply.javaClass.getMethod("returnValue").invoke(reply) as Int
                        if (lastReply != status) { lastReply = status; L7DebugLog.record("OemDimMedia: fdbus replyStatus=$status") }
                    }
                    null
                }
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "GalaxyL6OemMediaListener"
                else -> null
            }
        }
        client = type.getConstructor(String::class.java, listenerType).newInstance("GalaxyPlay-OemMedia", listener)
        try { check(type.getMethod("connect", String::class.java).invoke(client, "svc://com.autolink.cluster_ivi.interaction") == true) }
        catch (error: Throwable) { close(); throw error }
    }
    fun send(value: com.shilapi.xcertplay.media.CarPlayNowPlaying, lyrics: GalaxyOemLyrics, image: String, session: String): Boolean {
        if (!valid || !online) return false
        val info = requireNotNull(builders["PlayMediaInfo"]).getMethod("newBuilder").invoke(null)
        fun set(name: String, arg: Any) {
            val argType = when (arg) { is Int -> Int::class.javaPrimitiveType!!; is Long -> Long::class.javaPrimitiveType!!; else -> String::class.java }
            info.javaClass.getMethod(name, argType).invoke(info, arg)
        }
        set("setSourceType", 6); set("setUuid", session); set("setMuteState", 0)
        set("setTitle", value.title.orEmpty()); set("setAlbum", value.album.orEmpty()); set("setArtist", value.artist.orEmpty())
        set("setArtwork", image); set("setNextArtwork", "-999")
        set("setLyricSentence", lyrics.sentence); set("setLyricContent", lyrics.content); set("setLyricUrl", "")
        set("setDuration", value.durationMillis ?: 0L); set("setCurrentProgress", value.elapsedMillis ?: 0L)
        set("setFavoriteState", 0); set("setLoopMode", 0); set("setMediaPath", "")
        set("setPlaybackStatus", if (value.playing) 1 else 0); set("setPlayingPosition", 0)
        val media = info.javaClass.getMethod("build").invoke(info)
        val data = requireNotNull(builders["InteractionData"]).getMethod("newBuilder").invoke(null)
        data.javaClass.getMethod("setPlayMediaValue", requireNotNull(builders["PlayMediaInfo"])).invoke(data, media)
        val message = requireNotNull(builders["InteractionMessage"]).getMethod("newBuilder").invoke(null)
        val msgType = Class.forName("com.autolink.interaction.InteractionProto\$MsgType", true, loader)
        message.javaClass.getMethod("setMsgType", msgType).invoke(message, msgType.getField("MSG_TYPE_ENTERTAINMENT").get(null))
        message.javaClass.getMethod("setMsgSubtype", Int::class.javaPrimitiveType).invoke(message, 4)
        message.javaClass.getMethod("setMsgData", requireNotNull(builders["InteractionData"])).invoke(message, data.javaClass.getMethod("build").invoke(data))
        val built = message.javaClass.getMethod("build").invoke(message)
        val bytes = built.javaClass.getMethod("toByteArray").invoke(built)
        return type.getMethod("invokeAsync", Int::class.javaPrimitiveType, Any::class.java, Any::class.java)
            .invoke(client, 1, bytes, null) == true
    }
    override fun close() {
        valid = false; online = false
        client?.let { runCatching { type.getMethod("disconnect").invoke(it) }; runCatching { type.getMethod("destroy").invoke(it) } }
        client = null
    }
    private companion object { val initialized = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ClassLoader, Boolean>()) }
}
