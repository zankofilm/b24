package ir.bazr.inspection

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Local reminder only; mission data is never modified and no server status is implied. */
class MissionReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val key = inputData.getString("mission_key")?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val organization = inputData.getString("organization")?.takeIf { it.isNotBlank() } ?: "دستگاه اجرایی"
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) return Result.success()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "bazr_mission_reminders"
        manager.createNotificationChannel(NotificationChannel(
            channelId, "یادآوری مأموریت‌های بازرسی", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "یادآوری یک ساعت پیش از مأموریت‌های ابلاغ‌شده"
            enableVibration(true)
        })
        val open = Intent(applicationContext, MainActivity::class.java)
            .putExtra("open_tab", "calendar")
            .putExtra("mission_key", key)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val requestCode = (key.hashCode() and 0x7fffffff).coerceAtLeast(1)
        val pending = PendingIntent.getActivity(
            applicationContext, requestCode, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pending)
            .setContentTitle("یادآوری مأموریت بازرسی")
            .setContentText("تا یک ساعت دیگر: $organization")
            .setStyle(Notification.BigTextStyle().bigText("زمان مأموریت بازرسی $organization نزدیک است. برای مشاهده جزئیات، تقویم مأموریت‌ها را باز کنید."))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()
        manager.notify(requestCode, notification)
        return Result.success()
    }
}
