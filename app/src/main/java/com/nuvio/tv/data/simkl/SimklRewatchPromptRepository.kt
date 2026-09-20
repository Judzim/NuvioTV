package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A finished playback the user can still choose to record as a rewatch. */
data class RewatchPrompt(
    val media: TrackingMediaReference,
    val watchedAtEpochMs: Long
)

/** What an answer to the prompt actually did, shown briefly so the tap has visible feedback. */
enum class RewatchNoticeKind {
    /** The rewatch reached Simkl. */
    RECORDED,

    /** The user answered No, or the question timed out. */
    NOT_RECORDED,

    /** The write to Simkl failed. */
    FAILED
}

data class RewatchNotice(val kind: RewatchNoticeKind)

/**
 * Holds the rewatch question Nuvio shows after a playback that Simkl accepted as a repeat viewing.
 *
 * Nothing is written until the user confirms, which is why the prompt is the only place that turns
 * a playback into a rewatch session in manual mode. The prompt is cleared on every answer and never
 * survives the session. The answer also leaves a [notice] behind, so the user sees what happened
 * instead of having to trust that a tap did something.
 *
 * The answer does not decide anything about Continue Watching: a series joins the row once the
 * account shows two episodes of the run rewatched, which is a rule that reads the same on every
 * device (see `deriveSimklRewatchRuns`).
 *
 * TV ekvivalent mobile `RewatchPromptRepository.kt`. Mobile ho zámerne drží mimo DI a mimo
 * životného cyklu (`object` s vlastným scope), aby write prežil zatvorenie popupu; TV je
 * `@Singleton`, ktorý si ten scope nesie v sebe, takže pre overlay v `PlayerScreen`, ktorý sa po
 * odpovedi odmontuje, platí to isté. Zápis odpovede ide na [SimklRewatchWriter], nie na
 * `SimklMutationService` priamo, aby sa dal otestovať bez HTTP.
 */
@Singleton
class SimklRewatchPromptRepository @Inject constructor(
    private val writer: SimklRewatchWriter,
    private val authRepository: SimklAuthRepository
) {
    /**
     * Where the write of a confirmed rewatch runs. It cannot be the scope of the overlay that asked
     * the question: clearing the prompt takes that overlay out of the composition, which cancels its
     * scope, and the write must not be cancelled halfway.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _prompt = MutableStateFlow<RewatchPrompt?>(null)
    val prompt: StateFlow<RewatchPrompt?> = _prompt.asStateFlow()

    private val _notice = MutableStateFlow<RewatchNotice?>(null)
    val notice: StateFlow<RewatchNotice?> = _notice.asStateFlow()

    fun request(prompt: RewatchPrompt) {
        _prompt.value = prompt
    }

    /** Called when the prompt is dismissed, including by its own timeout. */
    fun dismiss() {
        _prompt.value = null
    }

    /** The user answered No: nothing is written, and the app says so. */
    fun decline() {
        _prompt.value = null
        _notice.value = RewatchNotice(RewatchNoticeKind.NOT_RECORDED)
    }

    /**
     * Records the pending rewatch and closes the prompt.
     *
     * The answer is shown when the write is done, not when the button is tapped, and it survives the
     * overlay closing: that is the difference between a tap that says what happened and one that does
     * not.
     */
    fun confirm() {
        val active = _prompt.value ?: return
        _prompt.value = null
        scope.launch {
            val recorded = writer.recordConfirmedRewatch(
                media = active.media,
                watchedAtEpochMs = active.watchedAtEpochMs
            )
            _notice.value = RewatchNotice(
                if (recorded) RewatchNoticeKind.RECORDED else RewatchNoticeKind.FAILED
            )
        }
    }

    /** Hides the feedback of the last answer. */
    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Drops the question and the answer, and signs Simkl out with them.
     *
     * Toto je cesta odhlásenia, nie cesta zavretia overlaya: mobile ju volá presne pri odhlásení
     * (`SimklAuthRepository.kt:171`), lebo otázka, ktorá ostane visieť po odhlásení, by po odpovedi
     * zapisovala do účtu, ktorý už nie je pripojený. Na zavretie otázky bez odpovede je [dismiss],
     * na odpoveď `Nie` je [decline]. TV `SimklAuthRepository` o tomto repozitári nevie, takže sa
     * odhlásenie volá odtiaľto ([SimklAuthRepository.disconnect]).
     */
    fun clear() {
        _prompt.value = null
        _notice.value = null
        authRepository.disconnect()
    }
}
