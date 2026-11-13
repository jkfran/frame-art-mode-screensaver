package io.github.jkfran.frameartmode

import android.content.Context
import android.content.SharedPreferences
import io.github.jkfran.frameartmode.control.EventLog
import io.github.jkfran.frameartmode.control.SettingsStore
import io.github.jkfran.frameartmode.control.TextStorage
import io.github.jkfran.frameartmode.control.TvConfig

/** App settings in private SharedPreferences, excluded from backups and device transfers. */
class Prefs(context: Context) : SettingsStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val eventLog = EventLog(object : TextStorage {
        override fun read(): String? = prefs.getString(KEY_LOG, null)
        override fun write(value: String?) {
            prefs.edit().apply { if (value == null) remove(KEY_LOG) else putString(KEY_LOG, value) }.apply()
        }
    })

    override val tv: TvConfig?
        get() {
            val host = prefs.getString(KEY_HOST, null) ?: return null
            return TvConfig(
                host = host,
                tvId = prefs.getString(KEY_TV_ID, null),
                wifiMac = prefs.getString(KEY_WIFI_MAC, null),
                modelName = prefs.getString(KEY_MODEL, null),
                name = prefs.getString(KEY_NAME, null),
            )
        }

    /** Stores the chosen TV. A different TV starts without the old token, pairing or terms state. */
    fun saveTv(config: TvConfig) {
        val sameTv = config.keepsAuthorisationFrom(tv)
        prefs.edit().apply {
            putString(KEY_HOST, config.host)
            putOrRemove(KEY_TV_ID, config.tvId)
            putOrRemove(KEY_WIFI_MAC, config.wifiMac)
            putOrRemove(KEY_MODEL, config.modelName)
            putOrRemove(KEY_NAME, config.name)
            if (!sameTv) {
                remove(KEY_TOKEN)
                remove(KEY_REMOTE_PAIRED)
                remove(KEY_TERMS_AT)
            }
        }.apply()
    }

    override var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().apply { putOrRemove(KEY_TOKEN, value) }.apply()

    override var termsNotAcceptedAt: Long?
        get() = if (prefs.contains(KEY_TERMS_AT)) prefs.getLong(KEY_TERMS_AT, 0) else null
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_TERMS_AT) else putLong(KEY_TERMS_AT, value) }.apply()

    override var powerKeyFallback: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_FALLBACK, value).apply()

    override var remotePaired: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_PAIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_PAIRED, value).apply()

    var endAfterArt: Boolean
        get() = prefs.getBoolean(KEY_END_AFTER, false)
        set(value) = prefs.edit().putBoolean(KEY_END_AFTER, value).apply()

    var endAfterSeconds: Int
        get() = prefs.getInt(KEY_END_AFTER_SECONDS, DEFAULT_END_AFTER_SECONDS)
        set(value) = prefs.edit().putInt(KEY_END_AFTER_SECONDS, value).apply()

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    companion object {
        const val DEFAULT_END_AFTER_SECONDS = 10
        private const val FILE = "frame_art_mode"
        private const val KEY_HOST = "tv_host"
        private const val KEY_TV_ID = "tv_id"
        private const val KEY_WIFI_MAC = "tv_wifi_mac"
        private const val KEY_MODEL = "tv_model"
        private const val KEY_NAME = "tv_name"
        private const val KEY_TOKEN = "tv_token"
        private const val KEY_TERMS_AT = "terms_not_accepted_at"
        private const val KEY_FALLBACK = "power_key_fallback"
        private const val KEY_REMOTE_PAIRED = "remote_paired"
        private const val KEY_END_AFTER = "end_after_art"
        private const val KEY_END_AFTER_SECONDS = "end_after_seconds"
        private const val KEY_LOG = "event_log"
    }
}
