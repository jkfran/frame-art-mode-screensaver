package io.github.jkfran.frameartmode.tv

/** What the TV reports at `GET /api/v2/`, reduced to the fields the app uses. */
data class DeviceInfo(
    val name: String?,
    val modelName: String?,
    /** "on" in TV mode and in Art Mode, "standby" when off; missing on some models. */
    val powerState: String?,
    val frameTvSupport: Boolean,
    val tokenAuthSupport: Boolean?,
    val id: String?,
    val wifiMac: String?,
) {
    val isOn: Boolean get() = powerState.equals("on", ignoreCase = true)

    companion object {
        /** Returns null unless [body] is a JSON object holding a `device` object. */
        fun parse(body: String): DeviceInfo? {
            val root = jsonObjectOrNull(body) ?: return null
            val device = root.optJSONObject("device") ?: return null
            return DeviceInfo(
                name = (device.stringOrNull("name") ?: root.stringOrNull("name"))?.let(::decodeEntities),
                modelName = device.stringOrNull("modelName"),
                powerState = device.stringOrNull("PowerState"),
                frameTvSupport = device.flagOrNull("FrameTVSupport") == true,
                tokenAuthSupport = device.flagOrNull("TokenAuthSupport"),
                id = device.stringOrNull("id") ?: device.stringOrNull("duid") ?: root.stringOrNull("id"),
                wifiMac = device.stringOrNull("wifiMac"),
            )
        }

        private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

        /** TVs may send HTML entities in their names, such as `&#39;` or `&amp;`. */
        internal fun decodeEntities(text: String): String {
            val numeric = NUMERIC_ENTITY.replace(text) { match ->
                val radix = if (match.groupValues[1].isEmpty()) 10 else 16
                val codePoint = match.groupValues[2].toIntOrNull(radix)
                if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                    String(Character.toChars(codePoint))
                } else {
                    match.value
                }
            }
            return numeric
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
        }
    }
}
