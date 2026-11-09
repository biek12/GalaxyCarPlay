package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayNowPlaying

/**
 * 手机歌词模式把 artist 改成歌名、title 改成“歌名 - 歌手”后再逐句更新。
 * 仅凭这组可核对的字段关系识别模式，固定曲名/歌手；播放时钟和封面仍跟随手机。
 * 普通歌曲改名仍作为切歌，不能无条件忽略 title 或跨手机会话保留旧歌词。
 */
internal class GalaxyNowPlayingIdentity {
    private var track = CarPlayNowPlaying()
    private var lyricTitle = false
    val lyricTitleMode get()=lyricTitle

    fun resolve(raw: CarPlayNowPlaying): CarPlayNowPlaying {
        if (raw == CarPlayNowPlaying()) { reset(); return raw }
        val title = track.title?.takeIf { it.isNotBlank() }
        val sourceChanged = track.sourceApp != null && raw.sourceApp != track.sourceApp
        val carrier = !sourceChanged && title != null && !track.artist.isNullOrBlank() && raw.artist == title &&
            (lyricTitle || raw.title != title && raw.artist != track.artist)
        val resolved = if (carrier) raw.copy(title = title, artist = track.artist) else raw
        lyricTitle = carrier
        track = resolved
        return resolved
    }

    fun reset() { track = CarPlayNowPlaying(); lyricTitle = false }
}
