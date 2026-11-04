package kz.teamcargo.time

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * Offline-generated ambient rain / thunder loop. No network or bundled copyrighted audio.
 * The loop is intentionally subtle and is a first sound-design pass, not a field recording.
 */
class AmbientAudioEngine(
    private val profile: String,
    private val rampMinutes: Int
) {
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var startedAt = 0L
    private var rampRunnable: Runnable? = null

    fun start() {
        val sampleRate = 22_050
        val samples = buildLoop(sampleRate, if (profile == "storm") 8 else 6)
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack.write(samples, 0, samples.size)
        audioTrack.setLoopPoints(0, samples.size, -1)
        audioTrack.setVolume(0.035f)
        audioTrack.play()
        track = audioTrack
        startedAt = android.os.SystemClock.elapsedRealtime()
        scheduleRamp()
    }

    private fun scheduleRamp() {
        val duration = rampMinutes.coerceIn(0, 30) * 60_000L
        if (duration == 0L) {
            track?.setVolume(0.85f)
            return
        }
        val task = object : Runnable {
            override fun run() {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                val fraction = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val smooth = fraction * fraction * (3f - 2f * fraction)
                track?.setVolume(0.035f + (0.85f - 0.035f) * smooth)
                if (fraction < 1f) handler.postDelayed(this, 1_000L)
            }
        }
        rampRunnable = task
        handler.post(task)
    }

    private fun buildLoop(sampleRate: Int, seconds: Int): ShortArray {
        val random = Random(42)
        val count = sampleRate * seconds
        val result = ShortArray(count)
        var low = 0.0
        var veryLow = 0.0
        for (i in 0 until count) {
            val white = random.nextDouble(-1.0, 1.0)
            low += 0.035 * (white - low)
            veryLow += 0.004 * (white - veryLow)
            val rain = (white * 0.30 + low * 1.7 + (white - low) * 0.28)
            val t = i.toDouble() / sampleRate
            val thunderEnvelope = if (profile == "storm") {
                val pulse1 = kotlin.math.exp(-((t - 1.7) / 1.1).let { it * it })
                val pulse2 = kotlin.math.exp(-((t - 6.2) / 1.5).let { it * it })
                (pulse1 + pulse2 * 0.75) * 0.9
            } else 0.0
            val rumble = veryLow * thunderEnvelope * 2.8
            val value = (rain * 0.22 + rumble).coerceIn(-0.85, 0.85)
            result[i] = (value * Short.MAX_VALUE).toInt().toShort()
        }

        // A short crossfade reduces clicks at the loop boundary.
        val fade = (sampleRate / 100).coerceAtMost(count / 2)
        for (i in 0 until fade) {
            val blend = i.toDouble() / fade
            val head = result[i].toDouble()
            val tailIndex = count - fade + i
            val tail = result[tailIndex].toDouble()
            val mixed = tail * (1.0 - blend) + head * blend
            val value = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            result[i] = value
            result[tailIndex] = value
        }
        return result
    }

    fun stop() {
        rampRunnable?.let(handler::removeCallbacks)
        rampRunnable = null
        track?.let { audio ->
            runCatching { audio.pause(); audio.flush(); audio.release() }
        }
        track = null
    }
}
