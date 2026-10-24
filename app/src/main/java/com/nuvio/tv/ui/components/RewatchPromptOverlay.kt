@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.components

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.RewatchQuestion
import com.nuvio.tv.data.simkl.SimklRewatchConsentRepository
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioColorScheme
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/**
 * How long the question waits before it leaves the screen unanswered.
 *
 * Something has to close it on a TV, where nothing can be tapped outside a dialog, and a question
 * that stays open over a playback blocks what the user is watching. Walking away leaves no answer
 * behind, so the next playback of the item asks again.
 */
internal const val REWATCH_QUESTION_TIMEOUT_MS = 8_000L

/** What an answer to the question does to it. */
internal enum class RewatchPromptKeyOutcome {
    /** The question stays open, the key belongs to the buttons. */
    KEEP,

    /** The question closes without an answer, the same as walking away from it. */
    IGNORE
}

/**
 * What one key press does to the open question.
 *
 * Down is the key that closes the question unanswered. A TV has no way to tap outside a dialog, and
 * down is the key a player opens its own controls with, so it has to leave the question alone
 * without deciding anything: not answering is not a no. Key up and every other key stay with the
 * buttons, which own left, right and the click.
 */
internal fun rewatchPromptKeyOutcome(keyCode: Int, action: Int): RewatchPromptKeyOutcome {
    if (action != KeyEvent.ACTION_DOWN) return RewatchPromptKeyOutcome.KEEP
    return if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
        RewatchPromptKeyOutcome.IGNORE
    } else {
        RewatchPromptKeyOutcome.KEEP
    }
}

/** The colours one answer uses depending on whether it owns focus. */
internal data class RewatchAnswerColors(
    val container: Color,
    val content: Color
)

/**
 * Both answers stay neutral until one owns focus.
 *
 * The accent is the focus treatment, not the meaning of an answer, so it belongs only to the
 * focused card. This is what keeps the question from looking like it is about to grant a rewatch
 * while focus actually sits on the answer that writes nothing.
 */
internal fun rewatchAnswerColors(isFocused: Boolean, colors: NuvioColorScheme): RewatchAnswerColors {
    return if (isFocused) {
        RewatchAnswerColors(
            container = colors.Secondary,
            content = colors.OnSecondary
        )
    } else {
        RewatchAnswerColors(
            container = colors.BackgroundCard,
            content = colors.TextPrimary
        )
    }
}

/**
 * The rewatch question, drawn above whatever is on screen.
 *
 * This is the TV counterpart of mobile `RewatchPromptHost`. The question is asked before a playback
 * begins and only where Simkl would open a session for it, so the answer can be carried to the end
 * of the playback and decide there whether the scrobble asks the account for a rewatch. Nothing is
 * written while the question is open, and an unanswered one writes nothing at all.
 *
 * The difference from mobile is the timeout: TV cannot click outside a dialog, so the question
 * closes itself after [REWATCH_QUESTION_TIMEOUT_MS] without an interaction, silently. An answer is
 * only ever what a button press said.
 */
@Composable
fun RewatchPromptOverlay(
    repository: SimklRewatchConsentRepository,
    modifier: Modifier = Modifier,
    isLocalHost: Boolean = false
) {
    val question by repository.question.collectAsStateWithLifecycle()
    val localHostActive by repository.localHostActive.collectAsStateWithLifecycle()

    if (!isLocalHost && localHostActive) return

    question?.let { active ->
        RewatchQuestion(
            question = active,
            onGrant = repository::grant,
            onDecline = repository::decline,
            onLeave = repository::dismiss,
            modifier = modifier
        )
    }
}

/**
 * The question itself, drawn as the same compact dialog the app uses for its own confirmations.
 *
 * The question is raised before a playback, while the source screen is still visible, so it no
 * longer has to sit on the top edge of a full-screen player. `NuvioDialog` gives it the app's
 * dialog width, container, border and corner radius; the answers are the focus cards from
 * `P2pConsentDialog`, neutral until one owns focus. Focus starts on the answer that writes nothing.
 */
@Composable
private fun RewatchQuestion(
    question: RewatchQuestion,
    onGrant: () -> Unit,
    onDecline: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val grantFocusRequester = remember { FocusRequester() }
    val declineFocusRequester = remember { FocusRequester() }
    var interactionCount by remember(question) { mutableIntStateOf(0) }

    // The timer restarts on every key press: "no interaction" is measured from the last one, so the
    // question cannot close while the user is still moving towards an answer.
    LaunchedEffect(question, interactionCount) {
        delay(REWATCH_QUESTION_TIMEOUT_MS)
        onLeave()
    }

    LaunchedEffect(question) {
        runCatching { declineFocusRequester.requestFocusAfterFrames() }
    }

    NuvioDialog(
        onDismiss = onLeave,
        title = "",
        width = 520.dp
    ) {
        Text(
            text = stringResource(R.string.rewatch_prompt_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Box(
            modifier = modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == KeyEvent.ACTION_DOWN) interactionCount += 1
                    when (rewatchPromptKeyOutcome(native.keyCode, native.action)) {
                        RewatchPromptKeyOutcome.KEEP -> false
                        RewatchPromptKeyOutcome.IGNORE -> {
                            onLeave()
                            true
                        }
                    }
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
            ) {
                RewatchAnswerCard(
                    text = stringResource(R.string.rewatch_prompt_confirm),
                    onClick = onGrant,
                    focusRequester = grantFocusRequester,
                    left = grantFocusRequester,
                    right = declineFocusRequester,
                    up = grantFocusRequester,
                    down = grantFocusRequester,
                    modifier = Modifier.weight(1f)
                )

                RewatchAnswerCard(
                    text = stringResource(R.string.rewatch_prompt_dismiss),
                    onClick = onDecline,
                    focusRequester = declineFocusRequester,
                    left = grantFocusRequester,
                    right = declineFocusRequester,
                    up = declineFocusRequester,
                    down = declineFocusRequester,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * One answer, drawn as the same focusable card the consent dialog uses.
 *
 * The card is neutral at rest and takes the accent only while focused, with the same focus ring,
 * scale and colour animation as `P2pConsentDialog`, so both answers read as one control rather than
 * as a primary and a secondary button.
 */
@Composable
private fun RewatchAnswerCard(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    left: FocusRequester,
    right: FocusRequester,
    up: FocusRequester,
    down: FocusRequester,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val targetColors = rewatchAnswerColors(isFocused, NuvioTheme.colors)
    val containerColor by animateColorAsState(
        targetValue = targetColors.container,
        animationSpec = tween(durationMillis = 150),
        label = "rewatchAnswerContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = targetColors.content,
        animationSpec = tween(durationMillis = 150),
        label = "rewatchAnswerContent"
    )
    val shape = RoundedCornerShape(50)

    Card(
        onClick = onClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusProperties {
                this.left = left
                this.right = right
                this.up = up
                this.down = down
            },
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor,
            contentColor = contentColor,
            focusedContentColor = contentColor
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1.05f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}
