package com.nuvio.app.features.subtitledownloader

import com.nuvio.app.features.streams.FilenameEpisodeMatcher
import com.nuvio.app.features.streams.StreamItem

/**
 * Finds, among an episode's streams, the same release as the one picked for another episode:
 * the file name the reference's name becomes for this episode, else the same addon with the most
 * release traits in common (group, resolution, codec, source, audio).
 */
internal object ReleaseMatcher {
    private val GROUP = Regex("""^\[([^\]]+)]|-([A-Za-z0-9]+)(?:\.[A-Za-z0-9]{2,4})?$""")
    private val TRAITS = listOf(
        Regex("""\b(2160p|1080p|720p|480p|4k)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(x265|x264|hevc|h\.?265|h\.?264|avc|av1)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(web-?dl|web-?rip|blu-?ray|bdrip|remux|hdtv|dvd)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(dual|multi|multi-?sub|dual-?audio)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(10-?bit|hdr|dv|dolby\s?vision)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(cr|amzn|nf|dsnp|hidive|adn|b-?global)\b""", RegexOption.IGNORE_CASE),
    )

    fun best(reference: StreamRef, referenceSeason: Int?, referenceEpisode: Int?, season: Int?, episode: Int?, candidates: List<StreamItem>): StreamItem? {
        if (candidates.isEmpty()) return null
        val refName = reference.filename ?: reference.title ?: reference.name.orEmpty()
        if (referenceEpisode != null && episode != null && refName.isNotBlank()) {
            val expected = FilenameEpisodeMatcher.buildNextFilename(refName, referenceSeason, referenceEpisode, season, episode)
            candidates.firstOrNull { c ->
                FilenameEpisodeMatcher.matches(c.behaviorHints.filename, expected) || FilenameEpisodeMatcher.matches(c.title, expected) ||
                    FilenameEpisodeMatcher.matches(c.name, expected)
            }?.let { return it }
        }
        val refText = listOfNotNull(reference.name, reference.title, reference.filename).joinToString(" ")
        val refTraits = traits(refText)
        val refGroup = group(reference.filename ?: reference.title ?: reference.name)
        val scored = candidates.map { c ->
            val text = listOfNotNull(c.name, c.title, c.behaviorHints.filename).joinToString(" ")
            var score = 0
            if (c.addonId == reference.addonId) score += 10
            if (reference.cloudConnectionId != null && c.serverTarget?.item?.connectionId == reference.cloudConnectionId) score += 10
            if (reference.bingeGroup != null && c.behaviorHints.bingeGroup == reference.bingeGroup) score += 12
            if (refGroup != null && group(c.behaviorHints.filename ?: c.title ?: c.name)?.equals(refGroup, true) == true) score += 8
            val t = traits(text)
            score += refTraits.count { it in t } * 2
            c to score
        }
        val (stream, score) = scored.maxByOrNull { it.second } ?: return null
        // Not even the same addon or anything in common: no match rather than a different release.
        return stream.takeIf { score >= 10 }
    }

    fun group(name: String?): String? {
        val value = name?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = GROUP.find(value) ?: return null
        return (match.groupValues[1].ifEmpty { match.groupValues[2] }).takeIf { it.isNotBlank() }
    }

    fun traits(text: String): Set<String> =
        TRAITS.flatMap { regex -> regex.findAll(text).map { it.value.lowercase().replace(Regex("[-. ]"), "") } }.toSet()
}
