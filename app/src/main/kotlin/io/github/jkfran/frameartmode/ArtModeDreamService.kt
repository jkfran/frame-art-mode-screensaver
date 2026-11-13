package io.github.jkfran.frameartmode

import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.jkfran.frameartmode.control.DreamPolicy
import io.github.jkfran.frameartmode.control.Trigger
import io.github.jkfran.frameartmode.tv.Timeouts
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screensaver. It shows a black screen and asks the TV to switch to Art Mode.
 *
 * What happens after the attempt is decided by [DreamPolicy]: after a successful request it does
 * not finish by itself, because the TV puts this device to sleep over HDMI-CEC once Art Mode is
 * on. Ending early is an opt-in setting.
 */
class ArtModeDreamService : DreamService() {
    private var scope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        setContentView(R.layout.dream_view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val graph = AppGraph.get(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Screensaver work failed", e)
        })
        this.scope?.cancel()
        this.scope = scope
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                graph.controller.switchToArtMode(Trigger.DREAM, Timeouts.DREAM)
            }
            val action = DreamPolicy.after(result, graph.prefs.endAfterArt, graph.prefs.endAfterSeconds)
            action.notice?.let { showNotice(getString(R.string.dream_status, getString(Messages.outcomeText(it)))) }
            action.finishAfterMs?.let {
                delay(it)
                finish()
            }
        }
    }

    override fun onDreamingStopped() {
        stopWork()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        stopWork()
        super.onDetachedFromWindow()
    }

    private fun stopWork() {
        scope?.cancel()
        scope = null
    }

    /** Shows [text] and moves it now and then so it never stays on one spot of the panel. */
    private fun showNotice(text: String) {
        val view = findViewById<TextView>(R.id.dream_status) ?: return
        view.text = text
        view.visibility = View.VISIBLE
        scope?.launch {
            while (isActive) {
                view.post { moveSomewhere(view) }
                delay(NOTICE_MOVE_INTERVAL_MS)
            }
        }
    }

    private fun moveSomewhere(view: View) {
        val parent = view.parent as? View ?: return
        val maxX = parent.width - parent.paddingLeft - parent.paddingRight - view.width
        val maxY = parent.height - parent.paddingTop - parent.paddingBottom - view.height
        // The notice sits at the start edge, which is the right one in right-to-left languages.
        view.translationX = DreamPolicy.noticeOffset(maxX, fromRightEdge = view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        view.translationY = DreamPolicy.noticeOffset(maxY)
    }

    private companion object {
        const val TAG = "FrameArtMode"
        const val NOTICE_MOVE_INTERVAL_MS = 60_000L
    }
}
