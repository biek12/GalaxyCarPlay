package com.shilapi.xcertplay

import java.util.Locale

/** 原厂完整LRC与逐句通道共用一份歌词；无时间戳时保留纯文本。 */
internal data class GalaxyOemLyrics(val content: String = "", val sentence: String = "") {
    companion object {
        fun from(state: ClusterMusicState.Snapshot, now: Long): GalaxyOemLyrics {
            val content = state.lyrics.take(1000).joinToString("\n") { line ->
                val time = line.timeMillis?.coerceAtLeast(0L)
                val prefix = if (time == null) "" else String.format(Locale.ROOT,
                    "[%02d:%02d.%02d]", time / 60000, time / 1000 % 60, time / 10 % 100)
                prefix + line.text.replace('\n', ' ').replace('\r', ' ').take(500)
            }.take(256000)
            return GalaxyOemLyrics(content, state.lyricAt(now)?.text.orEmpty().take(500))
        }
    }
}
