package kz.teamcargo.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(AlarmReceiver.EXTRA_ALARM_ID)?.let {
            WakeCheckRetryScheduler.cancel(context, it)
        }
        context.stopService(Intent(context, AlarmSoundService::class.java))
    }
}
