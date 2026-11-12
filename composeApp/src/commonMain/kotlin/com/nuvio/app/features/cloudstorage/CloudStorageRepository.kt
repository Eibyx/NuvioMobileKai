package com.nuvio.app.features.cloudstorage

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.AppFileCache
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal data class CloudStorageUiState(
    val accounts: List<CloudAccount> = emptyList(),
    val syncing: Set<String> = emptySet(),
)

/**
 * Cloud accounts of the active profile. Account settings and secrets go to [CloudVault] only;
 * the file index to the app's file cache. Nothing here is sent to Supabase, put in a URL, exported
 * with the settings backup or handed to plugins.
 */
internal object CloudStorageRepository {
    private val log = Logger.withTag("CloudStorage")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = SynchronizedObject()
    private val _uiState = MutableStateFlow(CloudStorageUiState())
    val uiState: StateFlow<CloudStorageUiState> = _uiState.asStateFlow()

    private var loadedProfileId: Int? = null
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    /** Opening the app (or coming back to it) re-indexes accounts not indexed for this long. */
    internal const val APP_OPEN_REFRESH_MS = 10 * 60 * 1000L

    /** Opening an episode re-indexes an account not indexed for this long, and waits for it. */
    internal const val EPISODE_REFRESH_MS = 2 * 60 * 1000L
    private const val EPISODE_WAIT_MS = 20_000L
    private val indexes = mutableMapOf<String, CloudIndexSnapshot>()
    private val providers = mutableMapOf<String, CloudProvider>()

    /** Tests replace the network. */
    internal var http: CloudHttp = KtorCloudHttp
    internal var profileIdProvider: () -> Int = { ProfileRepository.activeProfileId }
    internal var nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    fun ensureLoaded() {
        val profileId = profileIdProvider()
        if (loadedProfileId == profileId) return
        load(profileId)
    }

    fun onProfileChanged() = load(profileIdProvider())

    fun accounts(): List<CloudAccount> {
        ensureLoaded()
        return _uiState.value.accounts
    }

    fun account(id: String): CloudAccount? = accounts().firstOrNull { it.id == id }

    fun enabledAccounts(): List<CloudAccount> = accounts().filter { it.enabled && it.folders.isNotEmpty() }

    /** Verifies the cookie with TeraBox before anything is stored. */
    suspend fun addTeraBox(name: String, cookie: String, domain: String? = null): CloudAccount {
        val trimmed = cookie.trim().takeIf { it.isNotEmpty() } ?: throw CloudStorageException(CloudFailure.AUTH)
        if (!domain.isNullOrBlank() && teraBoxOrigin(domain) == null) throw CloudStorageException(CloudFailure.UNSUPPORTED)
        var credentials = CloudCredentials(teraboxCookie = trimmed, teraboxDomain = teraBoxOrigin(domain))
        val api = TeraBoxApi(http, trimmed, credentials.teraboxDomain, onSessionChanged = { token, base ->
            credentials = credentials.copy(teraboxJsToken = token, teraboxBaseUrl = base)
        })
        api.checkLogin()
        return save(CloudProviderKind.TERABOX, name.ifBlank { CloudProviderKind.TERABOX.displayName }, credentials)
    }

    /** Verifies the OAuth client and refresh token with Google before anything is stored. */
    suspend fun addGoogleDrive(name: String, clientId: String, clientSecret: String?, refreshToken: String): CloudAccount {
        if (clientId.isBlank() || refreshToken.isBlank()) throw CloudStorageException(CloudFailure.AUTH)
        var credentials = CloudCredentials(
            driveClientId = clientId.trim(),
            driveClientSecret = clientSecret?.trim()?.takeIf { it.isNotEmpty() },
            driveRefreshToken = refreshToken.trim(),
        )
        val api = GoogleDriveApi(
            http, credentials.driveClientId!!, credentials.driveClientSecret, credentials.driveRefreshToken!!,
            onTokenRefreshed = { token, expiry ->
                credentials = credentials.copy(driveAccessToken = token, driveAccessTokenExpiryEpochMs = expiry)
            },
        )
        val label = api.accountName()
        return save(CloudProviderKind.GOOGLE_DRIVE, name.ifBlank { label ?: CloudProviderKind.GOOGLE_DRIVE.displayName }, credentials)
    }

