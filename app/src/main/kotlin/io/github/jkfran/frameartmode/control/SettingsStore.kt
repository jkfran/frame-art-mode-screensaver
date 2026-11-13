package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.tv.DeviceInfo

/** The TV the user chose, and what identifies it. */
data class TvConfig(
    val host: String,
    val tvId: String?,
    val wifiMac: String?,
    val modelName: String?,
    val name: String?,
) {
    /** True when [info] comes from another TV than the one chosen. */
    fun isDifferentFrom(info: DeviceInfo): Boolean {
        if (tvId != null && info.id != null) return tvId != info.id
        if (wifiMac != null && info.wifiMac != null) return !wifiMac.equals(info.wifiMac, ignoreCase = true)
        return false
    }

    /** True when [other] describes the same TV: by ID, else by Wi-Fi MAC address, else by address. */
    fun isSameTv(other: TvConfig): Boolean = when {
        tvId != null && other.tvId != null -> tvId == other.tvId
        wifiMac != null && other.wifiMac != null -> wifiMac.equals(other.wifiMac, ignoreCase = true)
        else -> host == other.host
    }

    /**
     * True when choosing this TV after [previous] keeps the token, the remote-control pairing
     * and the Art Store terms state. Another TV must never get the previous one's token.
     */
    fun keepsAuthorisationFrom(previous: TvConfig?): Boolean = previous != null && previous.isSameTv(this)

    companion object {
        fun from(host: String, info: DeviceInfo) =
            TvConfig(host = host, tvId = info.id, wifiMac = info.wifiMac, modelName = info.modelName, name = info.name)
    }
}

/** What the controller reads and writes between attempts. */
interface SettingsStore {
    val tv: TvConfig?

    /** The token the TV issued for this app, if any. Never logged or shown. */
    var token: String?

    /** When the TV last refused Art Mode because its Art Store terms were not accepted. */
    var termsNotAcceptedAt: Long?

    /** The optional power-key fallback is switched on. */
    val powerKeyFallback: Boolean

    /** The TV has allowed this app on its remote-control channel. */
    var remotePaired: Boolean
}
