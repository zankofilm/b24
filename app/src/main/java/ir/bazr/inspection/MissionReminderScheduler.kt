package ir.bazr.inspection

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Schedules a one-time, best-effort reminder one hour before a server-assigned mission. */
object MissionReminderScheduler {
    enum class Result { SCHEDULED, TOO_LATE, INVALID }
    private const val TAG = "bazr_mission_reminder"

    fun schedule(context: Context, missionKey: String, organization: String, scheduledAt: Instant): Result {
        if (missionKey.isBlank() || organization.isBlank()) return Result.INVALID
        val reminderAt = scheduledAt.minus(Duration.ofHours(1))
        val delay = Duration.between(Instant.now(), reminderAt).toMillis()
        if (delay <= 0L) return Result.TOO_LATE
        val request = OneTimeWorkRequestBuilder<MissionReminderWorker>()
            .setInputData(Data.Builder()
                .putString("mission_key", missionKey)
                .putString("organization", organization.take(140))
                .putString("scheduled_at", scheduledAt.toString())
                .build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        val stableId = UUID.nameUUIDFromBytes(missionKey.toByteArray(Charsets.UTF_8)).toString()
        val unique = "bazr_mission_reminder_$stableId"
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(unique, ExistingWorkPolicy.REPLACE, request)
        return Result.SCHEDULED
    }

    fun cancel(context: Context, missionKey: String) {
        if (missionKey.isBlank()) return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("bazr_mission_reminder_${UUID.nameUUIDFromBytes(missionKey.toByteArray(Charsets.UTF_8))}")
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
    }
}