    /**
     * Stores a verified account with all of its folders selected (the whole drive, from its root)
     * and starts indexing it right away; the user can narrow the folders later.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun save(kind: CloudProviderKind, name: String, credentials: CloudCredentials): CloudAccount {
        ensureLoaded()
        val root = when (kind) {
            CloudProviderKind.GOOGLE_DRIVE -> CloudFolderRef(id = "root", path = "/")
            CloudProviderKind.TERABOX -> CloudFolderRef(id = "/", path = "/")
        }
        val account = CloudAccount(id = Uuid.random().toString(), kind = kind, name = name.trim().take(60), folders = listOf(root))
        writeCredentials(account.id, credentials)
        mutate { it + account }
        syncInBackground(account.id)
        return account
    }

    /** Re-indexes an account whose index is older than [STALE_AFTER_MS], without waiting for it. */
    fun refreshIfStale(account: CloudAccount) {
        val last = account.lastSyncEpochMs ?: 0L
        if (account.folders.isEmpty() || nowMs() - last < STALE_AFTER_MS) return
        syncInBackground(account.id)
    }

    /**
     * When Nuvio opens or comes back: re-indexes, in the background, every enabled account not
     * indexed in the last [APP_OPEN_REFRESH_MS], so new files show up without a manual sync.
     */
    fun refreshOnAppOpen() {
        val accounts = runCatching { enabledAccounts() }.getOrDefault(emptyList())
        accounts.filter { isOlderThan(it, APP_OPEN_REFRESH_MS) }.forEach { account ->
            log.i { "Re-indexing a ${account.kind.id} account on app open" }
            syncInBackground(account.id)
        }
    }

    /**
     * The index to match an episode against: re-indexed first when older than
     * [EPISODE_REFRESH_MS] (or joins a sync already running), waiting at most [EPISODE_WAIT_MS].
     * The sync is never cancelled by the wait: past the limit it finishes in the background.
     */
    suspend fun freshIndex(id: String): CloudIndexSnapshot? {
        val account = account(id) ?: return null
        if (account.folders.isNotEmpty() && isOlderThan(account, EPISODE_REFRESH_MS)) {
            val job = syncInBackground(id)
            val done = withTimeoutOrNull(EPISODE_WAIT_MS) {
                if (job != null) job.join() else uiState.first { id !in it.syncing }
            }
            if (done == null) log.i { "A ${account.kind.id} re-index is still running; matching the previous index" }
        }
        return index(id)
    }

    private fun isOlderThan(account: CloudAccount, ageMs: Long): Boolean =
        nowMs() - (account.lastSyncEpochMs ?: 0L) >= ageMs

    fun syncInBackground(id: String): Job? {
        if (id in _uiState.value.syncing) return null
        return backgroundScope.launch {
            runCatching { sync(id) }.onFailure { error ->
                if (error is CancellationException) throw error
                log.w { "Background sync failed: ${(error as? CloudStorageException)?.failure ?: error::class.simpleName}" }
            }
        }
    }

    fun update(id: String, change: (CloudAccount) -> CloudAccount) {
        mutate { accounts -> accounts.map { if (it.id == id) change(it) else it } }
    }

    fun setFolders(id: String, folders: List<CloudFolderRef>) = update(id) { it.copy(folders = folders.distinctBy { f -> f.id }) }

    fun setManualLink(id: String, link: CloudManualLink) = update(id) { account ->
        account.copy(manualLinks = account.manualLinks.filterNot { it.fileId == link.fileId } + link)
    }

