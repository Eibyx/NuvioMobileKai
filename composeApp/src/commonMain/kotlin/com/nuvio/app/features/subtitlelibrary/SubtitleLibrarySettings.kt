package com.nuvio.app.features.subtitlelibrary

import com.nuvio.app.features.cloudstorage.CloudVault
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a subtitle folder lives. */
enum class SubtitleOrigin(val id: String) {
    TERABOX("terabox"),
    DRIVE("drive"),
    LOCAL("local");

    companion object {
        fun fromId(id: String?): SubtitleOrigin? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One configured root folder (`Subtítulos/`). Holds no credential: cloud folders point to a
 * connected account (its tokens stay in the cloud accounts' encrypted store); local folders hold a
 * tree URI picked with the system's document picker (internal storage, SD card or USB drive).
 */
@Serializable
data class SubtitleFolder(
    val id: String,
    val origin: String,
    val displayName: String,
    /** Cloud account id (TeraBox, Drive); null for local folders. */
    val accountId: String? = null,
    /** Drive folder id, TeraBox path or document tree URI. */
    val folderId: String,
    val folderPath: String = "",
    /** Local folders: the storage volume's description ("USB drive", "SD card"), when known. */
    val volume: String? = null,
    val enabled: Boolean = true,
) {
    val originKind: SubtitleOrigin? get() = SubtitleOrigin.fromId(origin)
}

/** A series linked to a folder by hand ("Vincular carpeta…"). */
@Serializable
data class SubtitleFolderLink(val rootId: String, val folderEntryId: String, val folderName: String, val folderPath: String = "")

/** An episode's subtitle picked by hand ("Buscar en mis carpetas…"). */
@Serializable
data class SubtitleFilePick(
    val rootId: String,
    val fileId: String,
    val fileName: String,
    val folderEntryId: String,
    val folderPath: String = "",
    val filePath: String = "",
    val sizeBytes: Long = 0,
    val modifiedEpochMs: Long = 0,
)

@Serializable
data class SubtitleLibrarySettings(
    val folders: List<SubtitleFolder> = emptyList(),
    val autoSelect: Boolean = true,
    val cacheLimitMb: Int = DEFAULT_CACHE_LIMIT_MB,
    /** Series key (`anilist:…`, `kitsu:…`, `tt…`, `tmdb:…`) → linked folder. */
    val links: Map<String, SubtitleFolderLink> = emptyMap(),
    /** Episode key → picked file. */
    val picks: Map<String, SubtitleFilePick> = emptyMap(),
) {
    val enabledFolders: List<SubtitleFolder> get() = folders.filter { it.enabled }

    companion object {
        const val DEFAULT_CACHE_LIMIT_MB = 100
        val CACHE_LIMIT_OPTIONS_MB = listOf(50, 100, 200, 500)
    }
}

/**
 * The subtitle-folder settings of each profile, in the same encrypted, device-only store as the
 * cloud accounts ([CloudVault]): never synced, exported or sent to the account.
 */
object SubtitleLibraryStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(SubtitleLibrarySettings())
    private var loadedProfileId: Int? = null
    private const val MAX_LINKS = 500
    private const val MAX_PICKS = 2000

    internal var profileIdProvider: () -> Int = { ProfileRepository.activeProfileId }

    val settings: StateFlow<SubtitleLibrarySettings>
        get() {
            ensureLoaded()
            return state.asStateFlow()
        }

    fun current(): SubtitleLibrarySettings {
        ensureLoaded()
        return state.value
    }

    fun ensureLoaded() {
        val profileId = profileIdProvider()
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        state.value = runCatching { CloudVault.read(key(profileId)) }.getOrNull()
            ?.let { runCatching { json.decodeFromString(SubtitleLibrarySettings.serializer(), it) }.getOrNull() }
            ?: SubtitleLibrarySettings()
    }

    fun onProfileChanged() {
        loadedProfileId = null
        ensureLoaded()
    }

    fun removeProfile(profileId: Int) {
        runCatching { CloudVault.write(key(profileId), null) }
        if (loadedProfileId == profileId) onProfileChanged()
    }

    fun addFolder(folder: SubtitleFolder) = edit { it.copy(folders = it.folders.filterNot { f -> f.id == folder.id } + folder) }

    fun removeFolder(id: String) = edit { settings ->
        settings.copy(
            folders = settings.folders.filterNot { it.id == id },
            links = settings.links.filterValues { it.rootId != id },
            picks = settings.picks.filterValues { it.rootId != id },
        )
    }

    fun setFolderEnabled(id: String, enabled: Boolean) =
        edit { it.copy(folders = it.folders.map { folder -> if (folder.id == id) folder.copy(enabled = enabled) else folder }) }

    fun setAutoSelect(enabled: Boolean) = edit { it.copy(autoSelect = enabled) }

    fun setCacheLimitMb(limit: Int) = edit { it.copy(cacheLimitMb = limit.coerceIn(10, 2000)) }

    fun link(seriesKey: String, link: SubtitleFolderLink) = edit { settings ->
        settings.copy(links = (settings.links - seriesKey + (seriesKey to link)).entries.toList().takeLast(MAX_LINKS).associate { it.toPair() })
    }

    fun pick(episodeKey: String, pick: SubtitleFilePick) = edit { settings ->
        settings.copy(picks = (settings.picks - episodeKey + (episodeKey to pick)).entries.toList().takeLast(MAX_PICKS).associate { it.toPair() })
    }

    private fun edit(change: (SubtitleLibrarySettings) -> SubtitleLibrarySettings) {
        ensureLoaded()
        val updated = change(state.value)
        state.value = updated
        val profileId = loadedProfileId ?: return
        runCatching { CloudVault.write(key(profileId), json.encodeToString(SubtitleLibrarySettings.serializer(), updated)) }
    }

    private fun key(profileId: Int) = "subtitle_library.v1.$profileId"
}
