package com.nuvio.app.features.subtitlelibrary

/**
 * What subtitle-folder code may write to the log about a link or a request. Signed download links
 * (TeraBox dlinks, Drive media URLs) can carry the credential in the path or the query, so only
 * the scheme and host survive; headers are reduced to their names.
 *
 * Ported from NuvioArcKai (GPL-3.0), with the URL split by hand so it builds for every target.
 */
internal object LibraryRedaction {
    fun url(value: String?): String {
        if (value.isNullOrBlank()) return "<none>"
        val trimmed = value.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return "<redacted>"
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return "<redacted>"
        if (scheme == "file" || scheme == "content") return "$scheme://<local>"
        val authority = trimmed.substring(schemeEnd + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        return if (host.isBlank()) "$scheme://<redacted>" else "$scheme://$host/<redacted>"
    }

    fun headers(headers: Map<String, String>?): String =
        headers.orEmpty().keys.sorted().joinToString(prefix = "[", postfix = "]")

    /** Any text that may contain a link (an exception message): every URL in it is reduced by [url]. */
    fun text(message: String?): String {
        if (message.isNullOrEmpty()) return ""
        return URL_PATTERN.replace(message) { url(it.value) }
            .replace(BEARER_PATTERN, "Bearer <redacted>")
            .replace(COOKIE_PATTERN, "$1<redacted>")
    }

    private val URL_PATTERN = Regex("""(?i)\b(?:https?|content|file)://[^\s"'<>]+""")
    private val BEARER_PATTERN = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")
    private val COOKIE_PATTERN = Regex("""(?i)((?:cookie|ndus|BDUSS|STOKEN|jsToken|access_token|refresh_token)\s*[=:]\s*)[^;\s,&]+""")
}
