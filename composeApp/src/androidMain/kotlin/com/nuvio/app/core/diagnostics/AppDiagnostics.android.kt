package com.nuvio.app.core.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.player.PlayerSettingsRepository
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.diagnostics_share_title
import org.jetbrains.compose.resources.getString

actual object AppDiagnostics {
    actual val isSupported: Boolean = true

    actual val canSimulateCrash: Boolean
        get() = appContext?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } == true

    private val _notice = MutableStateFlow<UnexpectedExitNotice?>(null)
    actual val notice: StateFlow<UnexpectedExitNotice?> = _notice.asStateFlow()

    private val _status = MutableStateFlow(DiagnosticsStatus())
    actual val status: StateFlow<DiagnosticsStatus> = _status.asStateFlow()

    private const val TAG = "Diagnostics"
    private const val PREFS = "nuvio_kai_diagnostics"
    private const val KEY_NOTICE_REPORT = "notice_report"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val KEY_SESSION_PID = "session_pid"
    private const val MAX_REPORTS = 5
    private const val LOG_FILE_MAX_BYTES = 512L * 1024
    private const val LOG_FILE_KEEP_BYTES = 256L * 1024
    private const val REPORT_LOG_LINES = 400
    private const val EXPORT_LOG_LINES = 1_500
    private const val EXPORT_PREVIOUS_LOG_LINES = 300
    private const val MAX_TRACE_CHARS = 24_000
    private const val FLUSH_INTERVAL_MS = 2_000L

    private var appContext: Context? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var reportsDir: File
    private lateinit var logsDir: File
    private val pendingLines = ConcurrentLinkedQueue<String>()
    private val logLock = ReentrantLock()
    private var writer: ScheduledExecutorService? = null

    private val currentLog: File get() = File(logsDir, "current-session.log")
    private val previousLog: File get() = File(logsDir, "previous-session.log")

    /** Called first thing in Application.onCreate, before anything can crash or log. */
    fun initialize(application: Application) {
        if (appContext === application) return
        val firstStart = appContext == null
        appContext = application
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = File(application.filesDir, "diagnostics")
        reportsDir = File(root, "reports").apply { mkdirs() }
        logsDir = File(root, "logs").apply { mkdirs() }

        val previousPid = prefs.getInt(KEY_SESSION_PID, -1)
        prefs.edit().putInt(KEY_SESSION_PID, Process.myPid()).apply()
        rotateSessionLog()
        if (firstStart) {
            installCrashHandler()
            startLogWriter()
        }

        writer?.execute {
            runCatching { recordSystemExitReasons(application, previousPid) }
                .onFailure { Log.w(TAG, "Could not read exit reasons", it) }
            loadNotice()
            refreshStatus()
        }
    }

    actual fun dismissNotice() {
        if (appContext == null) return
        prefs.edit().remove(KEY_NOTICE_REPORT).apply()
        _notice.value = null
    }

    actual suspend fun exportAndShare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val context = checkNotNull(appContext) { "Diagnostics are not initialised" }
            flushLog()
            val text = DiagnosticsReportFormatter.format(
                summary = buildSummary(context),
                reports = reportFiles().map { it.toStoredReport() },
                logSections = buildList {
                    add("Latest log lines (this session)" to currentLog.tailLines(EXPORT_LOG_LINES))
                    val previous = previousLog.tailLines(EXPORT_PREVIOUS_LOG_LINES)
                    if (previous.isNotEmpty()) add("Last log lines of the previous session" to previous)
                },
            )
            val exportDir = File(context.cacheDir, "diagnostics").apply {
                deleteRecursively()
                mkdirs()
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(exportDir, "nuvio-kai-diagnostics-$stamp.txt").apply { writeText(text) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val title = getString(Res.string.diagnostics_share_title)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            InAppLogger.info(TAG, "Exported diagnostics (${text.length} chars, ${reportFiles().size} reports)")
        }.onFailure { error ->
            InAppLogger.error(TAG, "Diagnostics export failed: ${InAppLogger.throwableSummary(error)}")
        }
    }

    actual suspend fun clear() = withContext(Dispatchers.IO) {
        if (appContext == null) return@withContext
        logLock.lock()
        try {
            pendingLines.clear()
            reportsDir.listFiles()?.forEach(File::delete)
            previousLog.delete()
            currentLog.writeText(sessionHeader())
        } finally {
            logLock.unlock()
        }
        dismissNotice()
        refreshStatus()
        InAppLogger.info(TAG, "Diagnostics cleared")
    }

    actual fun simulateCrash() {
        if (!canSimulateCrash) return
        InAppLogger.warn(TAG, "Simulated crash requested from Settings")
        Handler(Looper.getMainLooper()).post {
            throw IllegalStateException("Simulated crash from Settings > Advanced > Debugging")
        }
    }

    // region Crash capture

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashReport(thread, throwable) }
                .onFailure { Log.e(TAG, "Could not save the crash report", it) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    internal fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val now = System.currentTimeMillis()
        // Best effort: the writer thread may hold the lock, and a dying process cannot wait long.
        if (logLock.tryLock(500, TimeUnit.MILLISECONDS)) {
            try {
                appendPendingLines()
            } finally {
                logLock.unlock()
            }
        }
        val body = buildString {
            appendLine("Kind: ${UnexpectedExitKind.Crash.name}")
            appendLine("Time: ${formatTime(now)}")
            appendLine("Pid: ${Process.myPid()}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine()
            appendLine(Log.getStackTraceString(throwable).trimEnd())
            appendLine()
            appendLine("--- Last log lines before the crash ---")
            InAppLogger.entries.value.takeLast(REPORT_LOG_LINES).forEach { appendLine(it.line) }
        }
        val file = saveReport(now, UnexpectedExitKind.Crash, body)
        // commit(), not apply(): the process is about to die.
        prefs.edit().putString(KEY_NOTICE_REPORT, file.name).commit()
    }

    private fun recordSystemExitReasons(context: Context, previousPid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = activityManager.getHistoricalProcessExitReasons(null, 0, 16)
        val lastSeen = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, -1L)
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, maxOf(newest, lastSeen)).apply()
        // First run with diagnostics: older exits predate this version, so record nothing.
        if (lastSeen < 0) return

        val crashPids = reportFiles().mapNotNull { it.headerValue("Pid")?.toIntOrNull() }.toSet()
        exits.filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
            .forEach { exit ->
                val kind = exit.unexpectedKind(crashPids) ?: return@forEach
                val body = describeExit(exit, kind, attachPreviousLog = exit.pid == previousPid)
                val file = saveReport(exit.timestamp, kind, body)
                prefs.edit().putString(KEY_NOTICE_REPORT, file.name).apply()
                InAppLogger.warn(TAG, "Previous run ended: ${kind.name} (${exit.description ?: "no description"})")
            }
    }

    private fun ApplicationExitInfo.unexpectedKind(crashPids: Set<Int>): UnexpectedExitKind? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (reason) {
            // Our own handler already saved Java crashes; this catches ones that happened before it ran.
            ApplicationExitInfo.REASON_CRASH -> if (pid in crashPids) null else UnexpectedExitKind.Crash
            ApplicationExitInfo.REASON_CRASH_NATIVE -> UnexpectedExitKind.NativeCrash
            ApplicationExitInfo.REASON_ANR -> UnexpectedExitKind.Anr
            // Background low-memory kills are normal Android behaviour; only ones while in use count.
            ApplicationExitInfo.REASON_LOW_MEMORY ->
                if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
                    UnexpectedExitKind.LowMemory
                } else {
                    null
                }
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> UnexpectedExitKind.Other
            else -> null
        }
    }

    private fun describeExit(exit: ApplicationExitInfo, kind: UnexpectedExitKind, attachPreviousLog: Boolean): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return buildString {
            appendLine("Kind: ${kind.name}")
            appendLine("Time: ${formatTime(exit.timestamp)}")
            appendLine("Pid: ${exit.pid}")
            appendLine("Process: ${exit.processName}")
            appendLine("System reason: ${exit.reasonName()} (status ${exit.status})")
            appendLine("Description: ${exit.description ?: "none"}")
            appendLine("Importance: ${exit.importance}")
            appendLine("Memory: pss ${exit.pss / 1024} MB, rss ${exit.rss / 1024} MB")
            if (kind == UnexpectedExitKind.Anr) {
                exit.readTrace()?.let { trace ->
                    appendLine()
                    appendLine("--- ANR thread dump (main thread first) ---")
                    appendLine(trace)
                }
            }
            if (attachPreviousLog) {
                appendLine()
                appendLine("--- Last log lines before the exit ---")
                previousLog.tailLines(REPORT_LOG_LINES).forEach(::appendLine)
            }
        }
    }

    private fun ApplicationExitInfo.readTrace(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val raw = runCatching {
            traceInputStream?.use { it.readBytes().decodeToString() }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val mainStart = raw.indexOf("\"main\"")
        val ordered = if (mainStart > 0) {
            val mainEnd = raw.indexOf("\n\n", mainStart).takeIf { it > 0 } ?: raw.length
            raw.substring(mainStart, mainEnd) + "\n\n" + raw.removeRange(mainStart, mainEnd)
        } else {
            raw
        }
        return ordered.take(MAX_TRACE_CHARS)
    }

    private fun ApplicationExitInfo.reasonName(): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
        else -> "reason $reason"
    }

    // endregion

    // region Reports

    private fun saveReport(timestamp: Long, kind: UnexpectedExitKind, body: String): File {
        val file = File(reportsDir, "report-$timestamp-${kind.name}.txt")
        file.writeText(DiagnosticsRedactor.redact(body))
        reportFiles().drop(MAX_REPORTS).forEach(File::delete)
        refreshStatus()
        return file
    }

    /** Newest first. */
    internal fun reportFiles(): List<File> =
        reportsDir.listFiles { file -> file.name.startsWith("report-") && file.name.endsWith(".txt") }
            .orEmpty()
            .sortedByDescending { it.reportTimestamp() }

    private fun File.reportTimestamp(): Long = name.split('-').getOrNull(1)?.toLongOrNull() ?: lastModified()

    private fun File.reportKind(): UnexpectedExitKind =
        name.removeSuffix(".txt").substringAfterLast('-').let { value ->
            UnexpectedExitKind.entries.firstOrNull { it.name == value }
        } ?: UnexpectedExitKind.Other

    private fun File.headerValue(name: String): String? =
        runCatching { useLines { lines -> lines.take(12).firstOrNull { it.startsWith("$name: ") } } }
            .getOrNull()
            ?.substringAfter(": ")

    private fun File.toStoredReport(): StoredDiagnosticReport =
        StoredDiagnosticReport(
            title = "${reportKind().name} at ${formatTime(reportTimestamp())}",
            body = runCatching { readText() }.getOrDefault("(unreadable report)"),
        )

    private fun loadNotice() {
        val name = prefs.getString(KEY_NOTICE_REPORT, null) ?: return
        val file = File(reportsDir, name)
        if (!file.exists()) {
            prefs.edit().remove(KEY_NOTICE_REPORT).apply()
            return
        }
        _notice.value = UnexpectedExitNotice(
            reportId = name,
            kind = file.reportKind(),
            timestampMillis = file.reportTimestamp(),
        )
    }

    private fun refreshStatus() {
        _status.value = DiagnosticsStatus(
            reportCount = reportFiles().size,
            savedLogBytes = currentLog.length() + previousLog.length(),
        )
    }

    // endregion

    // region Saved log

    private fun rotateSessionLog() {
        if (currentLog.exists() && currentLog.length() > 0) {
            previousLog.delete()
            currentLog.renameTo(previousLog)
        }
        currentLog.writeText(sessionHeader())
    }

    private fun sessionHeader(): String =
        "=== Session started ${formatTime(System.currentTimeMillis())} (pid ${Process.myPid()}) ===\n"

    private fun startLogWriter() {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-diagnostics").apply { isDaemon = true }
        }
        writer = executor
        InAppLogger.sink = { entry -> pendingLines.add(entry.line) }
        executor.scheduleWithFixedDelay(
            { runCatching { flushLog() }.onFailure { Log.w(TAG, "Log flush failed", it) } },
            FLUSH_INTERVAL_MS,
            FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun flushLog() {
        logLock.lock()
        try {
            appendPendingLines()
            if (currentLog.length() > LOG_FILE_MAX_BYTES) trimLog()
        } finally {
            logLock.unlock()
        }
    }

    private fun appendPendingLines() {
        if (pendingLines.isEmpty()) return
        val batch = buildString {
            while (true) {
                val line = pendingLines.poll() ?: break
                appendLine(DiagnosticsRedactor.redact(line))
            }
        }
        currentLog.appendText(batch)
    }

    /** Keeps the last [LOG_FILE_KEEP_BYTES], cut at a line start. */
    private fun trimLog() {
        val keep = RandomAccessFile(currentLog, "r").use { file ->
            val start = (file.length() - LOG_FILE_KEEP_BYTES).coerceAtLeast(0)
            file.seek(start)
            val bytes = ByteArray((file.length() - start).toInt())
            file.readFully(bytes)
            bytes.decodeToString().substringAfter('\n')
        }
        currentLog.writeText(keep)
    }

    private fun File.tailLines(count: Int): List<String> =
        if (exists()) runCatching { readLines().takeLast(count) }.getOrDefault(emptyList()) else emptyList()

    // endregion

    private fun buildSummary(context: Context): DiagnosticsSummary {
        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        val versionCode = packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
        } ?: 0L
        PlayerSettingsRepository.ensureLoaded()
        val settings = PlayerSettingsRepository.uiState.value
        val lastEngine = PlaybackDiagnostics.lastActiveEngine
        return DiagnosticsSummary(
            generatedAt = formatTime(System.currentTimeMillis()),
            appVersion = packageInfo?.versionName ?: "unknown",
            versionCode = versionCode,
            packageName = context.packageName,
            buildType = if (canSimulateCrash) "debug" else "release",
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            architecture = "${if (Process.is64Bit()) "64-bit" else "32-bit"} app on " +
                Build.SUPPORTED_ABIS.joinToString(", "),
            playbackEngine = "${settings.androidPlaybackEngine.label} (setting), " +
                "last used: ${lastEngine ?: "none in this session"}",
            playerSettings = DiagnosticsReportFormatter.playerSettingsSummary(settings),
        )
    }

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(millis))
}
