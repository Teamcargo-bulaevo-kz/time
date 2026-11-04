package kz.teamcargo.time

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SleepJournalDialog(
    store: SleepJournalStore,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var activeStart by remember { mutableStateOf(store.activeStart()) }
    var records by remember { mutableStateOf(store.getRecords()) }
    var pendingEnd by remember { mutableStateOf<Long?>(null) }
    var quality by remember { mutableFloatStateOf(3f) }
    var editingRecord by remember { mutableStateOf<SleepRecord?>(null) }
    var editStart by remember { mutableStateOf(0L) }
    var editEnd by remember { mutableStateOf(0L) }
    var editQuality by remember { mutableFloatStateOf(3f) }

    fun chooseDateTime(initial: Long?, onSelected: (Long) -> Unit) {
        val base = Calendar.getInstance().apply {
            timeInMillis = initial ?: System.currentTimeMillis()
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        selected.set(Calendar.HOUR_OF_DAY, hour)
                        selected.set(Calendar.MINUTE, minute)
                        onSelected(selected.timeInMillis)
                    },
                    base.get(Calendar.HOUR_OF_DAY),
                    base.get(Calendar.MINUTE),
                    true
                ).show()
            },
            base.get(Calendar.YEAR),
            base.get(Calendar.MONTH),
            base.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val dateFormat = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    fun durationLabel(minutes: Long): String = "${minutes / 60} ч ${minutes % 60} мин"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Дневник сна") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    activeStart?.let { "Начало сна: ${dateFormat.format(Date(it))}" }
                        ?: "Время отхода ко сну ещё не записано",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = {
                        chooseDateTime(activeStart) { timestamp ->
                            store.setActiveStart(timestamp)
                            activeStart = timestamp
                            pendingEnd = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (activeStart == null) "Записать отход ко сну" else "Изменить время отхода ко сну") }

                if (activeStart != null) {
                    Button(
                        onClick = {
                            chooseDateTime(null) { timestamp ->
                                if (timestamp > (activeStart ?: Long.MAX_VALUE)) pendingEnd = timestamp
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Записать пробуждение") }
                }

                pendingEnd?.let { end ->
                    Text("Качество сна: ${quality.roundToInt()} из 5")
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 1f..5f, steps = 3)
                    Text("Продолжительность: ${durationLabel(((end - (activeStart ?: end)) / 60_000L).coerceAtLeast(0))}")
                    Button(
                        onClick = {
                            val start = activeStart ?: return@Button
                            if (end <= start) return@Button
                            store.addRecord(SleepRecord(start, end, quality.roundToInt()))
                            activeStart = null
                            pendingEnd = null
                            records = store.getRecords()
                            quality = 3f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить запись") }
                }

                if (records.isNotEmpty()) {
                    val recent = records.take(7)
                    val averageMinutes = recent.map { it.durationMinutes }.average().toLong()
                    val averageQuality = recent.map { it.quality }.average()
                    Text(
                        "Среднее за последние ${recent.size}: ${durationLabel(averageMinutes)} · качество ${"%.1f".format(Locale.getDefault(), averageQuality)}/5",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Последние записи", style = MaterialTheme.typography.titleSmall)
                    records.take(5).forEach { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    dateFormat.format(Date(record.endedAt)),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "${durationLabel(record.durationMinutes)} · ${record.quality}/5",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row {
                                TextButton(onClick = {
                                    editingRecord = record
                                    editStart = record.startedAt
                                    editEnd = record.endedAt
                                    editQuality = record.quality.toFloat()
                                }) { Text("Изменить") }
                                TextButton(onClick = {
                                    store.deleteRecord(record.startedAt)
                                    records = store.getRecords()
                                }) { Text("Удалить") }
                            }
                        }
                    }
                } else {
                    Text("История появится после первой сохранённой записи.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )

    editingRecord?.let { original ->
        AlertDialog(
            onDismissRequest = { editingRecord = null },
            title = { Text("Изменить запись сна") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Засыпание: ${dateFormat.format(Date(editStart))}")
                    TextButton(onClick = { chooseDateTime(editStart) { editStart = it } }) {
                        Text("Изменить начало")
                    }
                    Text("Пробуждение: ${dateFormat.format(Date(editEnd))}")
                    TextButton(onClick = { chooseDateTime(editEnd) { editEnd = it } }) {
                        Text("Изменить конец")
                    }
                    Text("Качество: ${editQuality.roundToInt()} из 5")
                    Slider(value = editQuality, onValueChange = { editQuality = it }, valueRange = 1f..5f, steps = 3)
                    if (editEnd <= editStart) {
                        Text("Время пробуждения должно быть позже времени засыпания.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Продолжительность: ${durationLabel((editEnd - editStart) / 60_000L)}")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = editEnd > editStart,
                    onClick = {
                        store.updateRecord(
                            original.startedAt,
                            SleepRecord(editStart, editEnd, editQuality.roundToInt())
                        )
                        records = store.getRecords()
                        editingRecord = null
                    }
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editingRecord = null }) { Text("Отмена") }
            }
        )
    }
}
