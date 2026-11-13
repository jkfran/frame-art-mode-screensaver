package io.github.jkfran.frameartmode.control

import org.json.JSONArray
import org.json.JSONObject

/** Where the event log keeps its text. */
interface TextStorage {
    fun read(): String?
    fun write(value: String?)
}

/** The last [capacity] attempts, oldest first, kept as JSON in [storage]. */
class EventLog(private val storage: TextStorage, private val capacity: Int = DEFAULT_CAPACITY) {

    data class Entry(
        val time: Long,
        val trigger: Trigger,
        val step: Step,
        val outcome: Outcome,
        val detail: String?,
    )

    @Synchronized
    fun append(entry: Entry) {
        val entries = (entries() + entry.copy(detail = sanitize(entry.detail))).takeLast(capacity)
        storage.write(encode(entries))
    }

    @Synchronized
    fun entries(): List<Entry> = decode(storage.read())

    @Synchronized
    fun clear() = storage.write(null)

    companion object {
        const val DEFAULT_CAPACITY = 50
        private const val MAX_DETAIL = 300
        private val TOKEN = Regex("token=[^&\\s\"]+")

        /** Details never carry a token, and stay short. */
        fun sanitize(detail: String?): String? =
            detail?.replace(TOKEN, "token=***")?.take(MAX_DETAIL)?.takeIf { it.isNotBlank() }

        private fun encode(entries: List<Entry>): String {
            val array = JSONArray()
            for (entry in entries) {
                array.put(
                    JSONObject()
                        .put("time", entry.time)
                        .put("trigger", entry.trigger.name)
                        .put("step", entry.step.name)
                        .put("outcome", entry.outcome.name)
                        .apply { entry.detail?.let { put("detail", it) } },
                )
            }
            return array.toString()
        }

        private fun decode(text: String?): List<Entry> {
            if (text.isNullOrEmpty()) return emptyList()
            val array = try {
                JSONArray(text)
            } catch (_: Exception) {
                return emptyList()
            }
            return (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    time = item.optLong("time"),
                    trigger = enumOrNull<Trigger>(item.optString("trigger")) ?: return@mapNotNull null,
                    step = enumOrNull<Step>(item.optString("step")) ?: return@mapNotNull null,
                    outcome = enumOrNull<Outcome>(item.optString("outcome")) ?: return@mapNotNull null,
                    detail = if (item.has("detail")) item.optString("detail") else null,
                )
            }
        }

        private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
            enumValues<T>().firstOrNull { it.name == name }
    }
}
