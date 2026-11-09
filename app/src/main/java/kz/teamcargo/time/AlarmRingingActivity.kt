package kz.teamcargo.time

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

class AlarmRingingActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var brightnessTask: Runnable? = null
    private var brightnessStartedAt = 0L

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
        val visualStyle = alarm?.wakeVisualStyle ?: "sunrise"
        val snoozeMinutes = 5

        setContent {
            TimeTheme {
            var now by remember { mutableStateOf(LocalTime.now()) }
            var guardSeconds by remember {
                mutableStateOf(((AlarmGuard.remainingMillis(this@AlarmRingingActivity, alarmId) + 999L) / 1000L).toInt())
            }
            LaunchedEffect(alarmId) {
                while (guardSeconds > 0) {
                    guardSeconds = ((AlarmGuard.remainingMillis(this@AlarmRingingActivity, alarmId) + 999L) / 1000L).toInt()
                    delay(250)
                }
            }
            val reveal = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                reveal.animateTo(1f, animationSpec = tween(durationMillis = 60_000, easing = LinearEasing))
            }
            val motion = rememberInfiniteTransition(label = "wakeVisual")
            val phase by motion.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 6_000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wakeMotion"
            )
            LaunchedEffect(Unit) {
                while (true) {
                    now = LocalTime.now()
                    delay(1_000)
                }
            }
            val clock = now.format(DateTimeFormatter.ofPattern("HH:mm"))
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF080D18)) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val radius = min(w, h) * (0.28f + phase * 0.12f)
                        when (visualStyle) {
                            "pulse" -> {
                                drawRect(Color(0xFF080D18))
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFB6C9FF).copy(alpha = 0.62f * reveal.value),
                                            Color(0xFF6C86C8).copy(alpha = 0.24f * reveal.value),
                                            Color.Transparent
                                        ),
                                        center = Offset(w * 0.5f, h * 0.43f),
                                        radius = radius * 2.1f
                                    ),
                                    radius = radius * 2.1f,
                                    center = Offset(w * 0.5f, h * 0.43f)
                                )
                            }
                            "waves" -> {
                                drawRect(Color(0xFF07101B))
                                listOf(
                                    Triple(0.30f, 0.34f, Color(0xFF79C9C5)),
                                    Triple(0.72f, 0.48f, Color(0xFF8297E8)),
                                    Triple(0.48f, 0.68f, Color(0xFFC08BCB))
                                ).forEachIndexed { index, item ->
                                    val center = Offset(
                                        w * (item.first + (phase - 0.5f) * (if (index % 2 == 0) 0.10f else -0.10f)),
                                        h * item.second
                                    )
                                    val waveRadius = min(w, h) * (0.22f + phase * 0.10f + index * 0.035f)
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(item.third.copy(alpha = 0.32f * reveal.value), Color.Transparent),
                                            center = center,
                                            radius = waveRadius
                                        ),
                                        radius = waveRadius,
                                        center = center
                                    )
                                    drawCircle(
                                        color = item.third.copy(alpha = 0.20f * reveal.value),
                                        radius = waveRadius * 0.72f,
                                        center = center,
                                        style = Stroke(width = 2.dp.toPx())
                                    )
                                }
                            }
                            else -> {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF07152C),
                                            Color(0xFF233D68),
                                            Color(0xFF8A596B),
                                            Color(0xFFE7A66D)
                                        )
                                    )
                                )
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFFD8A0).copy(alpha = 0.72f * reveal.value),
                                            Color(0xFFE89A79).copy(alpha = 0.24f * reveal.value),
                                            Color.Transparent
                                        ),
                                        center = Offset(w * 0.5f, h * (0.72f - phase * 0.10f)),
                                        radius = min(w, h) * 0.72f
                                    ),
                                    radius = min(w, h) * 0.72f,
                                    center = Offset(w * 0.5f, h * (0.72f - phase * 0.10f))
                                )
                            }
                        }
                        drawRect(Color.Black.copy(alpha = 0.28f * (1f - reveal.value)))
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("TIME", color = Color(0xFFD0C4C6), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(28.dp))
                        Text(clock, color = Color(0xFFF0E8E9), fontSize = 76.sp, lineHeight = 82.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(label, color = Color(0xFFE0D5D7), style = MaterialTheme.typography.titleLarge)
                        if (guardSeconds > 0) {
                            Spacer(Modifier.height(16.dp))
                            Text("Кнопки разблокируются через $guardSeconds с", color = Color(0xFFD0C4C6), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(36.dp))
                        Button(
                            onClick = {
                                startActivity(Intent(this@AlarmRingingActivity, WakeCheckActivity::class.java)
                                    .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId))
                                finish()
                            },
                            enabled = guardSeconds == 0,
                            modifier = Modifier.fillMaxWidth().height(62.dp)
                        ) { Text("Я проснулся — решить задание") }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                WakeCheckRetryScheduler.schedule(this@AlarmRingingActivity, alarmId, snoozeMinutes)
                                stopService(Intent(this@AlarmRingingActivity, AlarmSoundService::class.java))
                                finish()
                            },
                            enabled = guardSeconds == 0,
                            modifier = Modifier.fillMaxWidth().height(58.dp)
                        ) { Text("Отложить на 5 минут") }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = {
                                WakeCheckRetryScheduler.cancel(this@AlarmRingingActivity, alarmId)
                                stopService(Intent(this@AlarmRingingActivity, AlarmSoundService::class.java))
                                finish()
                            },
                            enabled = guardSeconds == 0,
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                        ) { Text(if (guardSeconds > 0) "Отключение через $guardSeconds с" else "Отключить сигнал") }
                    }
                }
            }
            }
        }
    }

    private fun animateBrightness() {
        brightnessStartedAt = SystemClock.elapsedRealtime()
        val task = object : Runnable {
            override fun run() {
                val fraction = ((SystemClock.elapsedRealtime() - brightnessStartedAt).toFloat() / 60_000L)
                    .coerceIn(0f, 1f)
                window.attributes = window.attributes.apply {
                    screenBrightness = 0.12f + 0.38f * fraction
                }
                if (fraction < 1f) handler.postDelayed(this, 1_000L)
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
