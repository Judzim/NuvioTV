package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class SimklMutationService internal constructor(
    private val client: SimklApiClient,
    private val onMutationCommitted: suspend (SimklMutationReceipt) -> Unit = {}
) {
    @Inject
    constructor(client: SimklApiClient, syncRepository: SimklSyncRepository) : this(
        client,
        { receipt ->
            syncRepository.commitMutation(receipt)
            if (receipt.requiresReconciliation) {
                syncRepository.refreshAsync(TrackingRefreshIntent.INVALIDATED)
            }
        }
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    suspend fun moveToList(
        items: Collection<TrackingMediaReference>,
        destination: TrackingListStatus
    ): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/add-to-list",
                body = buildSimklListMutationBody(candidates, destination, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toListMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromList(items: Collection<TrackingMediaReference>): TrackingMutationResult =
        removeFromHistory(items)

    suspend fun addToHistory(items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val candidates = items.toList().also { historyItems ->
            require(historyItems.all { item -> item.media.hasResolvableIdentity }) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                body = buildSimklHistoryMutationBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryMutationReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    suspend fun removeFromHistory(items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val candidates = items.validated()
        if (candidates.isEmpty()) return TrackingMutationResult(attemptedCount = 0)
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history/remove",
                body = buildSimklHistoryRemovalBody(candidates, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        val receipt = response.toHistoryRemovalReceipt(candidates, json)
        onMutationCommitted(receipt)
        return receipt.result
    }

    internal suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
        recordRewatch: Boolean = false
    ): SimklScrobbleResult {
        require(event.media.hasResolvableIdentity) { "Simkl scrobble requires a media ID or title" }
        require(event.media.kind == TrackingMediaKind.MOVIE || event.media.episode != null) {
            "Simkl series scrobble requires an episode"
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation request action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
        val response = try {
            client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/scrobble/${action.wireValue}",
                    query = if (recordRewatch) SIMKL_ALLOW_REWATCH_QUERY else emptyMap(),
                    body = buildSimklScrobbleBody(event, json),
                    retryPolicy = SimklRetryPolicy.NEVER,
                    scrobbleStopConflictIsSuccess = action == TrackingScrobbleAction.STOP
                )
            )
        } catch (error: Throwable) {
            Log.e(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl mutation failed action=${action.wireValue} " +
                    "error=${error.javaClass.simpleName}:${error.message} ${event.scrobbleDiagnosticSummary()}",
                error
            )
            throw error
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl mutation response action=${action.wireValue} status=${response.status} " +
                "softSuccess=${response.isSoftSuccess} ${event.scrobbleDiagnosticSummary()}"
        )
        return response.toSimklScrobbleResult(action, event, json)
    }

    /**
     * Records a rewatch the user confirmed after the watch itself was already scrobbled.
     *
     * `/sync/history` is the only path left at that point, and it must not be committed as a mutation
     * receipt: the canonical entry was written by the scrobble, and rewatch progress never feeds it.
     */
    internal suspend fun recordRewatch(
        media: TrackingMediaReference,
        watchedAt: String?,
        rewatchId: Long? = null
    ): SimklRewatchWriteOutcome {
        require(media.hasResolvableIdentity) { "Simkl rewatch requires a media ID or title" }
        require(media.kind == TrackingMediaKind.MOVIE || media.episode != null) {
            "Simkl series rewatch requires an episode"
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "simkl rewatch request kind=${media.kind.name.lowercase()} " +
                "title=${media.title ?: "unknown"} pinned=${rewatchId != null}"
        )
        val response = client.execute(
            SimklApiRequest(
                method = SimklHttpMethod.POST,
                path = "/sync/history",
                query = SIMKL_ALLOW_REWATCH_QUERY,
                body = buildSimklRewatchMutationBody(media, watchedAt, rewatchId, json),
                retryPolicy = SimklRetryPolicy.SYNC_WRITE
            )
        )
        return response.toSimklRewatchWriteOutcome()
    }

    private fun Collection<TrackingMediaReference>.validated(): List<TrackingMediaReference> =
        toList().also { candidates ->
            require(candidates.all(TrackingMediaReference::hasResolvableIdentity)) {
                "Simkl mutation requires a media ID or title for every item"
            }
        }
}
