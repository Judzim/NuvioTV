package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The prompt is only worth showing when Simkl can open a session, and that needs a previous watch of the
 * exact movie or episode. The lookup reads the snapshot the player had before this playback.
 */
class SimklRewatchSnapshotTest {
    @Test
    fun `a watched episode reports its own watch date`() {
        val snapshot = snapshot(
            episodeEntry(
                watchedAt = listOf(3 to "2026-05-01T20:00:00Z", 4 to "2026-05-08T19:00:00Z")
            )
        )

        assertEquals(
            parseSimklUtcEpochMs("2026-05-01T20:00:00Z"),
            snapshot.lastWatchedAtEpochMs(scrobble(season = 1, number = 3))
        )
        assertEquals(
            parseSimklUtcEpochMs("2026-05-08T19:00:00Z"),
            snapshot.lastWatchedAtEpochMs(scrobble(season = 1, number = 4))
        )
    }

    @Test
    fun `an episode that was never watched reports nothing`() {
        val snapshot = snapshot(episodeEntry(watchedAt = listOf(3 to null)))

        assertNull(snapshot.lastWatchedAtEpochMs(scrobble(season = 1, number = 3)))
    }

    @Test
    fun `the tvdb coordinates match even when the numbering differs`() {
        val snapshot = snapshot(
            SimklLibraryEntry(
                mediaType = SimklMediaType.SHOWS,
                status = SimklListStatus.COMPLETED,
                show = showMedia(),
                seasons = listOf(
                    SimklSeason(
                        number = 1,
                        episodes = listOf(
                            SimklEpisode(
                                number = 3,
                                watchedAt = "2026-05-01T20:00:00Z",
                                tvdb = SimklEpisodeMapping(season = 1, episode = 3)
                            )
                        )
                    )
                )
            )
        )

        // The local coordinates are numbered differently; the tvdb pair is what lines them up.
        assertEquals(
            parseSimklUtcEpochMs("2026-05-01T20:00:00Z"),
            snapshot.lastWatchedAtEpochMs(
                scrobble(season = 5, number = 99, tvdbSeason = 1, tvdbNumber = 3)
            )
        )
        assertNull(snapshot.lastWatchedAtEpochMs(scrobble(season = 5, number = 99)))
    }

    @Test
    fun `a movie reports the canonical watch date`() {
        val snapshot = snapshot(
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                status = SimklListStatus.COMPLETED,
                movie = movieMedia(),
                lastWatchedAt = "2026-04-02T18:15:00Z"
            )
        )

        assertEquals(
            parseSimklUtcEpochMs("2026-04-02T18:15:00Z"),
            snapshot.lastWatchedAtEpochMs(movieScrobble())
        )
    }

    @Test
    fun `a plan to watch entry is not a previous watch`() {
        val snapshot = snapshot(
            SimklLibraryEntry(
                mediaType = SimklMediaType.MOVIES,
                status = SimklListStatus.PLAN_TO_WATCH,
                movie = movieMedia(),
                lastWatchedAt = "2026-04-02T18:15:00Z"
            )
        )

        assertNull(snapshot.lastWatchedAtEpochMs(movieScrobble()))
    }

    @Test
    fun `an unknown title reports nothing`() {
        val snapshot = snapshot(episodeEntry(watchedAt = listOf(3 to "2026-05-01T20:00:00Z")))

        val otherShow = scrobble(season = 1, number = 3).copy(
            media = SimklMedia(title = "Another Show", ids = mapOf("simkl" to JsonPrimitive(999)))
        )

        assertNull(snapshot.lastWatchedAtEpochMs(otherShow))
    }

    private fun snapshot(vararg entries: SimklLibraryEntry) = SimklSyncSnapshot(
        isInitialized = true,
        entries = entries.toList()
    )

    private fun episodeEntry(watchedAt: List<Pair<Int, String?>>) = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        status = SimklListStatus.COMPLETED,
        show = showMedia(),
        seasons = listOf(
            SimklSeason(
                number = 1,
                episodes = watchedAt.map { (number, watched) ->
                    SimklEpisode(number = number, watchedAt = watched)
                }
            )
        )
    )

    private fun showMedia() = SimklMedia(
        title = "The Walking Dead",
        year = 2010,
        ids = mapOf("simkl" to JsonPrimitive(2090))
    )

    private fun movieMedia() = SimklMedia(
        title = "Terminator 3: Rise of the Machines",
        year = 2003,
        ids = mapOf("simkl" to JsonPrimitive(53536))
    )

    private fun scrobble(
        season: Int,
        number: Int,
        tvdbSeason: Int? = null,
        tvdbNumber: Int? = null
    ) = SimklScrobbleResult(
        outcome = SimklScrobbleOutcome.SCROBBLE,
        playbackId = null,
        progress = 95.0,
        mediaType = SimklMediaType.SHOWS,
        media = showMedia(),
        episode = SimklPlaybackEpisode(
            season = season,
            number = number,
            tvdbSeason = tvdbSeason,
            tvdbNumber = tvdbNumber
        )
    )

    private fun movieScrobble() = SimklScrobbleResult(
        outcome = SimklScrobbleOutcome.SCROBBLE,
        playbackId = null,
        progress = 95.0,
        mediaType = SimklMediaType.MOVIES,
        media = movieMedia(),
        episode = null
    )
}
