package kz.teamcargo.time

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

@Composable
fun SleepPhaseCalculatorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var calculateFromWakeTime by remember { mutableStateOf(true) }
    var selectedMinutes by remember {
        mutableIntStateOf(
            Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        )
    }

    fun showTimePicker() {
        TimePickerDialog(
            context,
            { _, hour, minute -> selectedMinutes = hour * 60 + minute },
            selectedMinutes / 60,
            selectedMinutes % 60,
            true
        ).show()
    }

    fun timeLabel(totalMinutes: Int): String {
        val dayOffset = Math.floorDiv(totalMinutes, 1440)
        val minuteOfDay = Math.floorMod(totalMinutes, 1440)
        val time = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        return when {
            dayOffset < 0 -> "$time (предыдущий день)"
            dayOffset > 0 -> "$time (следующий день)"
            else -> time
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Калькулятор циклов сна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Что известно?")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = calculateFromWakeTime, onClick = { calculateFromWakeTime = true })
                    Text("Время подъёма")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !calculateFromWakeTime, onClick = { calculateFromWakeTime = false })
                    Text("Время отхода ко сну")
                }
                TextButton(onClick = ::showTimePicker, modifier = Modifier.fillMaxWidth()) {
                    Text("Выбрать время: " + timeLabel(selectedMinutes))
                }
                Text(
                    if (calculateFromWakeTime) "Рекомендуемое время, когда лечь в кровать:"
                    else "Примерное время пробуждения:",
                    style = MaterialTheme.typography.titleSmall
                )
                (4..6).forEach { cycleCount ->
                    val cycleDuration = cycleCount * 90 + 15
                    val result = if (calculateFromWakeTime) selectedMinutes - cycleDuration
                        else selectedMinutes + cycleDuration
                    Text(
                        "${cycleCount} цикла · ${cycleCount * 90} мин сна + 15 мин на засыпание: ${timeLabel(result)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Это грубая оценка по условным 90-минутным циклам. Реальная длительность циклов меняется, а калькулятор не определяет стадии сна и не заменяет трекер.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}
