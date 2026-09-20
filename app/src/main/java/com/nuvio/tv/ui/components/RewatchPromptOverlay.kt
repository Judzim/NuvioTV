@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.RewatchNotice
import com.nuvio.tv.data.simkl.RewatchNoticeKind
import com.nuvio.tv.data.simkl.RewatchPrompt
import com.nuvio.tv.data.simkl.SimklRewatchPromptRepository
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** How long the question waits for an answer before it counts as Ignore. */
internal const val REWATCH_PROMPT_TIMEOUT_MS = 8_000L

/** How long the feedback of an answer stays on screen, the same time mobile keeps it. */
internal const val REWATCH_NOTICE_TIMEOUT_MS = 2_600L

/** What an answer to the question does to it. */
internal enum class RewatchPromptKeyOutcome {
    /** The question stays open, the key belongs to the buttons. */
    KEEP,

    /** The question closes without recording, which is the Ignore answer. */
    IGNORE
}

/**
 * What one key press does to the open question.
 *
 * Down is the key that closes the question. A TV has no way to tap outside a dialog, so a shared
 * answer is needed for the case where the user does not want to record the rewatch but also does not
 * want to answer the question: it is the same Ignore the button gives, applied without a second
 * click. Key up and every other key stay with the buttons, which own left, right and the click.
 */
internal fun rewatchPromptKeyOutcome(keyCode: Int, action: Int): RewatchPromptKeyOutcome {
    if (action != KeyEvent.ACTION_DOWN) return RewatchPromptKeyOutcome.KEEP
    return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        RewatchPromptKeyOutcome.IGNORE
    } else {
        RewatchPromptKeyOutcome.KEEP
    }
}

/**
 * The rewatch question and the feedback of its last answer, drawn above whatever is on screen.
 *
 * Toto je TV náprotivok mobile `RewatchPromptHost`: otázka sa pýta na prehratie, ktoré Simkl prijal
 * ako opakované, a nič sa nezapíše, kým ju používateľ nepotvrdí. Odpoveď prežíva zatvorenie
 * overlaya, lebo zápis beží na scope repozitára, nie na tom, ktorý otázku položil.
 *
 * Rozdiel oproti mobile je v čase: TV nemá ako kliknúť mimo dialógu, takže otázka sa nedá nechať
 * len tak zmiznúť a používateľ musí vidieť, že vôbec bola. Po ôsmich sekundách bez akejkoľvek
 * interakcie sa preto zavrie ako odpoveď `Ignorovať`, teda s viditeľným "nezapísané", a nie ticho.
 */
@Composable
fun RewatchPromptOverlay(
    repository: SimklRewatchPromptRepository,
    modifier: Modifier = Modifier
) {
    val prompt by repository.prompt.collectAsStateWithLifecycle()
    val notice by repository.notice.collectAsStateWithLifecycle()

    prompt?.let { active ->
        RewatchQuestion(
            prompt = active,
            onConfirm = repository::confirm,
            onIgnore = repository::decline,
            modifier = modifier
        )
    }
    notice?.let { active ->
        RewatchNoticePill(
            notice = active,
            onDismissed = repository::dismissNotice,
            modifier = modifier
        )
    }
}

/**
 * The question itself. Focus starts on Record and the right key moves to Ignore, so the answer that
 * writes to the account is never the accidental one.
 */
@Composable
private fun RewatchQuestion(
    prompt: RewatchPrompt,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val confirmFocusRequester = remember { FocusRequester() }
    var interactionCount by remember(prompt) { mutableIntStateOf(0) }

    // The timer restarts on every key press: "no interaction" is measured from the last one, so the
    // question cannot close while the user is still moving towards an answer.
    LaunchedEffect(prompt, interactionCount) {
        delay(REWATCH_PROMPT_TIMEOUT_MS)
        onIgnore()
    }

    LaunchedEffect(prompt) {
        runCatching { confirmFocusRequester.requestFocus() }
    }

    NuvioDialog(
        onDismiss = onIgnore,
        title = stringResource(R.string.rewatch_prompt_title),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == KeyEvent.ACTION_DOWN) interactionCount += 1
                    when (rewatchPromptKeyOutcome(native.keyCode, native.action)) {
                        RewatchPromptKeyOutcome.KEEP -> false
                        RewatchPromptKeyOutcome.IGNORE -> {
                            onIgnore()
                            true
                        }
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.rewatch_prompt_confirm))
            }
            Button(
                onClick = onIgnore,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.rewatch_prompt_dismiss))
            }
        }
    }
}

/** The pill that says what the last answer did, so an answer is never silent. */
@Composable
private fun RewatchNoticePill(
    notice: RewatchNotice,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(notice) {
        delay(REWATCH_NOTICE_TIMEOUT_MS)
        onDismissed()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(3f),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = NuvioTheme.spacing.xxl)
                .clip(RoundedCornerShape(24.dp))
                .background(NuvioTheme.colors.BackgroundElevated)
                .border(
                    width = NuvioTheme.spacing.hairline,
                    color = NuvioTheme.colors.Border,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = NuvioTheme.spacing.xl, vertical = NuvioTheme.spacing.md)
        ) {
            Text(
                text = notice.kind.message(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextPrimary
            )
        }
    }
}

@Composable
private fun RewatchNoticeKind.message(): String = stringResource(
    when (this) {
        RewatchNoticeKind.RECORDED -> R.string.rewatch_notice_recorded
        RewatchNoticeKind.NOT_RECORDED -> R.string.rewatch_notice_declined
        RewatchNoticeKind.FAILED -> R.string.rewatch_notice_failed
    }
)
