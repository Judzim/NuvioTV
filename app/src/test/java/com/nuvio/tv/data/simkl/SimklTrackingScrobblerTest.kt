package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the rewatch rules reach the account: what the scrobbler tells Simkl, what it commits locally
 * and when it asks the user.
 *
 * The decision has to be made here, and not in the player or in the coordinator, because one scrobble
 * event is broadcast to every provider and only Simkl has rewatch sessions.
 */
class SimklTrackingScrobblerTest {

    private val authRepository = mockk<SimklAuthRepository>(relaxed = true)
    private val syncRepository = mockk<SimklSyncRepository>(relaxed = true)
    private val mutationService = mockk<SimklMutationService>(relaxed = true)
    private val promptRepository = mockk<SimklRewatchPromptRepository>(relaxed = true)

    private val scrobbler = SimklTrackingScrobbler(
        authRepository = authRepository,
        syncRepository = syncRepository,
        mutationService = mutationService,
        rewatchPromptRepository = promptRepository
    )

    @Test
    fun `a finished stop is sent as a stop and commits the playback`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.STOP,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a playback stopped under the threshold reaches Simkl as a pause`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 45.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 45.0))

        // A stop sent from here would have Simkl apply its own 80 percent rule and mark the title
        // watched anyway, so the pause is what the account is told about.
        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.PAUSE,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        // The same action decides the local commit, so the playback is stored as the pause it was
        // sent as and not as a finished one.
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a start never carries the rewatch flag and never commits`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.START, progress = 0.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.START, event(progressPercent = 0.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.START,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 0) { syncRepository.commitScrobble(any()) }
    }

    @Test
    fun `a pause is sent without the rewatch flag`() = runBlocking {
        connect()
        val result = scrobbleResult(outcome = SimklScrobbleOutcome.PAUSE, progress = 45.0)
        accountAnswers(result)

        scrobbler.scrobble(TrackingScrobbleAction.PAUSE, event(progressPercent = 45.0))

        coVerify {
            mutationService.scrobble(
                action = TrackingScrobbleAction.PAUSE,
                event = any(),
                recordRewatch = false,
                completionThresholdPercent = 80.0
            )
        }
        coVerify(exactly = 1) { syncRepository.commitScrobble(result) }
    }

    @Test
    fun `a disconnected account is told nothing`() = runBlocking {
        every { authRepository.state } returns MutableStateFlow(SimklAuthState(isAuthenticated = false))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        coVerify(exactly = 0) { mutationService.scrobble(any(), any(), any(), any()) }
        coVerify(exactly = 0) { syncRepository.commitScrobble(any()) }
    }

    @Test
    fun `the question stays down while the rewatch mode is the default one`() = runBlocking {
        connect()
        accountAnswers(scrobbleResult(outcome = SimklScrobbleOutcome.SCROBBLE, progress = 95.0))

        scrobbler.scrobble(TrackingScrobbleAction.STOP, event(progressPercent = 95.0))

        // Rozdiel oproti mobile: režim rewatchu sa číta z `TraktSettingsDataStore`, ktorý tie kľúče
        // dostane až v kroku 3.10. Do vtedy je režim predvolený (OFF), takže otázka nemá čo spýtať
        // a nikto nič nezapisuje bez potvrdenia.
        verify(exactly = 0) { promptRepository.request(any()) }
    }

    @Test
    fun `a stop under the threshold is reported as a pause`() {
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            simklReportingAction(
                action = TrackingScrobbleAction.STOP,
                progressPercent = 45.0,
                completionThresholdPercent = 80.0
            )
        )
        // The threshold itself is a finished playback: it is the number the user said the content
        // ends at.
        assertEquals(
            TrackingScrobbleAction.STOP,
            simklReportingAction(
                action = TrackingScrobbleAction.STOP,
                progressPercent = 80.0,
                completionThresholdPercent = 80.0
            )
        )
        assertEquals(
            TrackingScrobbleAction.PAUSE,
            simklReportingAction(
                action = TrackingScrobbleAction.PAUSE,
                progressPercent = 45.0,
                completionThresholdPercent = 80.0
            )
        )
        assertEquals(
            TrackingScrobbleAction.START,
            simklReportingAction(
                action = TrackingScrobbleAction.START,
                progressPercent = 0.0,
                completionThresholdPercent = 80.0
            )
        )
    }

    private fun connect(accountType: String? = "vip") {
        every { authRepository.state } returns MutableStateFlow(
            SimklAuthState(isAuthenticated = true, accountType = accountType)
        )
        every { syncRepository.state } returns MutableStateFlow(SimklSyncState())
    }

    private fun accountAnswers(result: SimklScrobbleResult) {
        coEvery {
            mutationService.scrobble(any(), any(), any(), any())
        } returns result
    }

    private fun event(progressPercent: Double) = TrackingScrobbleEvent(
        media = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            title = "Dark",
            year = 2017,
            ids = TrackingExternalIds(imdb = "tt5753856"),
            episode = TrackingEpisode(season = 2, number = 7)
        ),
        progressPercent = progressPercent
    )

    private fun scrobbleResult(
        outcome: SimklScrobbleOutcome,
        progress: Double,
        watchedAt: String? = null,
        rewatchStatus: SimklRewatchStatus? = null,
        rewatchId: Long? = null
    ) = SimklScrobbleResult(
        outcome = outcome,
        playbackId = 42L,
        progress = progress,
        mediaType = SimklMediaType.SHOWS,
        media = SimklMedia(title = "Dark", year = 2017),
        episode = SimklPlaybackEpisode(season = 2, number = 7),
        watchedAt = watchedAt,
        rewatchId = rewatchId,
        rewatchStatus = rewatchStatus
    )
}
