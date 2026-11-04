package kz.teamcargo.time

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val repeatDays: Set<Int> = emptySet(),
    val label: String = ""
) {
    fun timeText(): String = "%02d:%02d".format(hour, minute)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("repeatDays", JSONArray().also { array -> repeatDays.sorted().forEach(array::put) })
        put("label", label)
    }

    companion object {
        fun fromJson(json: JSONObject): Alarm {
            val days = json.optJSONArray("repeatDays") ?: JSONArray()
            return Alarm(
                id = json.optString("id", UUID.randomUUID().toString()),
                hour = json.optInt("hour", 8).coerceIn(0, 23),
                minute = json.optInt("minute", 0).coerceIn(0, 59),
                enabled = json.optBoolean("enabled", true),
                repeatDays = (0 until days.length()).map { days.optInt(it) }.filter { it in 1..7 }.toSet(),
                label = json.optString("label", "")
            )
        }
    }
}
