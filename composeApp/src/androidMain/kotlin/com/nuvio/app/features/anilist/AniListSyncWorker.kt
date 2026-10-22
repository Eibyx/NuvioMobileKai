/*
 * Adapted from Aniyomi's DelayedAnimeTrackingUpdateJob (https://github.com/aniyomiorg/aniyomi,
 * app/src/main/java/eu/kanade/domain/track/anime/service/DelayedAnimeTrackingUpdateJob.kt).
 *
 * Copyright © 2015 Javier Tomás
 * Copyright © 2024 Mihon Open Source Project
 * Copyright © 2024 Aniyomi Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Nuvio: runs the persisted AniList queue and honours its next retry time.
 */
package com.nuvio.app.features.anilist

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nuvio.app.core.logging.InAppLogger
import com.nuvio.app.features.profiles.ProfileStorage
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Sends pending AniList episodes once the network is up, also when the app has been closed. */
class AniListSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (runAttemptCount > MAX_ATTEMPTS) return Result.failure()
        initialize(applicationContext)
        initializeStorage(applicationContext)
        running = true
        return try {
            val stillPending = AniListTracker.sendPendingInBackground(PlatformAniListQueueStorage.lastProfile())
            if (stillPending) {
                InAppLogger.info(TAG, "Background send left items pending; WorkManager retries later")
                Result.retry()
            } else {
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            InAppLogger.warn(TAG, "Background send failed: ${InAppLogger.throwableSummary(error)}")
            Result.retry()
        } finally {
            running = false
        }
    }

    companion object {
        private const val TAG = "Sync/AniList"
        private const val WORK_NAME = "anilist_pending_sends"
        private const val MAX_ATTEMPTS = 8
        private var appContext: Context? = null

        /** While the job runs, its own Result.retry() reschedules it; replacing it would cancel it. */
        @Volatile
        private var running = false

        fun initialize(context: Context) {
            appContext = context.applicationContext
        }

        /** What the worker needs when the system starts the app just to run it. */
        private fun initializeStorage(context: Context) {
            PlatformAniListAuthPersistence.initialize(context)
            PlatformAniListSyncStorage.initialize(context)
            PlatformAniListQueueStorage.initialize(context)
            ProfileStorage.initialize(context)
        }

        internal fun schedule(delayMs: Long) {
            val context = appContext ?: return
            if (running) return
            val request = OneTimeWorkRequestBuilder<AniListSyncWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

internal actual object PlatformAniListSyncScheduler {
    actual fun schedule(delayMs: Long) = AniListSyncWorker.schedule(delayMs)
}
