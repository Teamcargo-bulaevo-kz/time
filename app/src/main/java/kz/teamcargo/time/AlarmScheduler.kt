package kz.teamcargo.time

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAll(alarms: List<Alarm>) = alarms.forEach(::schedule)

    fun schedule(alarm: Alarm) {
        cancel(alarm.id)
        if (!alarm.enabled) return
        val triggerAt = nextTriggerMillis(alarm) ?: return
        val pending = pendingIntent(alarm.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(id: String) = alarmManager.cancel(pendingIntent(id))

    private fun pendingIntent(id: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
        return PendingIntent.getBroadcast(context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun nextTriggerMillis(alarm: Alarm): Long? {
        val now = Calendar.getInstance()
        if (alarm.repeatDays.isEmpty()) {
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour); set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
        }
        return (0..7).map { offset ->
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, alarm.hour); set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
        }.firstOrNull { it.timeInMillis > now.timeInMillis && it.get(Calendar.DAY_OF_WEEK).toIsoDay() in alarm.repeatDays }?.timeInMillis
    }

    private fun Int.toIsoDay(): Int = if (this == Calendar.SUNDAY) 7 else this - 1
}