    fun removeManualLink(id: String, fileId: String) = update(id) { account ->
        account.copy(manualLinks = account.manualLinks.filterNot { it.fileId == fileId })
    }

    /** Revokes what the provider allows, then deletes the account, its secrets and its index. */
    suspend fun disconnect(id: String) {
        runCatching { provider(id)?.disconnect() }.onFailure { if (it is CancellationException) throw it }
        val profileId = profileIdProvider()
        CloudVault.write(secretKey(profileId, id), null)
        AppFileCache.write(indexPath(profileId, id), "")
        synchronized(lock) {
            indexes.remove(id)
            providers.remove(id)
        }
        mutate { accounts -> accounts.filterNot { it.id == id } }
    }

    suspend fun verify(id: String): String? {
        val provider = provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        return try {
            provider.verify().also { update(id) { it.copy(lastError = null) } }
        } catch (error: CloudStorageException) {
            update(id) { it.copy(lastError = error.failure.name) }
            throw error
        }
    }

    /** Every file and folder of [folder], unfiltered; for the subtitle folders. */
    suspend fun listAll(id: String, folder: CloudFolderRef): List<CloudListing> =
        (provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)).listAll(folder)

    suspend fun browse(id: String, folder: CloudFolderRef?): List<CloudEntry> =
        (provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)).browse(folder)

    /** Indexes the account's selected folders. Never downloads, moves or changes a file. */
    suspend fun sync(id: String): CloudIndexSnapshot {
        val account = account(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        val provider = provider(id) ?: throw CloudStorageException(CloudFailure.NOT_FOUND)
        var started = false
        _uiState.update { state ->
            started = id !in state.syncing
            if (started) state.copy(syncing = state.syncing + id) else state
        }
        if (!started) return index(id) ?: CloudIndexSnapshot()
        try {
            val snapshot = provider.index(account.folders, index(id))
            storeIndex(id, snapshot)
            update(id) { it.copy(lastSyncEpochMs = nowMs(), indexedFiles = snapshot.files.size, lastError = null) }
            log.i { "Indexed ${snapshot.files.size} videos for a ${account.kind.id} account" }
            return snapshot
        } catch (error: CloudStorageException) {
            update(id) { it.copy(lastError = error.failure.name) }
            throw error
        } finally {
            if (started) _uiState.update { it.copy(syncing = it.syncing - id) }
        }
    }

    fun index(id: String): CloudIndexSnapshot? {
        synchronized(lock) { indexes[id] }?.let { return it }
        val profileId = profileIdProvider()
        val stored = AppFileCache.read(indexPath(profileId, id))?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = runCatching { json.decodeFromString(CloudIndexSnapshot.serializer(), stored) }.getOrNull() ?: return null
        synchronized(lock) { indexes[id] = snapshot }
        return snapshot
    }

    fun file(accountId: String, fileId: String): CloudFile? = index(accountId)?.files?.firstOrNull { it.id == fileId }

    fun provider(id: String): CloudProvider? {
        synchronized(lock) { providers[id] }?.let { return it }
        val account = account(id) ?: return null
        val profileId = profileIdProvider()
        val credentials = readCredentials(profileId, id) ?: return null
        val provider: CloudProvider = when (account.kind) {
            CloudProviderKind.TERABOX -> TeraBoxCloudProvider(
                TeraBoxApi(
                    http = http,
                    cookie = credentials.teraboxCookie ?: return null,
                    domain = credentials.teraboxDomain,
                    jsToken = credentials.teraboxJsToken,
                    baseUrl = credentials.teraboxBaseUrl,
                    onSessionChanged = { token, base ->
                        updateCredentials(profileId, id) { it.copy(teraboxJsToken = token, teraboxBaseUrl = base) }
                    },
                ),
            )
            CloudProviderKind.GOOGLE_DRIVE -> GoogleDriveCloudProvider(
                GoogleDriveApi(
                    http = http,
                    clientId = credentials.driveClientId ?: return null,
                    clientSecret = credentials.driveClientSecret,
                    refreshToken = credentials.driveRefreshToken ?: return null,
                    accessToken = credentials.driveAccessToken,
                    accessTokenExpiryEpochMs = credentials.driveAccessTokenExpiryEpochMs,
                    onTokenRefreshed = { token, expiry ->
                        updateCredentials(profileId, id) {
                            it.copy(driveAccessToken = token, driveAccessTokenExpiryEpochMs = expiry)
                        }
                    },
                ),
            )
        }
        synchronized(lock) { providers[id] = provider }
        return provider
    }

    /** Signing out of the Nuvio account wipes every profile's cloud accounts on this device. */
    fun clearLocalState() {
        runCatching { CloudVault.clear() }.onFailure { log.w(it) { "Unable to clear cloud storage" } }
        synchronized(lock) {
            indexes.clear()
            providers.clear()
        }
        loadedProfileId = null
        _uiState.value = CloudStorageUiState()
    }

    fun removeProfile(profileId: Int) {
        val stored = readAccounts(profileId)
        stored.forEach { account ->
            CloudVault.write(secretKey(profileId, account.id), null)
            AppFileCache.write(indexPath(profileId, account.id), "")
        }
        CloudVault.write(accountsKey(profileId), null)
        if (profileId == profileIdProvider()) load(profileId)
    }

    private fun load(profileId: Int) {
        synchronized(lock) {
            indexes.clear()
            providers.clear()
        }
        loadedProfileId = profileId
        _uiState.value = CloudStorageUiState(accounts = readAccounts(profileId))
    }

    private fun mutate(change: (List<CloudAccount>) -> List<CloudAccount>) {
        val profileId = profileIdProvider()
        if (loadedProfileId != profileId) load(profileId)
        val updated = change(_uiState.value.accounts)
        CloudVault.write(accountsKey(profileId), json.encodeToString(ListSerializer(CloudAccount.serializer()), updated))
        synchronized(lock) {
            val ids = updated.map { it.id }.toSet()
            providers.keys.retainAll(ids)
        }
        _uiState.update { it.copy(accounts = updated) }
    }

    private fun readAccounts(profileId: Int): List<CloudAccount> =
        CloudVault.read(accountsKey(profileId))
            ?.let { runCatching { json.decodeFromString(ListSerializer(CloudAccount.serializer()), it) }.getOrNull() }
            .orEmpty()

    private fun storeIndex(id: String, snapshot: CloudIndexSnapshot) {
        synchronized(lock) { indexes[id] = snapshot }
        AppFileCache.write(indexPath(profileIdProvider(), id), json.encodeToString(CloudIndexSnapshot.serializer(), snapshot))
    }

    private fun writeCredentials(accountId: String, credentials: CloudCredentials) {
        CloudVault.write(secretKey(profileIdProvider(), accountId), json.encodeToString(CloudCredentials.serializer(), credentials))
    }

    private fun readCredentials(profileId: Int, accountId: String): CloudCredentials? =
        CloudVault.read(secretKey(profileId, accountId))
            ?.let { runCatching { json.decodeFromString(CloudCredentials.serializer(), it) }.getOrNull() }

    private fun updateCredentials(profileId: Int, accountId: String, change: (CloudCredentials) -> CloudCredentials) {
        val current = readCredentials(profileId, accountId) ?: return
        CloudVault.write(secretKey(profileId, accountId), json.encodeToString(CloudCredentials.serializer(), change(current)))
    }

    private fun accountsKey(profileId: Int) = "accounts.v1.$profileId"
    private fun secretKey(profileId: Int, accountId: String) = "secret.v1.$profileId.$accountId"
    private fun indexPath(profileId: Int, accountId: String) = "cloud_index/$profileId/$accountId.json"
}
