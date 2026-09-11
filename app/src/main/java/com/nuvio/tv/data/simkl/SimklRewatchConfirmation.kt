package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.TraktSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** A finished watch the user can still turn into a Simkl rewatch session. */
data class SimklRewatchPrompt(
    val media: TrackingMediaReference,
    val watchedAt: String?
) {
    val stableKey: String
        get() = media.stableKey
}

/** What Simkl answered to the confirmation write. */
internal data class SimklRewatchWriteOutcome(
    val rewatchId: Long?,
    val status: SimklRewatchStatus?,
    val addedCount: Int
)

/**
 * Holds the rewatch confirmation offered after a watch was recorded and turns a confirmation into the
 * extra `/sync/history` write Simkl needs for a rewatch.
 *
 * The flag cannot ride the scrobble here: the scrobble is what recorded the watch, and it leaves before
 * the user answers. Only a confirmation writes, and a declined or ignored prompt leaves the recorded
 * watch untouched.
 */
@Singleton
class SimklRewatchConfirmationCoordinator @Inject constructor(
    private val mutationService: SimklMutationService,
    private val settingsDataStore: TraktSettingsDataStore
) {
    private val _pending = MutableStateFlow<SimklRewatchPrompt?>(null)
    val pending: StateFlow<SimklRewatchPrompt?> = _pending.asStateFlow()

    /**
     * Simkl forks a session when a later write omits the id it handed out, so the first answer is kept
     * for the item it belongs to. The map lives with the coordinator because a confirmation is only ever
     * offered for a watch of this session.
     */
    private val pinnedSessions = mutableMapOf<String, Long>()

    fun offer(prompt: SimklRewatchPrompt) {
        _pending.value = prompt
    }

    fun dismiss() {
        _pending.value = null
    }

    /** Records the rewatch the user confirmed and clears the prompt, whatever the write answers. */
    internal suspend fun confirm(): SimklRewatchWriteOutcome? {
        val prompt = _pending.value ?: return null
        _pending.value = null
        // Turning the mode off while the prompt is up has to cancel the write with it.
        if (!settingsDataStore.simklRewatchMode.first().isEnabled) return null
        val pinnedSessionId = pinnedSessions[prompt.stableKey]
        return try {
            val outcome = mutationService.recordRewatch(
                media = prompt.media,
                watchedAt = prompt.watchedAt ?: System.currentTimeMillis().epochMsToUtcIso(),
                rewatchId = pinnedSessionId
            )
            outcome.rewatchId?.let { sessionId -> pinnedSessions[prompt.stableKey] = sessionId }
            Log.i(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch confirmed status=${outcome.status?.name?.lowercase() ?: "none"} " +
                    "added=${outcome.addedCount} rewatching=${outcome.rewatchId != null}"
            )
            outcome
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "simkl rewatch write failed error=" +
                    "${error.javaClass.simpleName}:${error.message}"
            )
            null
        }
    }
}

/**
 * Reads the rewatch answer out of a `/sync/history` response. The session id and status arrive inside
 * `added.statuses[].response`, and the counts tell whether anything was written at all (a free account
 * gets a silent no-op).
 */
internal fun SimklApiResponse.toSimklRewatchWriteOutcome(): SimklRewatchWriteOutcome {
    val payload = runCatching {
        REWATCH_RESPONSE_JSON.parseToJsonElement(body)
            .let { element -> REWATCH_RESPONSE_JSON.decodeFromJsonElement<SimklRewatchResponseDto>(element) }
    }.getOrNull()
    val added = payload?.added
    val statuses = added?.statuses.orEmpty()
    return SimklRewatchWriteOutcome(
        rewatchId = statuses.firstNotNullOfOrNull { status -> status.response?.rewatchId },
        status = statuses.firstNotNullOfOrNull { status ->
            SimklRewatchStatus.fromWire(status.response?.rewatchStatus)
        },
        addedCount = (added?.movies ?: 0) + (added?.shows ?: 0) + (added?.episodes ?: 0)
    )
}

private val REWATCH_RESPONSE_JSON = Json { ignoreUnknownKeys = true }

@Serializable
private data class SimklRewatchResponseDto(val added: SimklRewatchAddedDto? = null)

@Serializable
private data class SimklRewatchAddedDto(
    val movies: Int = 0,
    val shows: Int = 0,
    val episodes: Int = 0,
    val statuses: List<SimklRewatchStatusDto> = emptyList()
)

@Serializable
private data class SimklRewatchStatusDto(val response: SimklRewatchStatusResponseDto? = null)

@Serializable
private data class SimklRewatchStatusResponseDto(
    @SerialName("rewatch_id") val rewatchId: Long? = null,
    @SerialName("rewatch_status") val rewatchStatus: String? = null
)
