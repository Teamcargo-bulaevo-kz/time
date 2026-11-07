package kz.teamcargo.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ALARM_ID) ?: return
        AlarmGuard.begin(context, id)
        val store = AlarmStore(context)
        val alarm = store.getAll().firstOrNull { it.id == id } ?: return

        if (alarm.repeatDays.isNotEmpty()) {
            AlarmScheduler(context).schedule(alarm)
        } else {
            store.saveAll(store.getAll().map { if (it.id == id) it.copy(enabled = false) else it })
        }

        val serviceIntent = Intent(context, AlarmSoundService::class.java)
            .putExtra(EXTRA_ALARM_ID, id)
            .putExtra(AlarmSoundService.EXTRA_LABEL, alarm.label)
            .putExtra(AlarmSoundService.EXTRA_SOUND_PROFILE, alarm.soundProfile)
            .putExtra(AlarmSoundService.EXTRA_RAMP_MINUTES, alarm.rampMinutes)
            .putExtra(AlarmSoundService.EXTRA_CUSTOM_URI, alarm.customSoundUri)
            .putExtra(AlarmSoundService.EXTRA_FLASH_ENABLED, alarm.flashEnabled)
            .putExtra(AlarmSoundService.EXTRA_VIBRATION_ENABLED, alarm.vibrationEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "kz.teamcargo.time.ALARM_ID"
        const val EXTRA_RINGING_ID = "kz.teamcargo.time.RINGING_ID"
        const val CHANNEL_ID = "time_alarm_channel"
    }
}
