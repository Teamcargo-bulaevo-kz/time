package kz.teamcargo.time

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        val store = AlarmStore(context)
        val alarm = store.getAll().firstOrNull { it.id == id } ?: return

        if (alarm.repeatDays.isNotEmpty()) {
            AlarmScheduler(context).schedule(alarm)
        } else {
            store.saveAll(store.getAll().map { if (it.id == id) it.copy(enabled = false) else it })
        }

        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_RINGING_ID, id)
        val contentPending = PendingIntent.getActivity(context, id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Будильник",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Сигналы будильника TIME"
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(alarm.label.ifBlank { "Пора просыпаться" })
            .setContentText("Будильник " + alarm.timeText())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id.hashCode(), notification) }
    }

    companion object {
        const val EXTRA_ALARM_ID = "kz.teamcargo.time.ALARM_ID"
        const val EXTRA_RINGING_ID = "kz.teamcargo.time.RINGING_ID"
        const val CHANNEL_ID = "time_alarm_channel"
    }
}
