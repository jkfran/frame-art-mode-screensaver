package io.github.jkfran.frameartmode.tv

/** Deadlines, in milliseconds, for each step of talking to the TV. */
data class Timeouts(
    val restConnectMs: Long,
    val restReadMs: Long,
    val restCallMs: Long,
    val socketConnectMs: Long,
    /** `ms.channel.connect` (and `ms.channel.ready` on the art channel). */
    val handshakeMs: Long,
    val getMs: Long,
    val setMs: Long,
    val readBackMs: Long,
    val apiVersionMs: Long,
    val closeMs: Long,
    /** The whole attempt. */
    val totalMs: Long,
) {
    companion object {
        /** In the screensaver: short, nothing waits for a person. */
        val DREAM = Timeouts(
            restConnectMs = 2_000,
            restReadMs = 3_000,
            restCallMs = 4_000,
            socketConnectMs = 5_000,
            handshakeMs = 5_000,
            getMs = 5_000,
            setMs = 8_000,
            readBackMs = 2_000,
            apiVersionMs = 5_000,
            closeMs = 1_000,
            totalMs = 20_000,
        )

        /** From the settings screen: the handshake leaves time to answer a prompt on the TV. */
        val INTERACTIVE = Timeouts(
            restConnectMs = 3_000,
            restReadMs = 5_000,
            restCallMs = 6_000,
            socketConnectMs = 10_000,
            handshakeMs = 31_000,
            getMs = 5_000,
            setMs = 8_000,
            readBackMs = 5_000,
            apiVersionMs = 5_000,
            closeMs = 1_000,
            totalMs = 120_000,
        )

        /** Probing addresses during discovery. */
        val SCAN = DREAM.copy(restConnectMs = 500, restReadMs = 1_000, restCallMs = 1_500)
    }
}
