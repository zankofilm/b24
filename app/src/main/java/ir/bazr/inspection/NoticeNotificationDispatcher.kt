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
import org.json.JSONArray
import org.json.JSONObject

/** Deduplicates inbox alerts across foreground sync and WorkManager polling. */
object NoticeNotificationDispatcher {
    private const val CHANNEL_ID = "bazr_org_notices"
    private const val PREF_KEY = "notice_notified_ids"

    @Synchronized
    fun dispatch(context: Context, notices: JSONArray, store: SecureStore): Int {
        if (store.getString("notify_notices_enabled") != "yes") return 0
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return 0
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ابلاغیه‌های سازمانی", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "ابلاغیه‌ها، مأموریت‌ها و درخواست‌های اصلاح گزارش"
                enableVibration(true)
            }
        )
        val known = linkedSetOf<String>()
        runCatching { JSONArray(store.load(PREF_KEY) ?: "[]") }.getOrNull()?.let { saved ->
            for (i in 0 until saved.length()) saved.optString(i).takeIf { it.isNotBlank() }?.let(known::add)
        }
        var delivered = 0
        val allSeen = linkedSetOf<String>().apply { addAll(known) }
        for (i in 0 until notices.length()) {
            val notice = notices.optJSONObject(i) ?: continue
            val id = notice.optString("id", notice.optString("notice_key")).ifBlank {
                notice.optString("_server_key")
            }
            if (id.isBlank()) continue
            allSeen.add(id)
            if (!NoticeRules.shouldNotify(id, notice.optBoolean("is_read"), known)) continue

            val title = notice.optString("title", "ابلاغیه سازمانی").take(180)
            val message = notice.optString("message", "ابلاغیه جدید در سامانه ثبت شده است")
                .replace(Regex("\\s+"), " ").take(180)
            val open = Intent(context, MainActivity::class.java)
                .putExtra("open_tab", "notices")
                .putExtra("notice_id", id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val requestCode = (id.hashCode() and 0x7fffffff).coerceAtLeast(1)
            val pending = PendingIntent.getActivity(
                context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pending)
                .setContentTitle(title)
                .setContentText(message.ifBlank { "ابلاغیه جدید در سامانه ثبت شده است" })
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build()
            manager.notify(requestCode, notification)
            delivered++
        }
        // Bound the local dedupe index; it contains only opaque notice IDs.
        val retained = NoticeRules.retainRecentIds(allSeen)
        runCatching { store.saveDurable(PREF_KEY, JSONArray(retained).toString()) }
        return delivered
    }
}
