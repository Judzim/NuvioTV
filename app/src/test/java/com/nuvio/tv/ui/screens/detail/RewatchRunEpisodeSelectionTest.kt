package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which episode the detail screen offers while a rewatch run is in progress: the step after the run
 * position, and nothing at all once the run has reached the end of the catalogue.
 */
class RewatchRunEpisodeSelectionTest {

    @Test
    fun `the episode after the run is offered`() {
        val episodes = listOf(
            episode(1, 1),
            episode(1, 2),
            episode(1, 3),
            episode(1, 4),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("1:3", next?.id)
    }

    @Test
    fun `the run follows across a season boundary`() {
        val episodes = listOf(
            episode(1, 1),
            episode(1, 2),
            episode(2, 1),
            episode(2, 2),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("2:1", next?.id)
    }

    @Test
    fun `a run on the last episode offers nothing`() {
        val episodes = listOf(episode(2, 1), episode(2, 2))

        assertNull(nextEpisodeAfterRun(episodes, run(season = 2, episode = 2)))
    }

    @Test
    fun `the order of the list does not decide the offer`() {
        val episodes = listOf(
            episode(2, 1),
            episode(1, 4),
            episode(1, 2),
        )

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 2))

        assertEquals("1:4", next?.id)
    }

    @Test
    fun `a run position missing from the catalogue still offers the first episode after it`() {
        val episodes = listOf(episode(3, 1), episode(3, 2))

        val next = nextEpisodeAfterRun(episodes, run(season = 1, episode = 5))

        assertEquals("3:1", next?.id)
    }

    @Test
    fun `a list without episodes offers nothing`() {
        assertNull(nextEpisodeAfterRun(emptyList(), run(season = 1, episode = 1)))
    }

    private fun episode(season: Int, episode: Int) = Video(
        id = "$season:$episode",
        title = "Episode $episode",
        released = null,
        thumbnail = null,
        season = season,
        episode = episode,
        overview = null,
    )

    private fun run(season: Int, episode: Int) = RewatchRunPosition(
        contentId = "tt5753856",
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = 1_000L,
    )
}
