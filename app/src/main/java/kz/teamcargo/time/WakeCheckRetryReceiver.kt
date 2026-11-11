package kz.teamcargo.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class WakeCheckRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID) ?: return
        val alarm = AlarmStore(context).getAll().firstOrNull { it.id == id } ?: return
        // A retry is a fresh ringing session and receives the same 30-second lock.
        AlarmGuard.begin(context, id)
        val service = Intent(context, AlarmSoundService::class.java)
            .putExtra(AlarmReceiver.EXTRA_ALARM_ID, id)
            .putExtra(AlarmSoundService.EXTRA_LABEL, alarm.label)
            .putExtra(AlarmSoundService.EXTRA_SOUND_PROFILE, alarm.soundProfile)
            .putExtra(AlarmSoundService.EXTRA_RAMP_MINUTES, alarm.rampMinutes)
            .putExtra(AlarmSoundService.EXTRA_CUSTOM_URI, alarm.customSoundUri)
            .putExtra(AlarmSoundService.EXTRA_FLASH_ENABLED, alarm.flashEnabled)
            .putExtra(AlarmSoundService.EXTRA_VIBRATION_ENABLED, alarm.vibrationEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
        else context.startService(service)
    }
}
