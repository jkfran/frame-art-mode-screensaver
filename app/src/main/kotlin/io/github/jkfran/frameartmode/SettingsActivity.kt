package io.github.jkfran.frameartmode

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jkfran.frameartmode.control.ArtModeController
import io.github.jkfran.frameartmode.control.Discovery
import io.github.jkfran.frameartmode.control.FoundTv
import io.github.jkfran.frameartmode.control.Ipv4
import io.github.jkfran.frameartmode.control.Outcome
import io.github.jkfran.frameartmode.control.ScreensaverTiming
import io.github.jkfran.frameartmode.control.Trigger
import io.github.jkfran.frameartmode.control.TvConfig
import io.github.jkfran.frameartmode.tv.ArtMode
import io.github.jkfran.frameartmode.tv.DeviceInfo
import io.github.jkfran.frameartmode.tv.Timeouts
import io.github.jkfran.frameartmode.tv.TvException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app's only screen: choose the TV, test it, set up the screensaver, read the activity log. */
class SettingsActivity : Activity() {
    private val scope = MainScope()
    private val graph by lazy { AppGraph.get(this) }
    private var task: Job? = null

    private lateinit var tvSummary: TextView
    private lateinit var tvResult: TextView
    private lateinit var foundList: LinearLayout
    private lateinit var manualIp: EditText
    private lateinit var testNow: Button
    private lateinit var findTvs: Button
    private lateinit var screensaverStatus: TextView
    private lateinit var screensaverResult: TextView
    private lateinit var adbCommands: TextView
    private lateinit var openSystemSettings: Button
    private lateinit var endAfter: CheckBox
    private lateinit var endAfterDelay: Button
    private lateinit var powerKeyFallback: CheckBox
    private lateinit var pairRemote: Button
    private lateinit var optionsResult: TextView
    private lateinit var logList: LinearLayout
    private lateinit var openRepo: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvSummary = findViewById(R.id.tv_summary)
        tvResult = findViewById(R.id.tv_result)
        foundList = findViewById(R.id.found_list)
        manualIp = findViewById(R.id.manual_ip)
        testNow = findViewById(R.id.test_now)
        findTvs = findViewById(R.id.find_tvs)
        screensaverStatus = findViewById(R.id.screensaver_status)
        screensaverResult = findViewById(R.id.screensaver_result)
        adbCommands = findViewById(R.id.adb_commands)
        openSystemSettings = findViewById(R.id.open_system_settings)
        endAfter = findViewById(R.id.end_after)
        endAfterDelay = findViewById(R.id.end_after_delay)
        powerKeyFallback = findViewById(R.id.power_key_fallback)
        pairRemote = findViewById(R.id.pair_remote)
        optionsResult = findViewById(R.id.options_result)
        logList = findViewById(R.id.log_list)
        openRepo = findViewById(R.id.open_repo)

