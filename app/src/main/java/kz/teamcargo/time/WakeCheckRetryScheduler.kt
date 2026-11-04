package kz.teamcargo.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object WakeCheckRetryScheduler {
    private fun pendingIntent(context: Context, alarmId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.hashCode() xor 0x5A17,
            Intent(context, WakeCheckRetryReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(context: Context, alarmId: String, minutes: Int = 5) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, alarmId)
        val at = System.currentTimeMillis() + minutes.coerceIn(1, 60) * 60_000L
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, alarmId: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, alarmId))
    }
}
