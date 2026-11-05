package kz.teamcargo.time

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar

@Composable
fun SleepPhaseCalculatorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var calculateFromWakeTime by remember { mutableStateOf(true) }
    var selectedMinutes by remember {
        mutableIntStateOf(Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        })
    }
    var fallAsleepMinutes by remember { mutableIntStateOf(15) }

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
        val minuteOfDay = Math.floorMod(totalMinutes, 1440)
        val dayOffset = Math.floorDiv(totalMinutes, 1440)
        val time = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
        return when {
            dayOffset < 0 -> "$time · предыдущий день"
            dayOffset > 0 -> "$time · следующий день"
            else -> time
        }
    }

    val colors = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("СОН", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Text("Калькулятор сна", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }

                Text("Что рассчитываем?", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeCard(
                        title = "Когда лечь",
                        subtitle = "Знаю время подъёма",
                        selected = calculateFromWakeTime,
                        modifier = Modifier.weight(1f)
                    ) { calculateFromWakeTime = true }
                    ModeCard(
                        title = "Когда встать",
                        subtitle = "Знаю время сна",
                        selected = !calculateFromWakeTime,
                        modifier = Modifier.weight(1f)
                    ) { calculateFromWakeTime = false }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = ::showTimePicker),
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (calculateFromWakeTime) "Время подъёма" else "Время отхода ко сну",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                timeLabel(selectedMinutes),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("ИЗМЕНИТЬ", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Время на засыпание", style = MaterialTheme.typography.titleSmall)
                        Text("Учитывается до начала сна", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceVariant) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { fallAsleepMinutes = (fallAsleepMinutes - 5).coerceAtLeast(0) },
                                enabled = fallAsleepMinutes > 0
                            ) { Text("−") }
                            Text("$fallAsleepMinutes мин", fontWeight = FontWeight.SemiBold)
                            TextButton(
                                onClick = { fallAsleepMinutes = (fallAsleepMinutes + 5).coerceAtMost(60) },
                                enabled = fallAsleepMinutes < 60
                            ) { Text("+") }
                        }
                    }
                }

                Text(
                    if (calculateFromWakeTime) "ЛУЧШЕЕ ВРЕМЯ ДЛЯ ОТХОДА КО СНУ"
                    else "ПРИМЕРНОЕ ВРЕМЯ ПРОБУЖДЕНИЯ",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (4..6).forEach { cycles ->
                        val sleepMinutes = cycles * 90
                        val total = sleepMinutes + fallAsleepMinutes
                        val result = if (calculateFromWakeTime) selectedMinutes - total else selectedMinutes + total
                        val recommended = cycles == 5
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (recommended) colors.primaryContainer else colors.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "$cycles цикла · ${sleepMinutes / 60} ч ${sleepMinutes % 60} мин сна",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        if (recommended) "Ориентир · не универсальная норма" else "С учётом засыпания: $total мин",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    timeLabel(result).substringBefore(" · "),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (recommended) colors.onPrimaryContainer else colors.onSurface
                                )
                            }
                        }
                    }
                }

                Text(
                    "Расчёт условный: цикл не всегда длится ровно 90 минут. Калькулятор не измеряет фазы сна. Ориентируйся также на достаточную общую продолжительность сна и самочувствие.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Готово")
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.secondaryContainer else colors.surfaceVariant,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
            )
        }
    }
}
