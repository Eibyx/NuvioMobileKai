package com.nuvio.app.features.subtitledownloader

import android.content.Context
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.downloads.downloadHttpClient
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSourceLoader
import com.nuvio.app.features.subtitledownloader.engine.BudgetExceededException
import com.nuvio.app.features.subtitledownloader.engine.Detection
import com.nuvio.app.features.subtitledownloader.engine.EngineProgress
import com.nuvio.app.features.subtitledownloader.engine.EngineSettings
import com.nuvio.app.features.subtitledownloader.engine.ExtractionMode
import com.nuvio.app.features.subtitledownloader.engine.ExtractionRequest
import com.nuvio.app.features.subtitledownloader.engine.Integrity
import com.nuvio.app.features.subtitledownloader.engine.MkvTrack
import com.nuvio.app.features.subtitledownloader.engine.RangeNotSupportedException
import com.nuvio.app.features.subtitledownloader.engine.RemoteFileChangedException
import com.nuvio.app.features.subtitledownloader.engine.RemoteHttpException
import com.nuvio.app.features.subtitledownloader.engine.SubtitleSession
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Whether the player is on, so downloads leave it most of the server's connections. */
internal object SubtitleDownloaderPriority {
    private val players = java.util.concurrent.atomic.AtomicInteger()
    val playbackActive: Boolean get() = players.get() > 0
    fun playbackStarted() { players.incrementAndGet() }
    fun playbackStopped() { players.updateAndGet { (it - 1).coerceAtLeast(0) } }
}

/** A detected file, kept with its session so the download reuses what detection already read. */
internal class DetectedSource(
    val key: String,
    val stream: StreamItem,
    val session: SubtitleSession,
    val detection: Detection,
)

/**
 * Subtitle Downloader's queue: jobs persisted to disk (so they resume after the app closes), run
 * a few at a time, each reading its MKV with the extraction engine and saving scripts and fonts.
 */
internal object SubtitleDownloaderRepository {
    private const val MAX_BATCH = 200
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val running = ConcurrentHashMap<String, Job>()
    private val sessions = ConcurrentHashMap<String, DetectedSource>()
    private val streams = ConcurrentHashMap<String, StreamItem>()
    private var loaded = false

    private val _state = MutableStateFlow(SubtitleDownloaderState())
    val state: StateFlow<SubtitleDownloaderState> get() = _state.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, EngineProgress>>(emptyMap())
    val progress: StateFlow<Map<String, EngineProgress>> = _progress.asStateFlow()

