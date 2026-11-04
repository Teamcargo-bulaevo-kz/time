package kz.teamcargo.time

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmRingingActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var brightness = 0.08f
    private var brightnessTask: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alarmId = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID).orEmpty()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        animateBrightness()

        val alarm = AlarmStore(this).getAll().firstOrNull { it.id == alarmId }
        val label = alarm?.label?.ifBlank { "Пора просыпаться" } ?: "Пора просыпаться"
        val snoozeMinutes = 5

        setContent {
            MaterialTheme {
                var now by remember { mutableStateOf(LocalTime.now()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        now = LocalTime.now()
                        delay(1_000)
                    }
                }
                val clock = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF10131B)),
                    color = Color(0xFF10131B)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("TIME", color = Color(0xFF9BA9C4), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(28.dp))
                        Text(clock, color = Color.White, fontSize = 64.sp, lineHeight = 72.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(label, color = Color(0xFFD5DCEE), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(52.dp))
                        Button(
                            onClick = {
                                startActivity(Intent(this@AlarmRingingActivity, WakeCheckActivity::class.java)
                                    .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(58.dp)
                        ) { Text("Я проснулся — решить задание") }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                WakeCheckRetryScheduler.schedule(this@AlarmRingingActivity, alarmId, snoozeMinutes)
                                stopService(Intent(this@AlarmRingingActivity, AlarmSoundService::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) { Text("Отложить на 5 минут") }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                WakeCheckRetryScheduler.cancel(this@AlarmRingingActivity, alarmId)
                                stopService(Intent(this@AlarmRingingActivity, AlarmSoundService::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) { Text("Отключить сигнал") }
                    }
                }
            }
        }
    }

    private fun animateBrightness() {
        val task = object : Runnable {
            override fun run() {
                brightness = (brightness + 0.04f).coerceAtMost(1f)
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                if (brightness < 1f) handler.postDelayed(this, 700L)
            }
        }
        brightnessTask = task
        handler.post(task)
    }

    override fun onDestroy() {
        brightnessTask?.let(handler::removeCallbacks)
        super.onDestroy()
    }
}
