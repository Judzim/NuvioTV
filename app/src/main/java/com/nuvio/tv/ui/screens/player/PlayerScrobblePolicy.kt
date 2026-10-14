package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TRACKING_COMPLETION_BAR_PERCENT
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/**
 * Whether a playback that began above the completion bar still needs its start sent.
 *
 * Below the bar the start is the session the rest of the playback is reported through, and that path
 * owns it. Above the bar the start is only a "watching now" report, sent once per item: the answer is
 * never yes twice, which is what keeps a pause, and the resume after it, from sending another one.
 */
internal fun shouldSendStartAboveCompletionBar(
    hasSentAboveCompletionBar: Boolean,
    progressPercent: Float
): Boolean =
    progressPercent.toDouble() >= TRACKING_COMPLETION_BAR_PERCENT && !hasSentAboveCompletionBar
