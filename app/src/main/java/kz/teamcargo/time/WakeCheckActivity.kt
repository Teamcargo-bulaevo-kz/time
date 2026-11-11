package kz.teamcargo.time

import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private data class MathQuestion(val left: Int, val right: Int, val operator: String, val answer: Int) {
    val expression: String get() = "$left $operator $right = ?"
}

private fun makeMathQuestion(): MathQuestion {
    val operator = listOf("+", "−", "×").random()
    val left = if (operator == "×") (2..9).random() else (8..30).random()
    val right = if (operator == "×") (2..9).random() else (2..left).random()
    val answer = when (operator) {
        "+" -> left + right
        "−" -> left - right
        else -> left * right
    }
    return MathQuestion(left, right, operator, answer)
}

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

        if (!AlarmGuard.isLocked(this, alarmId)) {
            stopService(android.content.Intent(this, AlarmSoundService::class.java))
        }
        val alarm = AlarmStore(this).getAll().firstOrNull { it.id == alarmId }
        val retryMinutes = alarm?.retryMinutes ?: 5
        val configuredMode = alarm?.challengeMode ?: "schulte"
        val mode = configuredMode.takeIf { it in setOf("schulte", "math", "shake") } ?: "schulte"
        WakeCheckRetryScheduler.schedule(this, alarmId, retryMinutes)

        val selectedPalette = getSharedPreferences("time_ui", MODE_PRIVATE).getString("palette", "red") ?: "red"
        val systemBarColor = when (selectedPalette) {
            "blue" -> android.graphics.Color.rgb(11, 17, 25)
            "green" -> android.graphics.Color.rgb(13, 19, 15)
            else -> android.graphics.Color.rgb(16, 13, 14)
        }
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            TimeTheme(selectedPalette) {
                var guardSeconds by remember {
                    mutableStateOf(((AlarmGuard.remainingMillis(this@WakeCheckActivity, alarmId) + 999L) / 1000L).toInt())
                }
                LaunchedEffect(alarmId) {
                    while (guardSeconds > 0) {
                        guardSeconds = ((AlarmGuard.remainingMillis(this@WakeCheckActivity, alarmId) + 999L) / 1000L).toInt()
                        delay(250)
                    }
                }
                var numbers by remember { mutableStateOf((1..25).shuffled()) }
                var target by remember { mutableStateOf(1) }
                var completed by remember { mutableStateOf(false) }
                var questionIndex by remember { mutableStateOf(0) }
                var answerText by remember { mutableStateOf("") }
                var feedback by remember { mutableStateOf("") }
                var activeMode by remember { mutableStateOf(mode) }
                var shakeUnavailable by remember { mutableStateOf(false) }
                val shakeCountState = remember { mutableIntStateOf(0) }
                fun completeChallenge() {
                    completed = true
                    WakeCheckRetryScheduler.cancel(this@WakeCheckActivity, alarmId)
                    stopService(android.content.Intent(this@WakeCheckActivity, AlarmSoundService::class.java))
                }

                DisposableEffect(activeMode) {
                    if (activeMode == "shake") {
                        val manager = getSystemService(SENSOR_SERVICE) as SensorManager
                        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                        var lastShakeAt = 0L
                        val listener = object : SensorEventListener {
                            override fun onSensorChanged(event: SensorEvent) {
                                val x = event.values[0]
                                val y = event.values[1]
                                val z = event.values[2]
                                val magnitude = kotlin.math.sqrt(x*x + y*y + z*z)
                                val now = SystemClock.elapsedRealtime()
                                // Ignore motion until the mandatory 30-second lock has expired.
                                // Read the persisted guard directly: a Compose state captured by this
                                // sensor listener could otherwise remain stale for the whole session.
                                if (AlarmGuard.remainingMillis(this@WakeCheckActivity, alarmId) == 0L &&
                                    magnitude > 15.5f && now - lastShakeAt > 450L &&
                                    shakeCountState.intValue < 20
                                ) {
                                    lastShakeAt = now
                                    shakeCountState.intValue += 1
                                    if (shakeCountState.intValue == 20) completeChallenge()
                                }
                            }
                            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                        }
                        if (sensor != null) {
                            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                        } else {
                            shakeUnavailable = true
                            activeMode = "schulte"
                        }
                        onDispose { manager.unregisterListener(listener) }
                    } else onDispose { }
                }
                val questions = remember { List(5) { makeMathQuestion() } }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Проверка пробуждения", style = MaterialTheme.typography.headlineMedium)
                        if (guardSeconds > 0) {
                            Text("Задания станут активны через $guardSeconds с", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            if (completed) "Задание выполнено" else when (activeMode) {
                                "math" -> "Реши 5 примеров · ${questionIndex + 1} / 5"
                                "shake" -> "Встряхни телефон 20 раз · ${shakeCountState.intValue} / 20"
                                else -> "Найди числа по порядку: $target / 25"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (shakeUnavailable) {
                            Text("Датчик движения недоступен. Вместо встряхивания включена таблица Шульте.",
                                color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                        }

                        if (!completed && activeMode == "shake") {
                            Text("Держи телефон удобно и сделай 20 отчётливых встряхиваний.", style = MaterialTheme.typography.bodyLarge)
                            Text("${shakeCountState.intValue} / 8", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Не нужно трясти телефон сильно.", style = MaterialTheme.typography.bodySmall)
                        } else if (!completed && activeMode == "math") {
                            Text(questions[questionIndex].expression, style = MaterialTheme.typography.headlineLarge)
                            OutlinedTextField(
                                value = answerText,
                                onValueChange = { answerText = it.filter { char -> char.isDigit() || char == '-' } },
                                label = { Text("Ответ") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.error)
                            Button(
                                onClick = {
                                    if (answerText.toIntOrNull() == questions[questionIndex].answer) {
                                        answerText = ""
                                        feedback = ""
                                        if (questionIndex == questions.lastIndex) completeChallenge()
                                        else questionIndex += 1
                                    } else {
                                        feedback = "Неверно. Попробуй ещё раз."
                                    }
                                },
                                enabled = guardSeconds == 0,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Проверить ответ") }
                        } else if (!completed) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                numbers.chunked(5).forEach { rowNumbers ->
                                    androidx.compose.foundation.layout.Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rowNumbers.forEach { number ->
                                            Card(
                                                modifier = Modifier.weight(1f).aspectRatio(1f).clickable(enabled = guardSeconds == 0) {
                                                    if (number == target) {
                                                        if (number == 25) completeChallenge() else target += 1
                                                    }
                                                }
                                            ) {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Text(number.toString(), style = MaterialTheme.typography.titleLarge)
                                                }
                                            }
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
                            enabled = guardSeconds == 0,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (guardSeconds > 0) "Аварийное отключение через $guardSeconds с" else "Аварийно отключить")
                        }
                    }
                }
            }
        }
    }
}
