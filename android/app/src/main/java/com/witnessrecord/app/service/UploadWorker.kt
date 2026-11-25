package com.witnessrecord.app.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Flushes the upload queue after the app itself is gone.
 *
 * The in-process retry loop covers the ordinary case, but a phone seized
 * mid-encounter is exactly the case where the app gets force-stopped with
 * chunks still queued. WorkManager persists its jobs in its own database and
 * across reboots, so the queue drains later regardless - which is the whole
 * promise of the app.
 */
class UploadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        if (ChunkStore.pendingCount == 0) return Result.success()

        UploadManager.pump()

        // Give the pump a window to finish before the worker returns; anything
        // still queued after it comes back through a fresh retry, so this is a
        // bounded wait rather than a guarantee.
        var waited = 0L
        while (waited < 90_000 && ChunkStore.pendingCount > 0) {
            delay(3_000)
            waited += 3_000
        }
        return if (ChunkStore.pendingCount > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "witness-upload-flush"

        fun enqueueRetry(context: Context, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        // Any connection at all, metered included: getting
                        // footage off the device outweighs saving data.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
