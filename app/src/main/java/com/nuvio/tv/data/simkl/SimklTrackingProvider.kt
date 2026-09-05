package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaReference
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
import kotlinx.coroutines.CancellationException
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
    private val mutationService: SimklMutationService,
    private val settingsDataStore: TraktSettingsDataStore,
    apiClient: SimklApiClient
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.SIMKL

    // Rewatch writes reuse the same POST /sync/history body builders as manual history
    // writes, but run through a service without a commit callback: the completed scrobble
    // was already committed to the local snapshot above, so the rewatch receipt must not
    // be applied again (it only re-asserts the same history entry server side).
    private val rewatchService = SimklMutationService(client = apiClient)

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
        val result = mutationService.scrobble(
            action = action,
            event = enrichedEvent
        )
        // Evaluate against the pre-commit snapshot: a first watch is not a rewatch.
        val shouldRecordRewatch = result.outcome == SimklScrobbleOutcome.SCROBBLE &&
            isRewatchRecordingEnabled() &&
            syncRepository.state.value.snapshot.hasPriorWatch(result)
        if (action != TrackingScrobbleAction.START) {
            syncRepository.commitScrobble(result)
        }
        if (shouldRecordRewatch) {
            recordRewatchWrite(media = enrichedEvent.media, result = result)
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl adapter complete action=${action.wireValue} ${enrichedEvent.scrobbleDiagnosticSummary()}"
        )
    }

    private suspend fun isRewatchRecordingEnabled(): Boolean {
        if (!settingsDataStore.simklRewatchesEnabled.first()) return false
        var accountType = authRepository.state.value.accountType
        if (accountType == null) {
            // The plan is unknown until user settings have been fetched at least once.
            // A failed fetch skips the rewatch write silently.
            accountType = try {
                authRepository.refreshUserSettings()
                authRepository.state.value.accountType
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
        return accountType == "pro" || accountType == "vip"
    }

    private suspend fun recordRewatchWrite(
        media: TrackingMediaReference,
        result: SimklScrobbleResult
    ) {
        val watchedAtEpochMs = result.watchedAt?.let(::parseSimklUtcEpochMs)
            ?: System.currentTimeMillis()
        try {
            rewatchService.addToHistory(
                items = listOf(
                    TrackingHistoryItem(
                        media = media,
                        watchedAtEpochMs = watchedAtEpochMs
                    )
                ),
                allowRewatch = true
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch write failed error=${error.javaClass.simpleName}:${error.message}",
                error
            )
        }
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
