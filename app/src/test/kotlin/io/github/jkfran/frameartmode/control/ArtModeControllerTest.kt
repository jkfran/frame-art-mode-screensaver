package io.github.jkfran.frameartmode.control

import io.github.jkfran.frameartmode.testing.FakeFrameTv
import io.github.jkfran.frameartmode.testing.FakeSession
import io.github.jkfran.frameartmode.testing.MemoryStore
import io.github.jkfran.frameartmode.testing.MemoryText
import io.github.jkfran.frameartmode.testing.frameInfo
import io.github.jkfran.frameartmode.tv.ArtMode
import io.github.jkfran.frameartmode.tv.SetReply
import io.github.jkfran.frameartmode.tv.Timeouts
import io.github.jkfran.frameartmode.tv.TvException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision table, with a scripted TV. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtModeControllerTest {
    private val tv = FakeFrameTv()
    private val store = MemoryStore()
    private val log = EventLog(MemoryText())
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()

    private fun TestScope.controller() = ArtModeController(
        tv = tv,
        store = store,
        eventLog = log,
        clock = { now },
        pause = { pauses += it; kotlinx.coroutines.delay(it) },
    )

    private suspend fun TestScope.dream() = controller().switchToArtMode(Trigger.DREAM, Timeouts.DREAM)

    @Test
    fun `no TV chosen`() = runTest {
        store.tv = null
        assertEquals(Outcome.NOT_CONFIGURED, dream().outcome)
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun `TV that does not answer is left alone`() = runTest {
        tv.deviceInfoError = TvException.Unreachable("refused")
        assertEquals(Outcome.TV_UNREACHABLE, dream().outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `TV in standby is left alone`() = runTest {
        tv.info = frameInfo(power = "standby")
        assertEquals(Outcome.TV_OFF, dream().outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `TV without a power state is left alone`() = runTest {
        tv.info = frameInfo(power = null)
        assertEquals(Outcome.POWER_STATE_UNKNOWN, dream().outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `another TV at the address is left alone and gets no token`() = runTest {
        store.token = "12345678"
        tv.info = frameInfo(id = "uuid:other", mac = "00:00:5E:00:53:99")
        assertEquals(Outcome.DIFFERENT_TV, dream().outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `a TV that is not a Frame is left alone`() = runTest {
        tv.info = frameInfo(frame = false)
        assertEquals(Outcome.NOT_A_FRAME, dream().outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `already in Art Mode sends nothing more`() = runTest {
        tv.session = FakeSession(ArtMode.ART)
        val result = dream()
        assertEquals(Outcome.ALREADY_ART, result.outcome)
        assertEquals(0, tv.session.sets)
        assertEquals(1, tv.session.closes)
    }

    @Test
    fun `switches and confirms`() = runTest {
        tv.session = FakeSession(ArtMode.TV, ArtMode.ART)
        val result = dream()
        assertEquals(Outcome.ART_CONFIRMED, result.outcome)
        assertEquals(1, tv.session.sets)
        assertEquals(2, tv.session.gets)
        assertEquals(1, tv.session.closes)
        assertTrue(result.outcome.success)
    }

    @Test
    fun `the TV's announcement confirms without a read-back`() = runTest {
        tv.session = FakeSession(ArtMode.TV).apply { setReply = SetReply.ANNOUNCED }
        val result = dream()
        assertEquals(Outcome.ART_CONFIRMED, result.outcome)
        assertEquals(Step.SET_STATUS, result.step)
        assertNull(result.fallback)
        assertEquals(1, tv.session.sets)
        assertEquals(1, tv.session.gets)
        assertEquals(1, tv.session.closes)
    }

    @Test
    fun `a read-back that does not confirm is still a success and is not repeated`() = runTest {
        tv.session = FakeSession(ArtMode.TV, TvException.RequestTimeout("get_artmode_status"))
        val result = dream()
        assertEquals(Outcome.ART_REQUESTED, result.outcome)
        assertEquals(1, tv.session.sets)
    }

    @Test
    fun `an unreadable status still lets the request decide`() = runTest {
        tv.session = FakeSession(TvException.RequestFailed("get_artmode_status", -1, "-1"), ArtMode.ART)
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
        assertEquals(1, tv.session.sets)
    }

    @Test
    fun `terms not accepted stops, is remembered, and is never followed by a key`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.RequestFailed("set_artmode_status", -2, "-2") }
        val result = dream()
        assertEquals(Outcome.TERMS_NOT_ACCEPTED, result.outcome)
        assertNull(result.fallback)
        assertEquals(1, tv.session.sets)
        assertEquals(1, tv.opens)
        assertEquals(0, tv.powerKeys)
        assertEquals(now, store.termsNotAcceptedAt)
    }

    @Test
    fun `while the terms are pending the screensaver sends nothing`() = runTest {
        store.termsNotAcceptedAt = now - 60_000
        assertEquals(Outcome.TERMS_PENDING, dream().outcome)
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun `a day later the screensaver asks once more`() = runTest {
        store.termsNotAcceptedAt = now - ArtModeController.TERMS_RETRY_AFTER_MS
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
    }

    @Test
    fun `test now clears the terms flag`() = runTest {
        store.termsNotAcceptedAt = now - 60_000
        val result = controller().switchToArtMode(Trigger.TEST, Timeouts.INTERACTIVE)
        assertEquals(Outcome.ART_CONFIRMED, result.outcome)
        assertNull(store.termsNotAcceptedAt)
    }

    @Test
    fun `other refusals are not retried`() = runTest {
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.RequestFailed("set_artmode_status", -1, "-1") }
        val result = dream()
        assertEquals(Outcome.ART_REQUEST_FAILED, result.outcome)
        assertEquals("error_code -1", result.detail)
        assertEquals(1, tv.session.sets)
        assertNull(store.termsNotAcceptedAt)
    }

    @Test
    fun `a request that times out is never re-sent`() = runTest {
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.RequestTimeout("set_artmode_status") }
        assertEquals(Outcome.REQUEST_TIMEOUT, dream().outcome)
        assertEquals(1, tv.session.sets)
        assertEquals(1, tv.opens)
    }

    @Test
    fun `transient connection failure then success`() = runTest {
        tv.openFailures += TvException.ConnectFailed("refused", retryable = true)
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
        assertEquals(2, tv.opens)
        assertEquals(listOf(1_000L), pauses)
    }

    @Test
    fun `two transient failures then success, with backoff`() = runTest {
        tv.openFailures += TvException.ConnectFailed("refused", retryable = true)
        tv.openFailures += TvException.ConnectFailed("timed out", retryable = true)
        val start = currentTime
        assertEquals(Outcome.ART_CONFIRMED, dream().outcome)
        assertEquals(3, tv.opens)
        assertEquals(listOf(1_000L, 2_000L), pauses)
        assertEquals(3_000L, currentTime - start)
    }

    @Test
    fun `at most two retries`() = runTest {
        repeat(5) { tv.openFailures += TvException.ConnectFailed("refused", retryable = true) }
        val result = dream()
        assertEquals(Outcome.CONNECT_FAILED, result.outcome)
        assertEquals(3, tv.opens)
        assertEquals(0, tv.session.sets)
    }

    @Test
    fun `a refused upgrade is not retried`() = runTest {
        tv.openFailures += TvException.ConnectFailed("HTTP 403", retryable = false)
        assertEquals(Outcome.CONNECT_FAILED, dream().outcome)
        assertEquals(1, tv.opens)
    }

    @Test
    fun `access denied clears the token and is not retried`() = runTest {
        store.token = "12345678"
        store.remotePaired = true
        tv.openFailures += TvException.AccessDenied()
        assertEquals(Outcome.ACCESS_DENIED, dream().outcome)
        assertNull(store.token)
        assertEquals(false, store.remotePaired)
        assertEquals(1, tv.opens)
    }

    @Test
    fun `channel timeout is not retried`() = runTest {
        tv.openFailures += TvException.ChannelTimeout("ms.channel.timeOut")
        assertEquals(Outcome.CHANNEL_TIMEOUT, dream().outcome)
        assertEquals(1, tv.opens)
    }

    @Test
    fun `a token the TV issues is stored and sent next time`() = runTest {
        tv.issueToken = "12345678"
        dream()
        assertEquals("12345678", store.token)
        assertEquals(listOf<String?>(null), tv.tokensSent)
        tv.session = FakeSession(ArtMode.ART)
        dream()
        assertEquals(listOf(null, "12345678"), tv.tokensSent)
    }

    // --- power-key fallback ---------------------------------------------------------------

    @Test
    fun `fallback sends one key when the art channel is unreachable`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        repeat(3) { tv.openFailures += TvException.ConnectFailed("refused", retryable = true) }
        val result = dream()
        assertEquals(Outcome.CONNECT_FAILED, result.outcome)
        assertEquals(Outcome.POWER_KEY_SENT, result.fallback?.outcome)
        assertEquals(1, tv.powerKeys)
    }

    @Test
    fun `fallback after another refusal and after an error from the art channel`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.RequestFailed("set_artmode_status", -1, "-1") }
        assertEquals(Outcome.POWER_KEY_SENT, dream().fallback?.outcome)

        tv.openFailures += TvException.TvError("unrecognized method value : ms.remote.control")
        val result = dream()
        assertEquals(Outcome.TV_ERROR, result.outcome)
        assertEquals(Outcome.POWER_KEY_SENT, result.fallback?.outcome)
        assertEquals(2, tv.powerKeys)
    }

    @Test
    fun `no fallback after silence, which may mean standby or a TV already showing art`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.openFailures += TvException.ChannelTimeout("ms.channel.timeOut")
        assertEquals(Outcome.CHANNEL_TIMEOUT, dream().outcome)

        tv.session = FakeSession(TvException.RequestTimeout("get_artmode_status"))
        val result = dream()
        assertEquals(Outcome.REQUEST_TIMEOUT, result.outcome)
        assertNull(result.fallback)
        assertEquals(0, tv.session.sets)

        tv.session = FakeSession(TvException.ConnectionLost("get_artmode_status"))
        assertEquals(Outcome.CONNECTION_LOST, dream().outcome)
        assertEquals(0, tv.powerKeys)
        assertTrue(log.entries().none { it.step == Step.POWER_KEY })
    }

    @Test
    fun `no fallback after an error frame once the request was sent`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.TvError("unexpected") }
        val result = dream()
        assertEquals(Outcome.TV_ERROR, result.outcome)
        assertEquals(Step.SET_STATUS, result.step)
        assertNull(result.fallback)
        assertEquals(1, tv.session.sets)
        assertEquals(0, tv.powerKeys)
    }

    @Test
    fun `fallback checks again right before the key that the TV is on and the same`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true

        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        tv.infoReplies += frameInfo()
        tv.infoReplies += frameInfo(power = "standby")
        var result = dream()
        assertEquals(Outcome.CONNECT_FAILED, result.outcome)
        assertEquals(Outcome.TV_OFF, result.fallback?.outcome)
        assertEquals(Step.POWER_KEY, result.fallback?.step)
        assertEquals(listOf("info", "open", "info"), tv.calls)

        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        tv.infoReplies += frameInfo()
        tv.infoReplies += TvException.Unreachable("timeout")
        result = dream()
        assertEquals(Outcome.TV_UNREACHABLE, result.fallback?.outcome)

        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        tv.infoReplies += frameInfo()
        tv.infoReplies += frameInfo(id = "uuid:other", mac = "00:00:5E:00:53:99")
        result = dream()
        assertEquals(Outcome.DIFFERENT_TV, result.fallback?.outcome)
        assertEquals(0, tv.powerKeys)

        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        result = dream()
        assertEquals(Outcome.POWER_KEY_SENT, result.fallback?.outcome)
        assertEquals(listOf("info", "open", "info", "power"), tv.calls.takeLast(4))
    }

    @Test
    fun `no fallback when it is off or not paired`() = runTest {
        repeat(3) { tv.openFailures += TvException.ConnectFailed("refused", retryable = true) }
        store.powerKeyFallback = false
        store.remotePaired = true
        assertNull(dream().fallback)

        repeat(3) { tv.openFailures += TvException.ConnectFailed("refused", retryable = true) }
        store.powerKeyFallback = true
        store.remotePaired = false
        assertNull(dream().fallback)
        assertEquals(0, tv.powerKeys)
    }

    @Test
    fun `no fallback after a request that may have worked, nor after access denied`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.RequestTimeout("set_artmode_status") }
        assertNull(dream().fallback)
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.ConnectionLost("set_artmode_status") }
        assertNull(dream().fallback)
        tv.session = FakeSession(ArtMode.TV).apply { setError = TvException.TvError(null) }
        assertNull(dream().fallback)
        tv.session = FakeSession(ArtMode.ART)
        assertNull(dream().fallback)
        tv.openFailures += TvException.AccessDenied()
        assertNull(dream().fallback)
        assertEquals(0, tv.powerKeys)
    }

    @Test
    fun `no fallback when the TV is off`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.info = frameInfo(power = "standby")
        assertNull(dream().fallback)
        tv.deviceInfoError = TvException.Unreachable("timeout")
        assertNull(dream().fallback)
        assertEquals(0, tv.powerKeys)
    }

    // --- limits, cancellation and the log -------------------------------------------------

    @Test
    fun `the whole attempt has a time limit`() = runTest {
        tv.session = FakeSession(ArtMode.TV).apply { setSuspendsMs = 60_000 }
        val result = dream()
        assertEquals(Outcome.TIME_LIMIT, result.outcome)
        assertEquals(Step.SET_STATUS, result.step)
        assertEquals(1, tv.session.sets)
        assertEquals(1, tv.session.closes)
    }

    @Test
    fun `a screensaver that ends after the request is logged as such`() = runTest {
        tv.session = FakeSession(ArtMode.TV).apply { setSuspendsMs = 10_000 }
        val job = launch { dream() }
        runCurrent()
        advanceTimeBy(1_000)
        job.cancel()
        job.join()
        val entry = log.entries().single()
        assertEquals(Outcome.ENDED_AFTER_REQUEST, entry.outcome)
        assertEquals(1, tv.session.closes)
    }

    @Test
    fun `a key sent just before the time limit is logged as sent`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        tv.powerKeySettleMs = 1_000
        val result = controller().switchToArtMode(Trigger.DREAM, Timeouts.DREAM.copy(totalMs = 500))
        assertEquals(Outcome.CONNECT_FAILED, result.outcome)
        assertEquals(Outcome.POWER_KEY_SENT, result.fallback?.outcome)
        assertEquals(1, tv.powerKeys)
        assertEquals(listOf(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT), log.entries().map { it.outcome })
    }

    @Test
    fun `a key sent just before the screensaver ended is logged as sent`() = runTest {
        store.powerKeyFallback = true
        store.remotePaired = true
        tv.openFailures += TvException.ConnectFailed("HTTP 404", retryable = false)
        tv.powerKeySettleMs = 1_000
        val job = launch { dream() }
        runCurrent()
        assertEquals(1, tv.powerKeys)
        job.cancel()
        job.join()
        assertEquals(listOf(Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT), log.entries().map { it.outcome })
    }

    @Test
    fun `every attempt is logged, the fallback too`() = runTest {
        dream()
        store.powerKeyFallback = true
        store.remotePaired = true
        repeat(3) { tv.openFailures += TvException.ConnectFailed("refused", retryable = true) }
        dream()
        val entries = log.entries()
        assertEquals(
            listOf(Outcome.ART_CONFIRMED, Outcome.CONNECT_FAILED, Outcome.POWER_KEY_SENT),
            entries.map { it.outcome },
        )
        assertEquals(Trigger.DREAM, entries.first().trigger)
        assertEquals(now, entries.first().time)
    }

    @Test
    fun `an unexpected error is logged, not thrown`() = runTest {
        tv.openFailures += IllegalStateException("bug")
        val result = dream()
        assertEquals(Outcome.UNEXPECTED_ERROR, result.outcome)
        assertEquals(Outcome.UNEXPECTED_ERROR, log.entries().single().outcome)
        assertEquals(1, tv.opens)
    }

    // --- check and pairing ----------------------------------------------------------------

    @Test
    fun `check reads but never requests Art Mode`() = runTest {
        tv.session = FakeSession(ArtMode.TV)
        val report = controller().check(Timeouts.INTERACTIVE)
        assertEquals(Outcome.CHECK_OK, report.result.outcome)
        assertEquals(ArtMode.TV, report.artMode)
        assertEquals("4.3.4.0", report.apiVersion)
        assertNotNull(report.info)
        assertEquals(0, tv.session.sets)
    }

    @Test
    fun `check keeps the Art Mode status when the version cannot be read`() = runTest {
        for (error in listOf(TvException.TvError("bad request"), TvException.ConnectionLost("api_version"))) {
            tv.session = FakeSession(ArtMode.TV).apply { apiVersionError = error }
            val report = controller().check(Timeouts.INTERACTIVE)
            assertEquals(Outcome.CHECK_OK, report.result.outcome)
            assertEquals(ArtMode.TV, report.artMode)
            assertNull(report.apiVersion)
        }
    }

    @Test
    fun `check of a TV in standby opens no channel`() = runTest {
        tv.info = frameInfo(power = "standby")
        val report = controller().check(Timeouts.INTERACTIVE)
        assertEquals(Outcome.TV_OFF, report.result.outcome)
        assertEquals(listOf("info"), tv.calls)
    }

    @Test
    fun `pairing marks the remote as allowed`() = runTest {
        tv.issueToken = "87654321"
        assertEquals(Outcome.REMOTE_PAIRED, controller().pairRemote(Timeouts.INTERACTIVE).outcome)
        assertTrue(store.remotePaired)
        assertEquals("87654321", store.token)

        tv.pairError = TvException.AccessDenied()
        assertEquals(Outcome.ACCESS_DENIED, controller().pairRemote(Timeouts.INTERACTIVE).outcome)
        assertEquals(false, store.remotePaired)
        assertNull(store.token)
    }
}
