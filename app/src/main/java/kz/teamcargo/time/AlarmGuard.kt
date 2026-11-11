package kz.teamcargo.time

import android.content.Context
import android.os.SystemClock

object AlarmGuard {
    private const val PREFS = "time_alarm_guard"
    private const val KEY_ALARM_ID = "alarm_id"
    private const val KEY_STARTED_AT = "started_at"
    private const val LOCK_MILLIS = 30_000L

    fun begin(context: Context, alarmId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ALARM_ID, alarmId)
            .putLong(KEY_STARTED_AT, SystemClock.elapsedRealtime())
            .apply()
    }

    fun remainingMillis(context: Context, alarmId: String): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_ALARM_ID, null) != alarmId) return 0L
        val startedAt = prefs.getLong(KEY_STARTED_AT, 0L)
        return (LOCK_MILLIS - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
    }

    fun isLocked(context: Context, alarmId: String): Boolean =
        remainingMillis(context, alarmId) > 0L
}
