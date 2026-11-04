package kz.teamcargo.time

import android.content.Context
import org.json.JSONArray

class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("time_alarms", Context.MODE_PRIVATE)

    fun getAll(): List<Alarm> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { Alarm.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveAll(alarms: List<Alarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    companion object { private const val KEY = "alarms_json" }
}
