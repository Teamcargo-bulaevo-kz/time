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
    val label: String = "",
    val soundProfile: String = "rain",
    val rampMinutes: Int = 10,
    val customSoundUri: String? = null,
    val flashEnabled: Boolean = true,
    val vibrationEnabled: Boolean = false,
    val retryMinutes: Int = 5,
    val challengeMode: String = "shake",
    val flashPattern: String = "classic",
    val wakeVisualStyle: String = "sunrise"
) {
    fun timeText(): String = "%02d:%02d".format(hour, minute)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("repeatDays", JSONArray().also { array -> repeatDays.sorted().forEach(array::put) })
        put("label", label)
        put("soundProfile", soundProfile)
        put("rampMinutes", rampMinutes)
        put("customSoundUri", customSoundUri ?: JSONObject.NULL)
        put("flashEnabled", flashEnabled)
        put("vibrationEnabled", vibrationEnabled)
        put("retryMinutes", retryMinutes)
        put("challengeMode", challengeMode)
        put("flashPattern", flashPattern)
        put("wakeVisualStyle", wakeVisualStyle)
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
                label = json.optString("label", ""),
                soundProfile = json.optString("soundProfile", "rain").let { if (it in setOf("rain", "storm", "system", "custom")) it else "rain" },
                rampMinutes = json.optInt("rampMinutes", 10).coerceIn(0, 30),
                customSoundUri = if (json.isNull("customSoundUri")) null else json.optString("customSoundUri"),
                flashEnabled = json.optBoolean("flashEnabled", true),
                vibrationEnabled = json.optBoolean("vibrationEnabled", false),
                retryMinutes = json.optInt("retryMinutes", 5).coerceIn(1, 30),
                challengeMode = json.optString("challengeMode", "shake").let { if (it in setOf("schulte", "math", "shake")) it else "shake" },
                flashPattern = json.optString("flashPattern", "classic").let { if (it in setOf("classic", "double", "rapid", "slow", "heartbeat")) it else "classic" },
                wakeVisualStyle = json.optString("wakeVisualStyle", "sunrise").let { if (it in setOf("sunrise", "pulse", "waves")) it else "sunrise" }
            )
        }
    }
}
