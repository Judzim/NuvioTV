package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.core.tracking.RewatchRunPosition
import com.nuvio.tv.domain.model.Video

/**
 * The first episode of the series that comes after the given run position.
 *
 * A rewatch run on the detail screen works from the episode the run sits at rather than from the
 * canonical watch position, and the offer is the next step of the run: a run that reached S01E02
 * offers S01E03. Airedness is not decided here, because the caller works from a catalogue that has
 * already dropped what it cannot play; when the run sits on the last episode this returns null and
 * the canonical position decides, exactly as it does without a run.
 */
internal fun nextEpisodeAfterRun(episodes: List<Video>, run: RewatchRunPosition): Video? =
    episodes
        .filter { video -> video.season != null && video.episode != null }
        .sortedWith(compareBy<Video>({ video -> video.season ?: 0 }, { video -> video.episode ?: 0 }))
        .firstOrNull { video ->
            val season = video.season ?: return@firstOrNull false
            val episode = video.episode ?: return@firstOrNull false
            season > run.seasonNumber || (season == run.seasonNumber && episode > run.episodeNumber)
        }
