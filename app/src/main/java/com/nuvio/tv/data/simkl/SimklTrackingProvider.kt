package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class SimklTrackingScrobbler @Inject constructor(
    private val authRepository: SimklAuthRepository,
    private val syncRepository: SimklSyncRepository,
    private val settingsDataStore: TraktSettingsDataStore,
    private val mutationService: SimklMutationService,
    private val rewatchConfirmation: SimklRewatchConfirmationCoordinator
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        syncRepository.ensureLoaded()
        val enrichedEvent = event.copy(
            media = syncRepository.state.value.snapshot
                .enrichMediaReference(event.media)
                .resolveAnimeEpisodeForSimkl()
        )
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter enriched action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
        // One flag for every connected provider, so the rewatch decision has to be made before the
        // scrobble leaves this adapter: only Simkl has rewatch sessions.
        val mode = settingsDataStore.simklRewatchMode.first()
        val accountType = authRepository.state.value.accountType
        val recordRewatch = shouldRecordSimklRewatchOnStop(
            mode = mode,
            accountType = accountType,
            action = action,
            progressPercent = enrichedEvent.progressPercent
        )
        val result = mutationService.scrobble(
            action = action,
            event = enrichedEvent,
            recordRewatch = recordRewatch
        )
        // Read the previous watch while the snapshot still misses the one just scrobbled.
        val previousWatchAtEpochMs = syncRepository.state.value.snapshot.lastWatchedAtEpochMs(result)
        if (action != TrackingScrobbleAction.START) {
            syncRepository.commitScrobble(result)
        }
        if (recordRewatch || result.rewatchStatus != null) {
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch action=${action.wireValue} status=" +
                    "${result.rewatchStatus?.name?.lowercase() ?: "none"} " +
                    "rewatching=${result.rewatchId != null}"
            )
        }
        if (
            result.outcome == SimklScrobbleOutcome.SCROBBLE &&
            shouldOfferSimklRewatchPrompt(
                mode = mode,
                accountType = accountType,
                action = action,
                progressPercent = enrichedEvent.progressPercent,
                lastWatchAtEpochMs = previousWatchAtEpochMs,
                nowEpochMs = System.currentTimeMillis()
            )
        ) {
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch prompt offered kind=${enrichedEvent.media.kind.name.lowercase()}"
            )
            rewatchConfirmation.offer(
                SimklRewatchPrompt(
                    media = enrichedEvent.media,
                    watchedAt = result.watchedAt
                )
            )
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter complete action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
    }
}

@Singleton
class SimklTrackingProvider @Inject constructor(
    authRepository: SimklAuthRepository,
    override val scrobbler: SimklTrackingScrobbler
) : TrackingProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = authRepository.state
        .map { state -> state.isAuthenticated }
        .stateIn(scope, SharingStarted.Eagerly, authRepository.state.value.isAuthenticated)
}
