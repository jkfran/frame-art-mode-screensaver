package io.github.jkfran.frameartmode.tv

import org.json.JSONObject

/** The value of [key] as text, or null when it is missing, JSON null or blank. */
internal fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    val text = value as? String ?: value?.toString()
    return text?.takeIf { it.isNotBlank() }
}

/** A flag that Samsung TVs send as the string "true"/"false"; a JSON boolean is accepted too. */
internal fun JSONObject.flagOrNull(key: String): Boolean? {
    val value: Any? = opt(key)
    return when (value) {
        is Boolean -> value
        is String -> when (value.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

/** Parses [text] as a JSON object, or returns null. */
internal fun jsonObjectOrNull(text: String): JSONObject? =
    try {
        JSONObject(text)
    } catch (_: Exception) {
        null
    }
