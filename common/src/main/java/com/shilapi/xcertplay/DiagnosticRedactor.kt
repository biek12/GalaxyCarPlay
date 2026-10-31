package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    const val MAX_LINE = 700
    // 保留带下划线的结构化事件名；只拒绝行首/空白后的原始手机与协议转储标记。
    private val rawDump = Regex("(?:^|\\s)(?:TRACE|PHONE)\\s|-----\\s*(?:BEGIN|END)\\b")
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b|\\b[0-9a-f]{8}-[0-9a-f]{16}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    /** Cheap check for lines [redact] always rejects, so a queue can refuse them before storing. */
    fun neverExported(line: String): Boolean =
        line.any { it.code < 32 && it != '\t' || it.code == 127 } ||
            ((line.contains("TRACE") || line.contains("PHONE") || line.contains("-----")) && rawDump.containsMatchIn(line))

    private fun plainMetadata(line: String): Boolean {
        var hexRun = 0
        for (char in line) {
            if (char != ' ' && char !in 'a'..'z' && char !in 'A'..'Z') return false
            hexRun = if (char in 'a'..'f' || char in 'A'..'F') hexRun + 1 else 0
            if (hexRun >= 24) return false
        }
        val lower = line.lowercase(java.util.Locale.ROOT)
        return listOf("pass", "token", "private", "certificate", "pair", "ssid", "trace", "phone")
            .none { lower.contains(it) }
    }

    fun redact(line: String): String? {
        // 纯字母状态文本不可能包含地址或结构化载荷；关键词与长十六进制仍走完整检查。
        if (plainMetadata(line)) return line.take(MAX_LINE)
        if (neverExported(line)) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(MAX_LINE)
    }
}
