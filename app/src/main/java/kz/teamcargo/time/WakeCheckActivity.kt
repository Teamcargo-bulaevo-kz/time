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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
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
        val mode = if (configuredMode == "random") listOf("schulte", "math", "shake", "tank").random() else configuredMode
        WakeCheckRetryScheduler.schedule(this, alarmId, retryMinutes)

        setContent {
            TimeTheme {
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
                val shakeCountState = remember { mutableIntStateOf(0) }
                var tankSeconds by remember { mutableStateOf(30) }
                val tankTouchCount = remember { mutableIntStateOf(0) }
                var tankPosition by remember { mutableStateOf(0) }
                fun completeChallenge() {
                    completed = true
                    WakeCheckRetryScheduler.cancel(this@WakeCheckActivity, alarmId)
                    stopService(android.content.Intent(this@WakeCheckActivity, AlarmSoundService::class.java))
                }

                DisposableEffect(mode) {
                    if (mode == "shake") {
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
                                if (magnitude > 15.5f && now - lastShakeAt > 450L) {
                                    lastShakeAt = now
                                    if (shakeCountState.intValue < 8) shakeCountState.intValue += 1
                                    if (shakeCountState.intValue >= 8) completeChallenge()
                                }
                            }
                            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                        }
                        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                        onDispose { manager.unregisterListener(listener) }
                    } else onDispose { }
                }
                LaunchedEffect(mode) {
                    if (mode == "tank") {
                        while (tankSeconds > 0) {
                            delay(1_000)
                            tankSeconds -= 1
                            tankPosition = (tankPosition + 1) % 4
                        }
                        if (tankTouchCount.intValue >= 3) completeChallenge()
                        else feedback = "Коснись танка ещё ${3 - tankTouchCount.intValue} раза, чтобы подтвердить бодрствование."
                    }
                }
                val questions = remember { List(5) { makeMathQuestion() } }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Проверка пробуждения", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (completed) "Задание выполнено" else when (mode) {
                                "math" -> "Реши 5 примеров · ${questionIndex + 1} / 5"
                                "shake" -> "Встряхни телефон 8 раз · ${shakeCountState.intValue} / 8"
                                "tank" -> "30 секунд бодрствования · ${tankSeconds} с"
                                else -> "Найди числа по порядку: $target / 25"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )

                        if (!completed && mode == "shake") {
                            Text("Держи телефон удобно и сделай восемь отчётливых встряхиваний.", style = MaterialTheme.typography.bodyLarge)
                            Text("${shakeCountState.intValue} / 8", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Не нужно трясти телефон сильно.", style = MaterialTheme.typography.bodySmall)
                        } else if (!completed && mode == "tank") {
                            Text("Игрушка для мягкого пробуждения. Результат не оценивается — нужно лишь немного подвигаться и побыть активным 30 секунд.", style = MaterialTheme.typography.bodyMedium)
                            val tankAccent = MaterialTheme.colorScheme.primary
                            Button(
                                onClick = { tankTouchCount.intValue += 1; if (tankTouchCount.intValue >= 3 && tankSeconds <= 0) completeChallenge() },
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        val x = size.width * (0.10f + tankPosition * 0.20f)
                                        val y = size.height * 0.48f
                                        drawRoundRect(tankAccent.copy(alpha = 0.22f), Offset(x, y), Size(76.dp.toPx(), 28.dp.toPx()), CornerRadius(8.dp.toPx()))
                                        drawRoundRect(tankAccent, Offset(x + 9.dp.toPx(), y - 15.dp.toPx()), Size(46.dp.toPx(), 24.dp.toPx()), CornerRadius(6.dp.toPx()))
                                        drawLine(tankAccent, Offset(x + 48.dp.toPx(), y - 5.dp.toPx()), Offset(x + 82.dp.toPx(), y - 5.dp.toPx()), 5.dp.toPx())
                                        drawCircle(Color(0xFF171316), 8.dp.toPx(), Offset(x + 18.dp.toPx(), y + 28.dp.toPx()))
                                        drawCircle(Color(0xFF171316), 8.dp.toPx(), Offset(x + 58.dp.toPx(), y + 28.dp.toPx()))
                                    }
                                    Text("КОСНИСЬ ТАНКА", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Text("Касания: ${tankTouchCount.intValue} / 3 · Осталось: $tankSeconds с", style = MaterialTheme.typography.titleMedium)
                            if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.primary)
                        } else if (!completed && mode == "math") {
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
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                items(numbers) { number ->
                                    Card(
                                        modifier = Modifier.aspectRatio(1f).clickable(enabled = guardSeconds == 0) {
                                            if (number == target) {
                                                if (number == 25) completeChallenge() else target += 1
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
