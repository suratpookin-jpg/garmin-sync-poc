package com.personallife.garminsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("garmin_sync_prefs", Context.MODE_PRIVATE)
        val endpointUrl = prefs.getString("gas_url", "") ?: ""
        val secretToken = prefs.getString("bridge_secret", "") ?: ""

        if (endpointUrl.isBlank()) {
            return Result.success() // ยังไม่ได้ตั้งค่า URL ไม่ต้อง retry
        }

        val hcManager = HealthConnectManager(applicationContext)
        if (!hcManager.hasAllPermissions()) {
            return Result.success()
        }

        val network = NetworkManager()
        val since = Instant.now().minus(24, ChronoUnit.HOURS)
        val workouts = hcManager.readRecentGarminWorkouts(since)

        if (workouts.isEmpty()) {
            return Result.success()
        }

        val payload = WorkoutSyncPayload(activities = workouts)
        val result = network.syncWorkouts(endpointUrl, secretToken, payload)

        return if (result.isSuccess) {
            prefs.edit().putLong("last_sync_time", System.currentTimeMillis()).apply()
            Result.success()
        } else {
            Result.retry()
        }
    }
}
