package kz.teamcargo.time

import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val alarms = remember { mutableStateListOf("08:00") }

                Scaffold(
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                val now = Calendar.getInstance()
                                TimePickerDialog(
                                    this,
                                    { _, hour, minute ->
                                        alarms.add(String.format("%02d:%02d", hour, minute))
                                    },
                                    now.get(Calendar.HOUR_OF_DAY),
                                    now.get(Calendar.MINUTE),
                                    true
                                ).show()
                            }
                        ) {
                            Text("+")
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Будильники", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "Простой экран — основа для настроек и интеллектуального пробуждения.",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (alarms.isEmpty()) {
                                Text("Будильников пока нет")
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(alarms) { time ->
                                        varEnabledRow(time)
                                    }
                                }
                            }
                            Text(
                                "Прототип интерфейса. Сохранение и реальное срабатывание будут добавлены на следующем этапе.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun varEnabledRow(time: String) {
    var enabled = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, style = MaterialTheme.typography.headlineLarge)
        Switch(
            checked = enabled.value,
            onCheckedChange = { enabled.value = it }
        )
    }
}
