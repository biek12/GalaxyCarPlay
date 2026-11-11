package com.galaxy.auto.contract

/** 封面传输号、播放位置不属于歌曲身份；未知provider ID保持空值。 */
object MediaIdentity {
    fun key(source: String,app: String,title: String,artist: String,album: String,id: String=""): String {
        val value=listOf(source,app,id,title,artist,album).joinToString("\u0000")
        return java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}
