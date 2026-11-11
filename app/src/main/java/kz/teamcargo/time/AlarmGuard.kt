package kz.teamcargo.time

import android.content.Context
import android.os.SystemClock

object AlarmGuard {
    private const val PREFS = "time_alarm_guard"
    private const val KEY_STARTED_AT = "started_at"
    private const val LOCK_MILLIS = 30_000L

    fun begin(context: Context, alarmId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(startedKey(alarmId), SystemClock.elapsedRealtime())
            .apply()
    }

    fun remainingMillis(context: Context, alarmId: String): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val startedAt = prefs.getLong(startedKey(alarmId), 0L)
        val now = SystemClock.elapsedRealtime()
        // Ignore stale wall-clock values from earlier app versions or a previous device boot.
        if (startedAt <= 0L || startedAt > now) return 0L
        return (LOCK_MILLIS - (now - startedAt)).coerceAtLeast(0L)
    }

    fun isLocked(context: Context, alarmId: String): Boolean =
        remainingMillis(context, alarmId) > 0L

    private fun startedKey(alarmId: String): String = "${KEY_STARTED_AT}_$alarmId"
}
