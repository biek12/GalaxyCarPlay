package com.galaxy.auto.lyrics

data class ClusterLyricLine(val timeMillis: Long?,val text: String)
/** 只记录阶段/数量；调用方按自身日志策略接入，不记录曲目和URL。 */
object LyricDiagnostics { @Volatile var sink: ((String)->Unit)?=null; fun record(message: String){sink?.invoke(message)} }
