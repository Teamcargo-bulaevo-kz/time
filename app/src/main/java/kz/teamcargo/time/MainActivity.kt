package kz.teamcargo.time

import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

class MainActivity : ComponentActivity() {
    private lateinit var store: AlarmStore
    private lateinit var scheduler: AlarmScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AlarmStore(this)
        scheduler = AlarmScheduler(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            MaterialTheme {
                val alarms = remember { mutableStateListOf<Alarm>().apply { addAll(store.getAll()) } }
                var editing by remember { mutableStateOf<Alarm?>(null) }
                var showRepeatFor by remember { mutableStateOf<Alarm?>(null) }

                fun persist(updated: List<Alarm>) {
                    store.saveAll(updated)
                    scheduler.scheduleAll(updated)
                    alarms.clear()
                    alarms.addAll(updated)
                }

                fun chooseTime(existing: Alarm?) {
                    val now = Calendar.getInstance()
                    TimePickerDialog(
                        this,
                        { _, hour, minute ->
                            val next = if (existing == null) Alarm(hour = hour, minute = minute)
                                else existing.copy(hour = hour, minute = minute)
                            val updated = if (existing == null) alarms.toList() + next
                                else alarms.map { if (it.id == existing.id) next else it }
                            persist(updated)
                            requestExactAlarmPermissionIfNeeded()
                        },
                        existing?.hour ?: now.get(Calendar.HOUR_OF_DAY),
                        existing?.minute ?: now.get(Calendar.MINUTE),
                        true
                    ).show()
                }

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(onClick = { chooseTime(null) }) { Text("+") }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding)) {
                        Column(
                            Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("TIME", style = MaterialTheme.typography.labelLarge)
                            Text("Будильники", style = MaterialTheme.typography.headlineMedium)
                            Text("Простая настройка. Остальные возможности можно добавить позже.",
                                style = MaterialTheme.typography.bodyMedium)

                            if (alarms.isEmpty()) {
                                Spacer(Modifier.weight(1f))
                                Text("Пока нет будильников. Нажмите +, чтобы добавить первый.",
                                    style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.weight(1f))
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(alarms, key = { it.id }) { alarm ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(alarm.timeText(), style = MaterialTheme.typography.headlineLarge)
                                                    Text(
                                                        alarm.label.ifBlank { repeatLabel(alarm.repeatDays) },
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    TextButton(onClick = { chooseTime(alarm) }) { Text("Изменить время") }
                                                    TextButton(onClick = { showRepeatFor = alarm }) { Text("Повторение") }
                                                    TextButton(onClick = { scheduler.cancel(alarm.id); persist(alarms.filterNot { it.id == alarm.id }) }) { Text("Удалить") }
                                                }
                                                Switch(
                                                    checked = alarm.enabled,
                                                    onCheckedChange = { enabled ->
                                                        persist(alarms.map { if (it.id == alarm.id) it.copy(enabled = enabled) else it })
                                                        if (enabled) requestExactAlarmPermissionIfNeeded()
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                "Сейчас реализовано сохранение и системное планирование. Звук, свет и задания добавляются следующими этапами.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                showRepeatFor?.let { selected ->
                    val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                    var days by remember(selected.id) { mutableStateOf(selected.repeatDays) }
                    AlertDialog(
                        onDismissRequest = { showRepeatFor = null },
                        title = { Text("Повторять") },
                        text = {
                            Column {
                                dayNames.forEachIndexed { index, name ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = (index + 1) in days,
                                            onCheckedChange = { checked ->
                                                days = if (checked) days + (index + 1) else days - (index + 1)
                                            }
                                        )
                                        Text(name)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                persist(alarms.map { if (it.id == selected.id) it.copy(repeatDays = days) else it })
                                showRepeatFor = null
                            }) { Text("Сохранить") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRepeatFor = null }) { Text("Отмена") }
                        }
                    )
                }
            }
        }
    }

    private fun requestExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        ) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
            }
        }
    }

    private fun repeatLabel(days: Set<Int>): String = when {
        days.isEmpty() -> "Один раз"
        days.size == 7 -> "Каждый день"
        days == setOf(1, 2, 3, 4, 5) -> "По будням"
        days == setOf(6, 7) -> "По выходным"
        else -> "Повтор: " + days.sorted().joinToString(", ") {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[it - 1]
        }
    }
}
