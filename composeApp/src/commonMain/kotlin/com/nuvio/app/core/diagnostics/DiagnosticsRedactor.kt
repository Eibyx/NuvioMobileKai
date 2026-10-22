package com.nuvio.app.core.diagnostics

/**
 * Strips secrets out of diagnostic text before it is written to disk or shared.
 *
 * Covers API keys, tokens and passwords (debrid services, Trakt, Simkl, AniList, MDBList, TMDB,
 * AI providers), the configuration part of addon URLs (AIOStreams, Torrentio, Comet...), secret
 * query parameters, Authorization headers and e-mail addresses. Each match becomes [PLACEHOLDER].
 */
object DiagnosticsRedactor {
    const val PLACEHOLDER = "[REDACTADO]"

    private const val SECRET_NAME =
        "(?:[A-Za-z0-9_.-]*(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|id[_-]?token|" +
            "auth[_-]?token|token|secret|password|passwd|passphrase|authorization|signature|cookie|" +
            "client[_-]?id|private[_-]?key|credentials?)[A-Za-z0-9_.-]*|key|sig|pass|pwd|auth)"

    /** `user:password@host` in URLs. */
    private val urlCredentials = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/@:]+:[^\s/@]*@""")

    /** `Authorization: Bearer abc`, `trakt-api-key: abc`, `Cookie: a=b` in logged headers. */
    private val secretHeader = Regex(
        """(?im)(?<![?&/\w-])((?:proxy-)?authorization|cookie|set-cookie|x-[a-z0-9-]*(?:key|token|auth)[a-z0-9-]*|""" +
            """[a-z0-9-]*-api-key|api-key)(\s*[:=]\s*)(?:"([^"\r\n]*)"|([^\r\n,;}&]+))""",
    )

    /** `Bearer abc` / `Basic abc` wherever it appears. */
    private val authScheme = Regex("""(?i)\b(Bearer|Basic|Token)\s+[A-Za-z0-9._~+/=-]{6,}""")

    /** Query and fragment parameters with a secret-looking name: `?token=..`, `&api_key=..`. */
    private val secretQueryParam = Regex("""(?i)([?&#;]$SECRET_NAME=)[^&#\s"'<>]+""")

    /** `"apiKey": "abc"`, `password=abc`, `TRAKT_CLIENT_SECRET=abc` outside of URLs. */
    private val secretAssignment = Regex(
        """(?i)(["']?\b$SECRET_NAME["']?\s*[:=]\s*)(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s,;&}\])"']+))""",
    )

    /** JSON Web Tokens (AniList, Supabase, TMDB v4 read tokens...). */
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}""")

    /** Provider key formats that may show up on their own: OpenAI/Anthropic `sk-..`, Google `AIza..`. */
    private val providerKey = Regex("""\b(?:sk-(?:ant-|proj-)?[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,})""")

    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")

    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s"'<>]+""")

    /** Stremio addon resources; everything between the host and the resource is user configuration. */
    private val addonResource = Regex(
        """(?i)/(manifest\.json|configure|stream|meta|catalog|subtitles|addon_catalog)(?=[/?#]|$)""",
    )

    /** Path segments that carry keys: `realdebrid=KEY|sort=..`, long base64 or hex blobs. */
    private val configSegment = Regex("""(?i)(?:^|[|,])[a-z_]*(?:debrid|torbox|premiumize|offcloud|putio|key|token)[a-z_]*=""")
    private val opaqueSegment = Regex("""^[A-Za-z0-9_\-=+%.~]{32,}$""")

    /** Long mixed letter/digit tokens that are not inside a URL or a stack frame. */
    private val bareToken = Regex("""(?<![A-Za-z0-9_./$-])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}(?![A-Za-z0-9_.-])""")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        result = urlCredentials.replace(result) { "${it.groupValues[1]}$PLACEHOLDER@" }
        result = url.replace(result) { redactUrl(it.value) }
        result = secretHeader.replace(result) { match ->
            if (match.valueFrom(group = 3).startsWith(PLACEHOLDER)) match.value
            else "${match.groupValues[1]}${match.groupValues[2]}$PLACEHOLDER"
        }
        result = authScheme.replace(result) { "${it.groupValues[1]} $PLACEHOLDER" }
        result = secretAssignment.replace(result) { match ->
            if (match.valueFrom(group = 2).startsWith("[")) match.value else "${match.groupValues[1]}$PLACEHOLDER"
        }
        result = jwt.replace(result, PLACEHOLDER)
        result = providerKey.replace(result, PLACEHOLDER)
        result = email.replace(result, PLACEHOLDER)
        result = bareToken.replace(result, PLACEHOLDER)
        return result
    }

    fun redactLines(lines: List<String>): List<String> = lines.map(::redact)

    /** The value of a header or assignment match: the first non-empty group from [group] on. */
    private fun MatchResult.valueFrom(group: Int): String =
        groupValues.drop(group).firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun redactUrl(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return raw
        val afterScheme = schemeEnd + 3
        val pathStart = raw.indexOf('/', afterScheme).takeIf { it >= 0 }
        val queryStart = raw.indexOfAny(charArrayOf('?', '#'), afterScheme).takeIf { it >= 0 }
        val authorityEnd = listOfNotNull(pathStart, queryStart).minOrNull() ?: raw.length
        val origin = raw.substring(0, authorityEnd)
        val pathEnd = queryStart ?: raw.length
        val path = if (pathStart != null && pathStart < pathEnd) raw.substring(pathStart, pathEnd) else ""
        val tail = raw.substring(pathEnd)

        val redactedPath = redactAddonConfig(path)
            .split('/')
            .joinToString("/") { segment ->
                when {
                    segment.isEmpty() || segment == PLACEHOLDER -> segment
                    opaqueSegment.matches(segment) -> PLACEHOLDER
                    configSegment.containsMatchIn(segment) -> PLACEHOLDER
                    else -> segment
                }
            }
        val redactedTail = secretQueryParam.replace(tail) { "${it.groupValues[1]}$PLACEHOLDER" }
        return origin + redactedPath + redactedTail
    }

    private fun redactAddonConfig(path: String): String {
        val resource = addonResource.find(path) ?: return path
        val config = path.substring(0, resource.range.first)
        if (config.trim('/').isEmpty()) return path
        return "/$PLACEHOLDER" + path.substring(resource.range.first)
    }
}
