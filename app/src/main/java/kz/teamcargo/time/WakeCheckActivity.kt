package kz.teamcargo.time

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class WakeCheckActivity : ComponentActivity() {
    private lateinit var alarmId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID).orEmpty()
        if (alarmId.isBlank()) {
            finish()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        stopService(android.content.Intent(this, AlarmSoundService::class.java))
        WakeCheckRetryScheduler.schedule(this, alarmId, 5)

        setContent {
            MaterialTheme {
                var numbers by remember { mutableStateOf((1..25).shuffled()) }
                var target by remember { mutableStateOf(1) }
                var completed by remember { mutableStateOf(false) }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Проверка пробуждения", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (completed) "Задание выполнено" else "Найди числа по порядку: $target / 25",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!completed) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(numbers) { number ->
                                    Card(
                                        modifier = Modifier.aspectRatio(1f).clickable {
                                            if (number == target) {
                                                if (number == 25) {
                                                    completed = true
                                                    WakeCheckRetryScheduler.cancel(this@WakeCheckActivity, alarmId)
                                                    stopService(android.content.Intent(this@WakeCheckActivity, AlarmSoundService::class.java))
                                                } else target += 1
                                            }
                                        }
                                    ) {
                                        androidx.compose.foundation.layout.Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(number.toString(), style = MaterialTheme.typography.headlineSmall)
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Будильник отключён до следующего запланированного времени.")
                            Button(onClick = { finish() }) { Text("Готово") }
                        }

                        Button(
                            onClick = {
                                WakeCheckRetryScheduler.cancel(this@WakeCheckActivity, alarmId)
                                stopService(android.content.Intent(this@WakeCheckActivity, AlarmSoundService::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Аварийно отключить")
                        }
                    }
                }
            }
        }
    }
}