        testNow.setOnClickListener { testNow() }
        findViewById<Button>(R.id.check_tv).setOnClickListener { checkTv() }
        findTvs.setOnClickListener { findTvs() }
        findViewById<Button>(R.id.use_ip).setOnClickListener { useManualAddress() }
        manualIp.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // TV keyboards stay open unless the app closes them, and would hide the result.
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                useManualAddress()
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.set_screensaver).setOnClickListener { selectScreensaver() }
        findViewById<Button>(R.id.screensaver_delay).setOnClickListener { pickScreensaverDelay() }
        openSystemSettings.setOnClickListener { openSystemScreensaverSettings() }
        endAfter.setOnCheckedChangeListener { _, checked -> graph.prefs.endAfterArt = checked }
        endAfterDelay.setOnClickListener { pickEndAfterDelay() }
        powerKeyFallback.setOnCheckedChangeListener { _, checked ->
            graph.prefs.powerKeyFallback = checked
            if (checked && !graph.prefs.remotePaired) show(optionsResult, getString(R.string.pair_first))
        }
        pairRemote.setOnClickListener { pairRemote() }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            graph.prefs.eventLog.clear()
            refreshLog()
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.version, BuildConfig.VERSION_NAME)
        openRepo.setOnClickListener { openProjectPage() }

        refresh()
        if (savedInstanceState == null) {
            (if (graph.prefs.tv == null) findTvs else testNow).requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        refreshTv()
        refreshScreensaver()
        refreshOptions()
        refreshLog()
        openRepo.visibility = if (repoIntent().resolveActivity(packageManager) != null) View.VISIBLE else View.GONE
    }

    // --- TV -------------------------------------------------------------------------------

    private fun refreshTv() {
        val tv = graph.prefs.tv
        tvSummary.text = if (tv == null) {
            getString(R.string.tv_none)
        } else {
            getString(
                R.string.tv_summary,
                tv.name ?: getString(R.string.tv_unnamed),
                tv.modelName ?: getString(R.string.model_unknown),
                tv.host,
            )
        }
    }

    /** Runs one TV operation at a time and shows its result under the TV section. */
    private fun runTvTask(working: String, block: suspend () -> String) = runTask(tvResult, working, block)

    /** Runs one TV operation at a time, showing [working] and then the result in [target]. */
    private fun runTask(target: TextView, working: String, block: suspend () -> String) {
        if (task?.isActive == true) {
            show(target, getString(R.string.busy))
            return
        }
        show(target, working)
        task = scope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                getString(R.string.unexpected_error, e.toString())
            }
            show(target, message)
            refreshOptions()
            refreshLog()
        }
    }

    private fun testNow() = runTvTask(getString(R.string.working_test)) {
        val result = withContext(Dispatchers.IO) {
            graph.controller.switchToArtMode(Trigger.TEST, Timeouts.INTERACTIVE)
        }
        Messages.describe(this, result)
    }

    private fun checkTv() = runTvTask(getString(R.string.working_check)) {
        val report = withContext(Dispatchers.IO) { graph.controller.check(Timeouts.INTERACTIVE) }
        describe(report)
    }

    private fun describe(report: ArtModeController.CheckReport): String {
        val lines = mutableListOf<String>()
        report.info?.let { info ->
            lines += getString(R.string.check_name, info.name ?: getString(R.string.tv_unnamed))
            lines += getString(R.string.check_model, info.modelName ?: getString(R.string.model_unknown))
            lines += getString(R.string.check_power, info.powerState ?: getString(R.string.check_power_missing))
            lines += getString(if (info.frameTvSupport) R.string.check_frame_yes else R.string.check_frame_no)
        }
        report.artMode?.let { mode ->
            lines += getString(
                when (mode) {
                    ArtMode.ART -> R.string.check_art_on
                    ArtMode.TV -> R.string.check_art_off
                    ArtMode.UNKNOWN -> R.string.check_art_unknown
                },
            )
            lines += getString(R.string.check_api, report.apiVersion ?: getString(R.string.check_api_unknown))
        }
        if (report.result.outcome != Outcome.CHECK_OK) lines += Messages.describe(this, report.result)
        return lines.joinToString("\n")
    }

    private fun findTvs() = runTvTask(getString(R.string.working_scan)) {
        val addresses = withContext(Dispatchers.IO) { LocalNetwork.ipv4Addresses(this@SettingsActivity) }
        val hosts = addresses.flatMap { Discovery.hostsAround(it.address, it.prefixLength) }.distinct()
        if (hosts.isEmpty()) return@runTvTask getString(R.string.no_network)
        val found = withContext(Dispatchers.IO) { graph.discovery.scan(hosts) }
        showFound(found)
        if (found.isEmpty()) {
            getString(R.string.scan_none)
        } else {
            resources.getQuantityString(R.plurals.scan_found, found.size, found.size)
        }
    }

    private fun showFound(found: List<FoundTv>) {
        foundList.removeAllViews()
        val current = graph.prefs.tv
        for (tv in found) {
            val label = getString(
                R.string.found_tv,
                tv.info.name ?: getString(R.string.tv_unnamed),
                tv.info.modelName ?: getString(R.string.model_unknown),
                getString(if (tv.info.frameTvSupport) R.string.found_frame else R.string.found_not_frame),
                tv.host,
            )
            val button = Button(this, null, 0, R.style.TvButton).apply {
                text = if (current?.host == tv.host) getString(R.string.found_current, label) else label
                setOnClickListener { choose(tv.host, tv.info) }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.button_gap) }
            foundList.addView(button, params)
        }
        foundList.getChildAt(0)?.requestFocus()
    }

    private fun useManualAddress() {
        val address = manualIp.text.toString().trim()
        if (!Ipv4.isValid(address)) {
            manualIp.error = getString(R.string.invalid_ip)
            return
        }
        runTvTask(getString(R.string.working_lookup, address)) {
            val info = try {
                withContext(Dispatchers.IO) { graph.tv.deviceInfo(address, Timeouts.INTERACTIVE) }
            } catch (_: TvException) {
                return@runTvTask getString(R.string.lookup_failed, address)
            }
            choose(address, info)
        }
    }

    /** Stores [host] as the TV when it is a Frame; returns the message to show. */
    private fun choose(host: String, info: DeviceInfo): String {
        val name = info.name ?: info.modelName ?: host
        val message = if (!info.frameTvSupport) {
            getString(R.string.not_a_frame_choice, name)
        } else {
            graph.prefs.saveTv(TvConfig.from(host, info))
            foundList.removeAllViews()
            refreshTv()
            refreshOptions()
            testNow.requestFocus()
            getString(R.string.tv_chosen, name)
        }
        show(tvResult, message)
        return message
    }

    // --- Screensaver ----------------------------------------------------------------------

    private fun refreshScreensaver() {
        val state = ScreensaverSetup.read(this)
        screensaverStatus.text = when {
            state.components == null && state.enabled == null -> getString(R.string.screensaver_status_unknown)
            !ScreensaverSetup.isSelected(this, state) -> getString(R.string.screensaver_status_other)
            state.enabled == false -> getString(R.string.screensaver_status_ours_disabled)
            state.delayMs != null && !ScreensaverTiming.fits(state.delayMs, state.sleepLimitMs) -> getString(
                R.string.screensaver_status_too_long,
                formatDelay(state.delayMs),
                state.sleepLimitMs?.let(::formatDelay) ?: getString(R.string.delay_unknown),
            )
            else -> getString(
                R.string.screensaver_status_ours,
                state.delayMs?.let(::formatDelay) ?: getString(R.string.delay_unknown),
            )
        }
        openSystemSettings.visibility =
            if (ScreensaverSetup.systemSettingsIntent(this) != null) View.VISIBLE else View.GONE
    }

    private fun selectScreensaver() {
        if (ScreensaverSetup.select(this)) {
            adbCommands.visibility = View.GONE
            show(screensaverResult, getString(R.string.screensaver_set))
        } else {
            val message = if (ScreensaverSetup.canWriteSettings(this)) {
                getString(R.string.screensaver_set_failed)
            } else {
                getString(R.string.adb_needed)
            }
            show(screensaverResult, message)
            val state = ScreensaverSetup.read(this)
            val delay = ScreensaverTiming.suggestedDelayMs(state.delayMs, state.sleepLimitMs)
            show(adbCommands, ScreensaverSetup.adbCommands(this, delay))
        }
        refreshScreensaver()
    }

    private fun pickScreensaverDelay() {
        val state = ScreensaverSetup.read(this)
        val limit = state.sleepLimitMs
        // Only delays that leave the screensaver time to run before the device sleeps.
        val choices = ScreensaverTiming.choices(limit)
        if (limit != null && choices.isEmpty()) {
            show(screensaverResult, getString(R.string.delay_no_room, formatDelay(limit)))
            return
        }
        val labels = choices.map(::formatDelay).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (limit == null) getString(R.string.delay_title) else getString(R.string.delay_title_limited, formatDelay(limit)))
            .setSingleChoiceItems(labels, choices.indexOf(state.delayMs)) { dialog, which ->
                dialog.dismiss()
                applyScreensaverDelay(choices[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyScreensaverDelay(delayMs: Long) {
        when (ScreensaverSetup.setDelay(this, delayMs)) {
            ScreensaverSetup.DelayChange.SET -> {
                adbCommands.visibility = View.GONE
                show(screensaverResult, getString(R.string.delay_set, formatDelay(delayMs)))
            }
            ScreensaverSetup.DelayChange.TOO_LONG -> {
                val limit = ScreensaverSetup.read(this).sleepLimitMs
                show(
                    screensaverResult,
                    getString(R.string.delay_too_long, limit?.let(::formatDelay) ?: getString(R.string.delay_unknown)),
                )
            }
            ScreensaverSetup.DelayChange.NOT_ALLOWED -> {
                show(screensaverResult, getString(R.string.delay_needs_adb))
                show(adbCommands, getString(R.string.adb_delay_command, delayMs.toString()))
            }
        }
        refreshScreensaver()
    }

    private fun openSystemScreensaverSettings() {
        val intent = ScreensaverSetup.systemSettingsIntent(this)
        try {
            if (intent == null) throw ActivityNotFoundException()
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            show(screensaverResult, getString(R.string.no_system_settings))
        } catch (_: SecurityException) {
            show(screensaverResult, getString(R.string.no_system_settings))
        }
    }

    private fun formatDelay(delayMs: Long): String {
        val minutes = (delayMs / 60_000).toInt()
        return when {
            delayMs < 60_000 -> {
                val seconds = (delayMs / 1_000).toInt()
                resources.getQuantityString(R.plurals.seconds, seconds, seconds)
            }
            minutes % 60 == 0 -> resources.getQuantityString(R.plurals.hours, minutes / 60, minutes / 60)
            else -> resources.getQuantityString(R.plurals.minutes, minutes, minutes)
        }
    }

    // --- Options --------------------------------------------------------------------------

    private fun refreshOptions() {
        val prefs = graph.prefs
        endAfter.isChecked = prefs.endAfterArt
        powerKeyFallback.isChecked = prefs.powerKeyFallback
        endAfterDelay.text = getString(R.string.end_after_delay, formatDelay(prefs.endAfterSeconds * 1_000L))
        pairRemote.setText(if (prefs.remotePaired) R.string.pair_remote_done else R.string.pair_remote)
    }

    private fun pickEndAfterDelay() {
        val labels = END_AFTER_CHOICES_S.map { formatDelay(it * 1_000L) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.end_after_title)
            .setSingleChoiceItems(labels, END_AFTER_CHOICES_S.indexOf(graph.prefs.endAfterSeconds)) { dialog, which ->
                dialog.dismiss()
                graph.prefs.endAfterSeconds = END_AFTER_CHOICES_S[which]
                refreshOptions()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pairRemote() = runTask(optionsResult, getString(R.string.working_pair)) {
        val result = withContext(Dispatchers.IO) { graph.controller.pairRemote(Timeouts.INTERACTIVE) }
        Messages.describe(this, result)
    }

    // --- Activity log ---------------------------------------------------------------------

    private fun refreshLog() {
        logList.removeAllViews()
        val entries = graph.prefs.eventLog.entries().asReversed()
        if (entries.isEmpty()) {
            logList.addView(logEntryView(getString(R.string.log_empty)))
            return
        }
        for (entry in entries) logList.addView(logEntryView(Messages.logLine(this, entry)))
    }

    private fun logEntryView(text: String): TextView =
        TextView(this, null, 0, R.style.LogEntry).apply {
            this.text = text
            // Focusable so the D-pad can scroll through the log.
            isFocusable = true
        }

    // --- About ----------------------------------------------------------------------------

    private fun repoIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.repo_url)))

    private fun openProjectPage() {
        try {
            startActivity(repoIntent())
        } catch (_: ActivityNotFoundException) {
            openRepo.visibility = View.GONE
        }
    }

    private fun show(view: TextView, text: String) {
        view.text = text
        view.visibility = View.VISIBLE
    }

    private companion object {
        val END_AFTER_CHOICES_S = listOf(5, 10, 15, 30, 60, 120)
    }
}
