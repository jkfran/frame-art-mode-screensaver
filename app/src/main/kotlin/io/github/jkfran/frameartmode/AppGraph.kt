package io.github.jkfran.frameartmode

import android.content.Context
import android.util.Log
import io.github.jkfran.frameartmode.control.ArtModeController
import io.github.jkfran.frameartmode.control.Discovery
import io.github.jkfran.frameartmode.tv.FrameTv
import io.github.jkfran.frameartmode.tv.FrameTvClient
import io.github.jkfran.frameartmode.tv.Logger
import io.github.jkfran.frameartmode.tv.Timeouts
import io.github.jkfran.frameartmode.tv.TvException
import io.github.jkfran.frameartmode.tv.TvPorts

/** The app's few long-lived objects, shared by the screensaver and the settings screen. */
class AppGraph private constructor(context: Context) {
    val prefs = Prefs(context)

    private val logger = Logger { Log.i(TAG, it) }

    val tv: FrameTv = FrameTvClient(logger = logger)

    val controller = ArtModeController(tv, prefs, prefs.eventLog, logger = logger)

    // Discovery probes plain HTTP only; a refused port 8001 means "not a Samsung TV" there.
    private val scanClient = FrameTvClient(TvPorts(restTlsFallback = null))

    val discovery = Discovery(probe = { host ->
        try {
            scanClient.deviceInfo(host, Timeouts.SCAN)
        } catch (_: TvException) {
            null
        }
    })

    companion object {
        private const val TAG = "FrameArtMode"

        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }
    }
}
