package com.nuvio.app.core.logging

private val localStreamToken = Regex("""(?i)((?:127\.0\.0\.1|localhost)(?::\d+)?/v1/)[0-9a-f]{64}""")

/**
 * Hides the per-session token of the local cloud streaming server
 * (`http://127.0.0.1:<port>/v1/<token>/<file>`), wherever such a URL ends up in a log line: the
 * player logs its source URL, and mpv and ExoPlayer quote it in their errors.
 */
internal fun redactLocalStreamTokens(text: String): String =
    if ("/v1/" !in text) text else localStreamToken.replace(text) { "${it.groupValues[1]}<token>" }
