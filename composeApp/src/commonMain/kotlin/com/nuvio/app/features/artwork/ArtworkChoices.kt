package com.nuvio.app.features.artwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the chosen artwork and the Fanart.tv key are kept, on the device, across restarts. */
internal expect object ArtworkChoiceStorage {
    fun loadChoices(): String?
    fun saveChoices(value: String)
    fun loadFanartKey(): String?
    fun saveFanartKey(value: String?)
}

/** The artwork picked for one title; null fields keep what the metadata addon gives. */
@Serializable
data class ArtworkChoice(
    val logo: String? = null,
    val background: String? = null,
    val poster: String? = null,
) {
    val isEmpty: Boolean get() = logo == null && background == null && poster == null
}

enum class ArtworkKind { LOGO, BACKGROUND, POSTER }

/** The artwork the user picked per title, by the title's id. */
object ArtworkChoices {
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val _state = MutableStateFlow<Map<String, ArtworkChoice>>(emptyMap())

    val state: StateFlow<Map<String, ArtworkChoice>>
        get() {
            ensureLoaded()
            return _state.asStateFlow()
        }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val stored = runCatching { ArtworkChoiceStorage.loadChoices() }.getOrNull()
        if (!stored.isNullOrBlank()) {
            _state.value = runCatching { json.decodeFromString<Map<String, ArtworkChoice>>(stored) }.getOrDefault(emptyMap())
        }
    }

    fun choice(id: String?): ArtworkChoice? {
        ensureLoaded()
        return id?.let { _state.value[it] }
    }

    fun logo(id: String?, fallback: String?): String? = choice(id)?.logo ?: fallback
    fun background(id: String?, fallback: String?): String? = choice(id)?.background ?: fallback
    fun poster(id: String?, fallback: String?): String? = choice(id)?.poster ?: fallback

    fun set(id: String, kind: ArtworkKind, url: String?) {
        ensureLoaded()
        _state.update { map ->
            val current = map[id] ?: ArtworkChoice()
            val next = when (kind) {
                ArtworkKind.LOGO -> current.copy(logo = url)
                ArtworkKind.BACKGROUND -> current.copy(background = url)
                ArtworkKind.POSTER -> current.copy(poster = url)
            }
            if (next.isEmpty) map - id else map + (id to next)
        }
        save()
    }

    fun reset(id: String) {
        ensureLoaded()
        _state.update { it - id }
        save()
    }

    private fun save() {
        runCatching { ArtworkChoiceStorage.saveChoices(json.encodeToString(_state.value)) }
    }

    var fanartKey: String?
        get() = runCatching { ArtworkChoiceStorage.loadFanartKey() }.getOrNull()?.takeIf { it.isNotBlank() }
        set(value) {
            runCatching { ArtworkChoiceStorage.saveFanartKey(value?.trim()?.takeIf { it.isNotEmpty() }) }
        }
}

/** The artwork picked for [id], recomposing when it changes. */
@Composable
fun rememberArtworkChoice(id: String?): ArtworkChoice? {
    val choices by ArtworkChoices.state.collectAsState()
    return id?.let { choices[it] }
}
