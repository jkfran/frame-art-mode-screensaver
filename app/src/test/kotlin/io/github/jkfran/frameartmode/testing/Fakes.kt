package io.github.jkfran.frameartmode.testing

import io.github.jkfran.frameartmode.control.SettingsStore
import io.github.jkfran.frameartmode.control.TextStorage
import io.github.jkfran.frameartmode.control.TvConfig
import io.github.jkfran.frameartmode.tv.ArtMode
import io.github.jkfran.frameartmode.tv.ArtSession
import io.github.jkfran.frameartmode.tv.DeviceInfo
import io.github.jkfran.frameartmode.tv.FrameTv
import io.github.jkfran.frameartmode.tv.Timeouts
import io.github.jkfran.frameartmode.tv.TvException
import kotlinx.coroutines.delay

val CHOSEN_TV = TvConfig(
    host = "192.0.2.20",
    tvId = "uuid:tv-1",
    wifiMac = "00:00:5E:00:53:01",
    modelName = "QE55LS03BAUXXU",
    name = "Samsung The Frame 55",
)

fun frameInfo(
    power: String? = "on",
    frame: Boolean = true,
    id: String? = "uuid:tv-1",
    mac: String? = "00:00:5E:00:53:01",
) = DeviceInfo(
    name = "Samsung The Frame 55",
    modelName = "QE55LS03BAUXXU",
    powerState = power,
    frameTvSupport = frame,
    tokenAuthSupport = true,
    id = id,
    wifiMac = mac,
)

class MemoryStore(
    override var tv: TvConfig? = CHOSEN_TV,
    override var token: String? = null,
    override var termsNotAcceptedAt: Long? = null,
    override var powerKeyFallback: Boolean = false,
    override var remotePaired: Boolean = false,
) : SettingsStore

class MemoryText(var value: String? = null) : TextStorage {
    override fun read(): String? = value
    override fun write(value: String?) {
        this.value = value
    }
}

/** A scripted art session: [modes] answer successive status reads (an exception is thrown). */
class FakeSession(vararg modes: Any) : ArtSession {
    val modes = ArrayDeque(modes.toList())
    var setError: TvException? = null
    var setSuspendsMs = 0L
    var apiVersionError: TvException? = null
    var gets = 0
    var sets = 0
    var closes = 0

    override suspend fun artMode(timeoutMs: Long): ArtMode {
        gets++
        return when (val next = modes.removeFirstOrNull() ?: ArtMode.TV) {
            is TvException -> throw next
            else -> next as ArtMode
        }
    }

    override suspend fun setArtMode(on: Boolean, timeoutMs: Long) {
        check(on) { "the app only ever asks for Art Mode on" }
        sets++
        if (setSuspendsMs > 0) delay(setSuspendsMs)
        setError?.let { throw it }
    }

    override suspend fun apiVersion(timeoutMs: Long): String {
        apiVersionError?.let { throw it }
        return "4.3.4.0"
    }

    override suspend fun close(timeoutMs: Long) {
        closes++
    }
}

class FakeFrameTv(
    var info: DeviceInfo = frameInfo(),
    var session: FakeSession = FakeSession(ArtMode.TV, ArtMode.ART),
) : FrameTv {
    var deviceInfoError: TvException? = null
    /** Answers successive [deviceInfo] calls (a [DeviceInfo] or a thrown [TvException]) before [info] does. */
    val infoReplies = ArrayDeque<Any>()
    /** Thrown by successive [openArt] calls before one succeeds. */
    val openFailures = ArrayDeque<Exception>()
    var issueToken: String? = null
    var powerKeyError: TvException? = null
    /** How long [clickPowerKey] waits after sending, as the real client does. */
    var powerKeySettleMs = 0L
    var pairError: TvException? = null
    val calls = mutableListOf<String>()
    val tokensSent = mutableListOf<String?>()

    val opens get() = calls.count { it == "open" }
    val powerKeys get() = calls.count { it == "power" }

    override suspend fun deviceInfo(host: String, timeouts: Timeouts): DeviceInfo {
        calls += "info"
        when (val next = infoReplies.removeFirstOrNull()) {
            is TvException -> throw next
            is DeviceInfo -> return next
        }
        deviceInfoError?.let { throw it }
        return info
    }

    override suspend fun openArt(host: String, token: String?, timeouts: Timeouts, onToken: (String) -> Unit): ArtSession {
        calls += "open"
        tokensSent += token
        openFailures.removeFirstOrNull()?.let { throw it }
        issueToken?.let(onToken)
        return session
    }

    override suspend fun pairRemote(host: String, token: String?, timeouts: Timeouts, onToken: (String) -> Unit) {
        calls += "pair"
        pairError?.let { throw it }
        issueToken?.let(onToken)
    }

    override suspend fun clickPowerKey(
        host: String,
        token: String?,
        timeouts: Timeouts,
        onSent: () -> Unit,
        onToken: (String) -> Unit,
    ) {
        calls += "power"
        powerKeyError?.let { throw it }
        onSent()
        if (powerKeySettleMs > 0) delay(powerKeySettleMs)
    }
}

/** Short deadlines for tests against a local server. */
val FAST = Timeouts.DREAM.copy(
    socketConnectMs = 3_000,
    handshakeMs = 1_500,
    getMs = 1_500,
    setMs = 700,
    readBackMs = 500,
    apiVersionMs = 700,
    closeMs = 300,
    totalMs = 15_000,
)
