package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    const val MAX_LINE = 700
    // 保留带下划线的结构化事件名；只拒绝行首/空白后的原始手机与协议转储标记。
    private val rawDump = Regex("(?:^|\\s)(?:TRACE|PHONE)\\s|-----\\s*(?:BEGIN|END)\\b")
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    fun redact(line: String): String? {
        if (rawDump.containsMatchIn(line) || line.any { it.code < 32 && it != '\t' || it.code == 127 }) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(MAX_LINE)
    }
}
