@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Shown when a free Simkl account tries to turn rewatch recording on. Simkl only stores rewatch
 * sessions for Pro and VIP plans, so the picker stays on its previous value until the plan allows it.
 */
@Composable
internal fun SimklRewatchUpgradeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var browserError by rememberSaveable { mutableStateOf(false) }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_simkl_rewatch_pro_title),
        subtitle = stringResource(R.string.tracking_simkl_rewatch_pro_description),
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        if (browserError) {
            Text(
                text = stringResource(R.string.error_open_browser_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.Error,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.tracking_simkl_rewatch_pro_not_now),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.tracking_simkl_rewatch_pro_upgrade),
                onClick = { browserError = !openSimklVipPage(context) },
                primary = true
            )
        }
    }
}

/** The TV client has no `LocalUriHandler`, so the VIP page opens through a plain VIEW intent. */
private fun openSimklVipPage(context: Context): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SIMKL_VIP_URL)))
}.isSuccess

private const val SIMKL_VIP_URL = "https://simkl.com/vip/"
