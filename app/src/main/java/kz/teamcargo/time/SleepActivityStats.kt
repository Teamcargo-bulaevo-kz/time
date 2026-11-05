package kz.teamcargo.time

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class EstimatedSleepSession(
    val startMillis: Long,
    val endMillis: Long
) {
    val durationMinutes: Long
        get() = TimeUnit.MILLISECONDS.toMinutes(endMillis - startMillis)
}

object SleepActivityStats {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return appOps.checkOpNoThrow(
            "android:get_usage_stats",
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun load(context: Context, days: Int = 7): List<EstimatedSleepSession> {
        if (!hasUsageAccess(context)) return emptyList()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val begin = now - TimeUnit.DAYS.toMillis((days + 1).toLong())
        val events = manager.queryEvents(begin, now)
        val event = UsageEvents.Event()
        var screenOffAt: Long? = null
        val sessions = mutableListOf<EstimatedSleepSession>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                16 -> screenOffAt = event.timeStamp // SCREEN_NON_INTERACTIVE
                15 -> { // SCREEN_INTERACTIVE
                    val start = screenOffAt
                    if (start != null && event.timeStamp > start) {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(event.timeStamp - start)
                        val startHour = java.util.Calendar.getInstance().apply { timeInMillis = start }.get(java.util.Calendar.HOUR_OF_DAY)
                        val endHour = java.util.Calendar.getInstance().apply { timeInMillis = event.timeStamp }.get(java.util.Calendar.HOUR_OF_DAY)
                        val looksLikeNight = (startHour >= 18 || startHour <= 5) &&
                            (endHour in 4..13) && minutes in 180..960
                        if (looksLikeNight) {
                            sessions.add(EstimatedSleepSession(start, event.timeStamp))
                        }
                    }
                    screenOffAt = null
                }
            }
        }
        return sessions.distinctBy { it.endMillis / TimeUnit.MINUTES.toMillis(1) }
            .sortedByDescending { it.endMillis }
            .take(days)
    }

    fun formatDuration(minutes: Long): String {
        val hours = minutes / 60
        val remainder = minutes % 60
        return if (remainder == 0L) "${hours} ч" else "${hours} ч ${remainder} мин"
    }
}

@Composable
fun SleepStatsDialog(context: Context, onDismiss: () -> Unit) {
    val hasAccess = remember { SleepActivityStats.hasUsageAccess(context) }
    val sessions = remember(hasAccess) { if (hasAccess) SleepActivityStats.load(context) else emptyList() }
    val dateFormat = remember { SimpleDateFormat("EEE, d MMM", Locale("ru")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Статистика сна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Оценка по периодам выключенного экрана. Это приблизительная статистика, не измерение сна.")
                if (!hasAccess) {
                    Text("Нужно разрешить TIME доступ к статистике использования устройства.")
                } else if (sessions.isEmpty()) {
                    Text("Пока не найдено ночных интервалов. Данные появятся после нескольких ночей.")
                } else {
                    val average = sessions.map { it.durationMinutes }.average().toLong()
                    Text("Средний ночной интервал: ${SleepActivityStats.formatDuration(average)}",
                        style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(sessions) { session ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(dateFormat.format(Date(session.endMillis)))
                                Text(
                                    "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.startMillis))} — " +
                                        "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.endMillis))} · " +
                                        SleepActivityStats.formatDuration(session.durationMinutes),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!hasAccess) {
                Button(onClick = { SleepActivityStats.openUsageAccessSettings(context); onDismiss() }) {
                    Text("Разрешить доступ")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
        dismissButton = {
            if (!hasAccess) TextButton(onClick = onDismiss) { Text("Позже") }
        }
    )
}
