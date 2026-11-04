package kz.teamcargo.time

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.VibrationEffect
import android.os.Vibrator
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
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class AlarmSoundService : Service() {
    private var player: MediaPlayer? = null
    private var ambient: AmbientAudioEngine? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rampTask: Runnable? = null
    private var flashTask: Runnable? = null
    private var flashCameraId: String? = null
    private var flashOn = false
    private var vibrator: Vibrator? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID).orEmpty()
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Пора просыпаться" }
        val profile = intent?.getStringExtra(EXTRA_SOUND_PROFILE).orEmpty().ifBlank { "rain" }
        val ramp = intent?.getIntExtra(EXTRA_RAMP_MINUTES, 10) ?: 10
        val customUri = intent?.getStringExtra(EXTRA_CUSTOM_URI)?.let(Uri::parse)
        val flashEnabled = intent?.getBooleanExtra(EXTRA_FLASH_ENABLED, false) ?: false
        val vibrationEnabled = intent?.getBooleanExtra(EXTRA_VIBRATION_ENABLED, true) ?: true

        acquireScreenWakeLock()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(alarmId, label))
        if (vibrationEnabled) startVibration() else stopVibration()
        if (flashEnabled) startFlash() else stopFlash()

        if (player == null && ambient == null) {
            when (profile) {
                "system" -> startAlarmSound(ramp)
                "custom" -> if (customUri != null) startMedia(customUri, ramp, true) else startAmbient("rain", ramp)
                else -> startAmbient(profile, ramp)
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "TIME:AlarmScreenWake"
            ).apply { acquire(120_000L) }
        }.onFailure { screenWakeLock = null }
    }

    private fun startVibration() {
        if (vibrator != null) return
        val deviceVibrator = getSystemService(Vibrator::class.java) ?: return
        if (!deviceVibrator.hasVibrator()) return
        vibrator = deviceVibrator
        runCatching {
            deviceVibrator.vibrate(VibrationEffect.createWaveform(
                longArrayOf(0, 650, 450, 650, 1600),
                intArrayOf(0, 180, 0, 180, 0),
                0
            ))
        }
    }

    private fun startFlash() {
        if (flashTask != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(CameraManager::class.java) ?: return
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return
        flashCameraId = cameraId
        val task = object : Runnable {
            override fun run() {
                val id = flashCameraId ?: return
                flashOn = !flashOn
                runCatching { manager.setTorchMode(id, flashOn) }.onFailure { stopFlash() }
                if (flashCameraId != null) handler.postDelayed(this, if (flashOn) 450L else 900L)
            }
        }
        flashTask = task
        handler.post(task)
    }

    private fun stopVibration() {
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
    }

    private fun stopFlash() {
        flashTask?.let(handler::removeCallbacks)
        flashTask = null
        flashCameraId?.let { id ->
            runCatching { getSystemService(CameraManager::class.java)?.setTorchMode(id, false) }
        }
        flashCameraId = null
        flashOn = false
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
        val ringing = PendingIntent.getActivity(
            this, alarmId.hashCode() xor 0x4411,
            Intent(this, AlarmRingingActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val check = PendingIntent.getActivity(
            this, alarmId.hashCode(), Intent(this, WakeCheckActivity::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getBroadcast(
            this, 11, Intent(this, StopAlarmReceiver::class.java)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId),
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
            .setContentIntent(ringing)
            .setFullScreenIntent(ringing, true)
            .addAction(android.R.drawable.ic_media_pause, "Решить задачу", check)
            .addAction(android.R.drawable.ic_delete, "Аварийно отключить", stop)
            .build()
    }

    override fun onDestroy() {
        rampTask?.let(handler::removeCallbacks)
        rampTask = null
        screenWakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        screenWakeLock = null
        stopFlash()
        vibrator?.let { runCatching { it.cancel() } }
        vibrator = null
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
        const val EXTRA_FLASH_ENABLED = "kz.teamcargo.time.FLASH_ENABLED"
        const val EXTRA_VIBRATION_ENABLED = "kz.teamcargo.time.VIBRATION_ENABLED"
        const val NOTIFICATION_ID = 7001
    }
}
