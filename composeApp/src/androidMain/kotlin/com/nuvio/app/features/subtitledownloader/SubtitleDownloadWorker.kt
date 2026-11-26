package com.nuvio.app.features.subtitledownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

/**
 * Keeps Subtitle Downloader's queue going while the app is in the background, with a progress
 * notification, and starts it again after the system stopped the app.
 */
class SubtitleDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        SubtitleDownloaderRepository.initialize(applicationContext)
        if (!SubtitleDownloaderRepository.hasPendingWork) return@coroutineScope Result.success()
        if (AppFeaturePolicy.downloadForegroundServiceEnabled) {
            runCatching { setForeground(foreground()) }
        }
        val updates = launch {
            while (true) {
                delay(1_500)
                runCatching { notify() }
            }
        }
        SubtitleDownloaderRepository.state.first { state -> state.jobs.none { it.isActive } }
        updates.cancel()
        Result.success()
    }

    private fun notification(): android.app.Notification {
        val state = SubtitleDownloaderRepository.state.value
        val active = state.jobs.filter { it.isActive }
        val progress = SubtitleDownloaderRepository.progress.value
        val current = active.firstOrNull { it.status == JobStatus.RUNNING }
        val p = current?.let { progress[it.id] }
        val text = if (current != null && p != null) {
            "${current.label} · ${p.events} eventos · ${p.fontsDone}/${p.fontsTotal} fuentes"
        } else {
            "${active.size} en cola"
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle("Subtitle Downloader")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, ((p?.coveredFraction ?: 0.0) * 100).toInt(), p == null)
            .build()
    }

    private fun foreground(): ForegroundInfo {
        ensureChannel(applicationContext)
        return ForegroundInfo(NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    private suspend fun notify() {
        if (AppFeaturePolicy.downloadForegroundServiceEnabled) setForeground(foreground())
    }

    companion object {
        private const val WORK = "subtitle-downloader"
        private const val CHANNEL = "subtitle_downloader"
        private const val NOTIFICATION_ID = 0x5D0D

        fun start(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SubtitleDownloadWorker>().build(),
                )
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Subtitle Downloader", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
