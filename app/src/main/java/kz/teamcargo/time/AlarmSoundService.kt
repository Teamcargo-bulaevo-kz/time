package kz.teamcargo.time

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class AlarmSoundService : Service() {
    private var player: MediaPlayer? = null
    private var ambient: AmbientAudioEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rampTask: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID).orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Пора просыпаться" }
        val profile = intent?.getStringExtra(EXTRA_SOUND_PROFILE).orEmpty().ifBlank { "rain" }
        val ramp = intent?.getIntExtra(EXTRA_RAMP_MINUTES, 10) ?: 10
        val customUri = intent?.getStringExtra(EXTRA_CUSTOM_URI)?.let(Uri::parse)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(alarmId, label))

        if (player == null && ambient == null) {
            when (profile) {
                "system" -> startAlarmSound(ramp)
                "custom" -> if (customUri != null) startMedia(customUri, ramp, true) else startAmbient("rain", ramp)
                else -> startAmbient(profile, ramp)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAmbient(profile: String, ramp: Int) {
        runCatching {
            ambient = AmbientAudioEngine(profile, ramp).also { it.start() }
        }.onFailure { startAlarmSound(ramp) }
    }

    private fun startAlarmSound(ramp: Int) {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        startMedia(uri, ramp, false)
    }

    private fun startMedia(uri: Uri, ramp: Int, fallbackToSystem: Boolean) {
        runCatching {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            media.isLooping = true
            media.setDataSource(this, uri)
            media.setOnPreparedListener { prepared ->
                prepared.setVolume(0.035f, 0.035f)
                prepared.start()
                scheduleMediaRamp(prepared, ramp)
            }
            media.setOnErrorListener { failed, _, _ ->
                runCatching { failed.release() }
                if (player === failed) player = null
                if (fallbackToSystem) startAlarmSound(ramp)
                true
            }
            media.prepareAsync()
        }.onFailure {
            player?.let { runCatching { it.release() } }
            player = null
            if (fallbackToSystem) startAlarmSound(ramp)
        }
    }

    private fun scheduleMediaRamp(media: MediaPlayer, rampMinutes: Int) {
        val duration = rampMinutes.coerceIn(0, 30) * 60_000L
        if (duration == 0L) {
            media.setVolume(1f, 1f)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        val task = object : Runnable {
            override fun run() {
                if (player !== media) return
                val fraction = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / duration).coerceIn(0f, 1f)
                val smooth = fraction * fraction * (3f - 2f * fraction)
                val volume = 0.035f + 0.965f * smooth
                runCatching { media.setVolume(volume, volume) }
                if (fraction < 1f) handler.postDelayed(this, 1_000L)
            }
        }
        rampTask = task
        handler.post(task)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                AlarmReceiver.CHANNEL_ID, "Будильник TIME", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Повторяющийся звуковой сигнал будильника"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun buildNotification(alarmId: String, label: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            this, 11, Intent(this, StopAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AlarmReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Будильник активен")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Остановить звук", stop)
            .build()
    }

    override fun onDestroy() {
        rampTask?.let(handler::removeCallbacks)
        rampTask = null
        player?.let { media -> runCatching { media.stop(); media.release() } }
        player = null
        ambient?.stop()
        ambient = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_LABEL = "kz.teamcargo.time.ALARM_LABEL"
        const val EXTRA_SOUND_PROFILE = "kz.teamcargo.time.SOUND_PROFILE"
        const val EXTRA_RAMP_MINUTES = "kz.teamcargo.time.RAMP_MINUTES"
        const val EXTRA_CUSTOM_URI = "kz.teamcargo.time.CUSTOM_URI"
        const val NOTIFICATION_ID = 7001
    }
}
