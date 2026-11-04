package kz.teamcargo.time

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SleepRecord(
    val startedAt: Long,
    val endedAt: Long,
    val quality: Int
) {
    val durationMinutes: Long
        get() = ((endedAt - startedAt) / 60_000L).coerceAtLeast(0)
}

class SleepJournalStore(context: Context) {
    private val prefs = context.getSharedPreferences("sleep_journal", Context.MODE_PRIVATE)

    fun activeStart(): Long? = prefs.getLong("active_start", -1L).takeIf { it > 0L }

    fun setActiveStart(timestamp: Long?) {
        prefs.edit().apply {
            if (timestamp == null) remove("active_start") else putLong("active_start", timestamp)
        }.apply()
    }

    fun getRecords(): List<SleepRecord> {
        val raw = prefs.getString("records", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val start = item.optLong("startedAt", 0L)
                val end = item.optLong("endedAt", 0L)
                if (start <= 0L || end <= start) null
                else SleepRecord(start, end, item.optInt("quality", 3).coerceIn(1, 5))
            }.sortedByDescending { it.endedAt }
        }.getOrDefault(emptyList())
    }

    fun addRecord(record: SleepRecord) {
        saveRecords((listOf(record) + getRecords()).take(30))
        prefs.edit().remove("active_start").apply()
    }

    fun updateRecord(originalStartedAt: Long, record: SleepRecord) {
        val updated = getRecords().map {
            if (it.startedAt == originalStartedAt) record else it
        }
        saveRecords(updated)
    }

    fun deleteRecord(startedAt: Long) {
        saveRecords(getRecords().filterNot { it.startedAt == startedAt })
    }

    private fun saveRecords(records: List<SleepRecord>) {
        val array = JSONArray()
        records.sortedByDescending { it.endedAt }.take(30).forEach { item ->
            array.put(JSONObject().apply {
                put("startedAt", item.startedAt)
                put("endedAt", item.endedAt)
                put("quality", item.quality.coerceIn(1, 5))
            })
        }
        prefs.edit().putString("records", array.toString()).apply()
    }
}