    /** One client for every extraction: connections to a CDN stay open from one episode to the next. */
    val client: OkHttpClient by lazy {
        downloadHttpClient.newBuilder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    fun initialize(context: Context) {
        if (loaded) return
        this.context = context.applicationContext
        SubtitleStore.initialize(context)
        loaded = true
        val restored = runCatching { json.decodeFromString(SubtitleDownloaderState.serializer(), stateFile().readText()) }.getOrNull()
        if (restored != null) {
            // Whatever was running when the app closed starts again; its cache keeps what it had read.
            _state.value = restored.copy(jobs = restored.jobs.map {
                if (it.status == JobStatus.RUNNING || it.status == JobStatus.RESOLVING) it.copy(status = JobStatus.QUEUED) else it
            })
        }
        schedule()
    }

    val settings: SubtitleDownloaderSettings get() = _state.value.settings

    fun updateSettings(transform: (SubtitleDownloaderSettings) -> SubtitleDownloaderSettings) {
        _state.update { it.copy(settings = transform(it.settings)) }
        save()
    }

    private fun engineSettings(): EngineSettings {
        val s = settings
        return EngineSettings(
            memoryCacheBytes = s.memoryCacheMb.coerceIn(16, 1024) * 1024L * 1024,
            diskCacheBytes = s.diskCacheMb.coerceIn(0, 4096) * 1024L * 1024,
            cacheRoot = File(context.cacheDir, "subtitle_downloader/ranges"),
        )
    }

    /** Reads a stream's tracks and fonts; the session stays for the download that may follow. */
    suspend fun detect(stream: StreamItem, season: Int?, episode: Int?, onReading: () -> Unit = {}): DetectedSource {
        val source = SubtitleSources.forStream(stream, season, episode)
        sessions[source.stableId]?.let { return it }
        onReading()
        val session = SubtitleSession(client, source, engineSettings())
        applyCeiling(session)
        val detection = session.detect()
        val detected = DetectedSource(source.stableId, stream, session, detection)
        sessions[source.stableId] = detected
        return detected
    }

    fun forget(detected: DetectedSource) {
        sessions.remove(detected.key)
    }

    /** Queues one file's extraction, reusing [detected]'s session. */
    fun enqueue(
        detected: DetectedSource?,
        stream: StreamItem,
        showTitle: String,
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
        tracks: TrackChoice,
        fonts: FontChoice,
        mode: ExtractionMode,
        budgetPercent: Int,
    ): SubtitleJob {
        val now = System.currentTimeMillis()
        val job = SubtitleJob(
            id = UUID.randomUUID().toString(),
            createdAtMs = now,
            showTitle = showTitle,
            contentType = contentType,
            videoId = videoId,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            stream = StreamRef.of(stream),
            tracks = tracks,
            fonts = fonts,
            mode = mode.name,
            budgetPercent = budgetPercent,
            treeUri = settings.treeUri,
        )
        streams[job.id] = stream
        detected?.let { pinned[job.id] = it }
        add(listOf(job))
        return job
    }

    private val pinned = ConcurrentHashMap<String, DetectedSource>()

    /** Queues other episodes of a season: each finds the same release among its own streams when it starts. */
    fun enqueueEpisodes(
        reference: StreamItem,
        referenceSeason: Int?,
        referenceEpisode: Int?,
        showTitle: String,
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        episodes: List<EpisodeTarget>,
        tracks: TrackChoice,
        fonts: FontChoice,
        mode: ExtractionMode,
        budgetPercent: Int,
    ): Int {
        val now = System.currentTimeMillis()
        val batch = UUID.randomUUID().toString()
        val ref = StreamRef.of(reference)
        val jobs = episodes.take(MAX_BATCH).mapIndexed { i, e ->
            SubtitleJob(
                id = UUID.randomUUID().toString(),
                createdAtMs = now + i,
                showTitle = showTitle,
                contentType = contentType,
                videoId = e.videoId,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                season = e.season,
                episode = e.episode,
                episodeTitle = e.title,
                stream = if (e.season == referenceSeason && e.episode == referenceEpisode) ref else null,
                reference = ref,
                referenceSeason = referenceSeason,
                referenceEpisode = referenceEpisode,
                tracks = tracks,
                fonts = fonts,
                mode = mode.name,
                budgetPercent = budgetPercent,
                treeUri = settings.treeUri,
                batchId = batch,
            )
        }
        jobs.firstOrNull { it.stream != null }?.let { streams[it.id] = reference }
        add(jobs)
        return jobs.size
    }

    data class EpisodeTarget(val videoId: String, val season: Int?, val episode: Int?, val title: String?)

    private fun add(jobs: List<SubtitleJob>) {
        _state.update { it.copy(jobs = it.jobs + jobs) }
        save()
        schedule()
        SubtitleDownloadWorker.start(context)
    }

    fun pause(id: String) {
        running.remove(id)?.cancel(CancellationException("paused"))
        update(id) { if (it.isActive) it.copy(status = JobStatus.PAUSED, message = null) else it }
    }

    fun resume(id: String, budgetPercent: Int? = null) {
        update(id) {
            if (it.status in setOf(JobStatus.PAUSED, JobStatus.FAILED, JobStatus.NEEDS_BUDGET, JobStatus.CANCELLED, JobStatus.PARTIAL)) {
                it.copy(status = JobStatus.QUEUED, budgetPercent = budgetPercent ?: it.budgetPercent, message = null)
            } else it
        }
        schedule()
        SubtitleDownloadWorker.start(context)
    }

    fun cancel(id: String) {
        running.remove(id)?.cancel(CancellationException("cancelled"))
        update(id) { if (it.status == JobStatus.DONE || it.status == JobStatus.PARTIAL) it else it.copy(status = JobStatus.CANCELLED) }
    }

    fun pauseAll() = _state.value.jobs.filter { it.isActive }.forEach { pause(it.id) }

    fun resumeAll() = _state.value.jobs.filter { it.status == JobStatus.PAUSED }.forEach { resume(it.id) }

    fun clearHistory() {
        _state.update { s -> s.copy(jobs = s.jobs.filter { it.isActive || it.status == JobStatus.PAUSED || it.status == JobStatus.NEEDS_BUDGET }) }
        save()
    }

    fun remove(id: String) {
        cancel(id)
        _state.update { s -> s.copy(jobs = s.jobs.filterNot { it.id == id }) }
        save()
    }

    val hasPendingWork: Boolean get() = _state.value.jobs.any { it.isActive }

    private fun update(id: String, transform: (SubtitleJob) -> SubtitleJob) {
        _state.update { s -> s.copy(jobs = s.jobs.map { if (it.id == id) transform(it).copy(updatedAtMs = System.currentTimeMillis()) else it }) }
        save()
    }

    private fun schedule() {
        if (!loaded) return
        scope.launch {
            lock.withLock {
                val limit = settings.maxParallelJobs.coerceIn(1, 4)
                val queued = _state.value.jobs.filter { it.status == JobStatus.QUEUED && !running.containsKey(it.id) }
                for (job in queued) {
                    if (running.size >= limit) break
                    val started = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runJob(job.id) }
                    running[job.id] = started
                    started.invokeOnCompletion {
                        running.remove(job.id, started)
                        _progress.update { it - job.id }
                        schedule()
                    }
                    started.start()
                }
                sessions.values.forEach(::applyCeilingDetected)
            }
        }
    }

