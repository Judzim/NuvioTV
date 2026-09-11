package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.simkl.SimklRewatchConfirmationCoordinator
import com.nuvio.tv.data.simkl.SimklRewatchPrompt
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * How long the prompt waits for an answer. An unattended television has to keep watching alone, and an
 * unanswered prompt must never record anything, so it closes itself.
 */
internal const val REWATCH_CONFIRMATION_TIMEOUT_MS = 6_000L

@HiltViewModel
class RewatchConfirmationViewModel @Inject constructor(
    private val coordinator: SimklRewatchConfirmationCoordinator
) : ViewModel() {
    val prompt: StateFlow<SimklRewatchPrompt?> = coordinator.pending

    fun confirm() {
        viewModelScope.launch { coordinator.confirm() }
    }

    fun dismiss() {
        coordinator.dismiss()
    }
}

/**
 * Asks whether a finished watch should be kept as a Simkl rewatch session. The watch itself is already
 * recorded, so both answers are safe: "not now" only drops the extra session.
 */
@Composable
fun RewatchConfirmationPrompt(
    viewModel: RewatchConfirmationViewModel,
    timeoutMillis: Long = REWATCH_CONFIRMATION_TIMEOUT_MS
) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    prompt?.let { pending ->
        RewatchConfirmationDialog(
            promptKey = pending.stableKey,
            mediaLabel = rewatchMediaLabel(pending),
            timeoutMillis = timeoutMillis,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RewatchConfirmationDialog(
    promptKey: String,
    mediaLabel: String,
    timeoutMillis: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(promptKey) {
        runCatching { confirmFocusRequester.requestFocus() }
        delay(timeoutMillis)
        onDismiss()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_simkl_rewatch_prompt_title),
        subtitle = stringResource(R.string.tracking_simkl_rewatch_prompt_message, mediaLabel),
        suppressFirstKeyUp = false
    ) {
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(confirmFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.tracking_simkl_rewatch_prompt_confirm))
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.tracking_simkl_rewatch_prompt_dismiss))
        }
    }
}

/** Names the watch the prompt is about, with the episode coordinates when the media has them. */
@Composable
private fun rewatchMediaLabel(prompt: SimklRewatchPrompt): String {
    val title = prompt.media.title?.trim().orEmpty()
    val episode = prompt.media.episode
    val season = episode?.season
    val episodeLabel = if (episode != null && season != null) {
        stringResource(R.string.season_episode_format, season, episode.number)
    } else {
        ""
    }
    return listOf(title, episodeLabel).filter(String::isNotBlank).joinToString(" ")
}
