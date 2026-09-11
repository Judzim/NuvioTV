package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingScrobbleAction

/**
 * How Nuvio records rewatches on Simkl.
 *
 * Simkl requires an explicit opt-in for rewatch bookkeeping, so the default is [OFF].
 * [AUTOMATIC] sends `allow_rewatch=yes` on every finished playback, [MANUAL] asks the user to confirm
 * the rewatch right after the watch itself was recorded.
 */
enum class SimklRewatchMode {
    OFF,
    MANUAL,
    AUTOMATIC;

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        val Default: SimklRewatchMode = OFF

        fun fromStorage(value: String?): SimklRewatchMode {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { mode -> mode.name.equals(normalized, ignoreCase = true) }
                ?: Default
        }
    }
}

/** Simkl marks a title watched at this progress, and rewatches can only exist where a watch does. */
internal const val SIMKL_REWATCH_MIN_PROGRESS_PERCENT = 80.0

/** Query Simkl expects on the calls that are allowed to record a rewatch session. */
internal val SIMKL_ALLOW_REWATCH_QUERY: Map<String, String> = mapOf("allow_rewatch" to "yes")

/**
 * Simkl absorbs two watches of the same movie or episode that are closer than two days apart into the
 * existing session, so a confirmation inside that window would record nothing.
 */
internal const val SIMKL_REWATCH_MIN_GAP_MS: Long = 48L * 60L * 60L * 1_000L

/**
 * Simkl Pro / VIP only. Unknown plans stay ineligible: sending the flag for a free account would
 * consume a rate-limit slot and be dropped server-side.
 */
internal fun isSimklRewatchPlanEligible(accountType: String?): Boolean {
    val normalized = accountType?.trim()?.lowercase().orEmpty()
    return normalized == "pro" || normalized == "vip"
}

/**
 * Whether the scrobble call itself should carry `allow_rewatch=yes`.
 *
 * Only [SimklRewatchMode.AUTOMATIC] answers that with yes. In [SimklRewatchMode.MANUAL] the answer
 * arrives after the watch was recorded, and it is written through `/sync/history` instead. The flag
 * is never sent on start/pause, because those endpoints mark nothing watched and Simkl documents that
 * the flag on `/scrobble/start` can open a session for a different title. Below
 * [SIMKL_REWATCH_MIN_PROGRESS_PERCENT] a stop resolves to a pause and returns no rewatch fields.
 */
internal fun shouldRecordSimklRewatchOnStop(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    progressPercent: Double
): Boolean = when {
    mode != SimklRewatchMode.AUTOMATIC -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    action != TrackingScrobbleAction.STOP -> false
    progressPercent < SIMKL_REWATCH_MIN_PROGRESS_PERCENT -> false
    else -> true
}

/**
 * Whether to ask the user to confirm a rewatch once the stop was recorded.
 *
 * Only for titles that were already watched: Simkl cannot start a session on a first watch, so asking
 * there would offer the user something the server drops. [lastWatchAtEpochMs] is the previous watch of
 * the same movie or episode and [SIMKL_REWATCH_MIN_GAP_MS] has to have passed since then.
 */
internal fun shouldOfferSimklRewatchPrompt(
    mode: SimklRewatchMode,
    accountType: String?,
    action: TrackingScrobbleAction,
    progressPercent: Double,
    lastWatchAtEpochMs: Long?,
    nowEpochMs: Long
): Boolean = when {
    mode != SimklRewatchMode.MANUAL -> false
    !isSimklRewatchPlanEligible(accountType) -> false
    action != TrackingScrobbleAction.STOP -> false
    progressPercent < SIMKL_REWATCH_MIN_PROGRESS_PERCENT -> false
    lastWatchAtEpochMs == null -> false
    nowEpochMs - lastWatchAtEpochMs < SIMKL_REWATCH_MIN_GAP_MS -> false
    else -> true
}

/** Free-tier accounts cannot record rewatches, so the picker keeps them off and offers an upgrade. */
internal fun isSimklRewatchModeSelectable(
    mode: SimklRewatchMode,
    accountType: String?
): Boolean = mode == SimklRewatchMode.OFF || isSimklRewatchPlanEligible(accountType)