    private fun applyCeilingDetected(detected: DetectedSource) = applyCeiling(detected.session)

    /** The connections a session may use: the server's eight shared by the jobs running, two while the player is on. */
    private fun applyCeiling(session: SubtitleSession) {
        val share = (8 / running.size.coerceAtLeast(1)).coerceAtLeast(2)
        session.limiter.externalCeiling = if (SubtitleDownloaderPriority.playbackActive) 2 else share
    }

    private fun find(id: String) = _state.value.jobs.firstOrNull { it.id == id }

    private suspend fun runJob(id: String) {
        var job = find(id) ?: return
        try {
            update(id) { it.copy(status = JobStatus.RESOLVING, message = null) }
            val stream = streamFor(job)
            job = find(id) ?: return
            val detected = pinned.remove(id)?.takeIf { it.stream.addonId == stream.addonId } ?: detect(stream, job.season, job.episode)
            val session = detected.session
            val detection = detected.detection
            val selected = selectTracks(job.tracks, detection.layout.assTracks)
            val fontIndexes = detection.attachments.filter { it.isFont && (job.fonts.all || it.name in job.fonts.names) }.map { it.index }.toSet()
            if (selected.isEmpty() && fontIndexes.isEmpty()) {
                update(id) { it.copy(status = JobStatus.FAILED, message = "no-ass") }
                return
            }
            update(id) { it.copy(status = JobStatus.RUNNING) }
            applyCeiling(session)
            val folders = SubtitleStore.episodeFolders(job.showTitle, job.season, job.episode, job.episodeTitle)
            val fontFolders = SubtitleStore.fontFolders(job.showTitle, job.season)
            var saved = 0
            var reused = 0
            var lastLog = 0L
            InAppLogger.info("SubtitleDownloader", "Start ${job.label}: ${detection.strategy}, ${detection.layout.fileSize} bytes, cues ${detection.tracks.map { "${it.indexedWithPosition}/${it.expectedEvents ?: "?"}" }}, ${netSummary(session)}")
            val budget = (detection.layout.fileSize * job.budgetPercent.coerceIn(1, 100) / 100.0).toLong()
            val result = session.extract(
                ExtractionRequest(selected.map { it.number }.toSet(), fontIndexes, ExtractionMode.valueOf(job.mode), budget),
                fontSink = { attachment, bytes, sha ->
                    val written = SubtitleStore.writeFont(job.treeUri, fontFolders, attachment.name, bytes, sha)
                    if (written.reused) reused++ else saved++
                },
            ) { p ->
                applyCeiling(session)
                _progress.update { it + (id to p) }
                val now = System.currentTimeMillis()
                if (now - lastLog >= 10_000) {
                    lastLog = now
                    InAppLogger.info("SubtitleDownloader", "${p.phase} ${p.strategy} ${p.elapsedMs} ms · events ${p.events}/${p.expectedEvents ?: "?"} · fonts ${p.fontsDone}/${p.fontsTotal} · ${netSummary(session)}")
                }
            }
            val names = HashSet<String>()
            val outcomes = result.tracks.map { track ->
                val ext = if (track.track.codecId == MkvTrack.CODEC_SSA) "ssa" else "ass"
                var name = fileName(track.track) + ".$ext"
                var n = 2
                while (!names.add(name)) name = fileName(track.track) + " ($n).$ext".also { n++ }
                val bytes = track.ass
                if (bytes != null) SubtitleStore.writeFile(job.treeUri, folders, name, bytes)
                TrackOutcome(name, track.track.languageCode, track.track.name, track.events, track.expectedEvents, track.integrity.name, track.notes)
            }
            val complete = outcomes.all { it.integrity == Integrity.VERIFIED_STATISTICS.name || it.integrity == Integrity.VERIFIED_STRUCTURE.name || it.integrity == Integrity.CONSISTENT_INDEX.name }
            sessions.remove(detected.key)
            update(id) {
                it.copy(
                    status = if (complete) JobStatus.DONE else JobStatus.PARTIAL,
                    message = null,
                    result = JobResult(
                        tracks = outcomes,
                        fontsSaved = saved,
                        fontsReused = reused,
                        fontsTotal = fontIndexes.size,
                        strategy = result.strategy.name,
                        elapsedMs = result.elapsedMs,
                        trafficBytes = result.trafficBytes,
                        requests = result.requests,
                        fileSize = result.fileSize,
                        folder = (listOf(if (job.treeUri == null) "Download/${SubtitleStore.ROOT}" else (settings.treeLabel ?: "…")) + folders).joinToString("/"),
                        notes = result.notes,
                    ),
                )
            }
            InAppLogger.info("SubtitleDownloader", "Done ${job.label}: ${result.strategy} ${result.elapsedMs} ms · ${outcomes.map { "${it.events}/${it.expected ?: "?"} ${it.integrity}" }} · ${netSummary(session)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: BudgetExceededException) {
            update(id) { it.copy(status = JobStatus.NEEDS_BUDGET, message = "budget") }
        } catch (e: Throwable) {
            val code = when (e) {
                is RangeNotSupportedException -> "no-range"
                is RemoteFileChangedException -> "file-changed"
                is UnsupportedSourceException -> e.message ?: "unsupported"
                is RemoteHttpException -> "http-${e.code}"
                is NoMatchException -> "no-match"
                is com.nuvio.app.features.subtitledownloader.engine.MkvFormatException -> "not-mkv"
                else -> "error"
            }
            InAppLogger.warn("SubtitleDownloader", "Failed ${job.label}: $code ${e.javaClass.simpleName} ${com.nuvio.app.features.subtitlelibrary.LibraryRedaction.text(e.message)}" +
                (sessions.values.firstOrNull()?.let { " · " + netSummary(it.session) } ?: ""))
            update(id) { it.copy(status = JobStatus.FAILED, message = code) }
        }
    }

    /** Requests, traffic and how the server answered: no links, cookies or tokens. */
    private fun netSummary(session: SubtitleSession): String {
        val s = session.stats
        val m = session.model
        return "req ${s.requests.get()} · ${s.trafficBytes / 1024} KiB · limit ${session.limiter.limit}/${session.limiter.externalCeiling} · " +
            "status ${s.statuses()} · overloads ${s.overloads.get()} · retries ${s.retries.get()} · errors ${s.errors.get()} · refreshes ${s.refreshes.get()} · " +
            "redirects ${s.redirects.get()} · conn new ${s.newConnections.get()}/reused ${s.reusedConnections.get()} · h2 ${s.http2Requests.get()} · " +
            "ttfb new ${(m.latencyNew() * 1000).toInt()} ms reused ${(m.latencyReused() * 1000).toInt()} ms · ${(m.connectionBandwidth() / 1024).toInt()} KiB/s"
    }

    private class NoMatchException : Exception("no matching release")

    /** The stream of a job: the one picked, else the same release found among the episode's streams. */
    private suspend fun streamFor(job: SubtitleJob): StreamItem {
        streams[job.id]?.let { return it }
        job.stream?.let { ref ->
            if (ref.cloudConnectionId != null || ref.url != null) return ref.toStream().also { streams[job.id] = it }
        }
        val reference = job.reference ?: job.stream ?: throw NoMatchException()
        val refSeason = job.referenceSeason
        val refEpisode = job.referenceEpisode
        val embedded = runCatching { MetaDetailsRepository.findEmbeddedStreams(job.videoId) }.getOrDefault(emptyList())
        val candidates = StreamSourceLoader.loadStreams(
            type = job.contentType,
            videoId = job.videoId,
            season = job.season,
            episode = job.episode,
            embeddedStreams = embedded,
            timeoutMs = 20_000,
        )
        val match = ReleaseMatcher.best(reference, refSeason, refEpisode, job.season, job.episode, candidates) ?: throw NoMatchException()
        update(job.id) { it.copy(stream = StreamRef.of(match), matchedRelease = StreamRef.of(match).label) }
        streams[job.id] = match
        return match
    }

    fun selectTracks(choice: TrackChoice, tracks: List<MkvTrack>): List<MkvTrack> {
        if (choice.all) return tracks
        val out = LinkedHashSet<MkvTrack>()
        for (key in choice.keys) {
            tracks.firstOrNull { key.number != null && it.number == key.number && it.languageCode == key.language && it.name == key.name }?.let { out += it; continue }
            tracks.firstOrNull { it.languageCode == key.language && it.name == key.name }?.let { out += it; continue }
            tracks.filter { it.languageCode == key.language }.let { out += it }
        }
        if (choice.languages.isNotEmpty()) out += tracks.filter { it.languageCode in choice.languages }
        return out.toList().ifEmpty { if (choice.keys.isEmpty() && choice.languages.isEmpty()) emptyList() else tracks }
    }

    fun fileName(track: MkvTrack): String {
        val language = SubtitleStore.displayLanguage(track.languageCode)
        val name = track.name?.takeIf { it.isNotBlank() }
        return SubtitleStore.safe(if (name != null) "$language - $name" else language)
    }

    fun diagnosticsCacheDir(): File = File(context.cacheDir, "subtitle_downloader")

    private fun stateFile() = File(context.filesDir, "subtitle_downloader/jobs.json")

    private fun save() {
        if (!loaded) return
        val snapshot = _state.value
        runCatching {
            stateFile().parentFile?.mkdirs()
            val tmp = File(stateFile().path + ".tmp")
            tmp.writeText(json.encodeToString(SubtitleDownloaderState.serializer(), snapshot))
            tmp.renameTo(stateFile())
        }
    }
}
