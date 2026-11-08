package com.mmtube.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mmtube.app.R
import com.mmtube.app.ui.components.AuroraBackground
import com.mmtube.app.ui.components.GradientButton
import com.mmtube.app.ui.components.MmtubeLogo
import com.mmtube.app.ui.theme.MmtubePalette
import com.mmtube.app.ui.theme.MmtubeType

@Composable
fun TermsScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val context = LocalContext.current
    AuroraBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Spacer(Modifier.weight(0.06f, fill = true))
            MmtubeLogo(size = 76.dp)
            Text(
                text = context.getString(R.string.terms_title),
                style = MmtubeType.HeadlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "MMTube",
                style = MmtubeType.LabelSmall,
                color = MmtubePalette.Aqua
            )
            Text(
                text = context.getString(R.string.terms_body),
                style = MmtubeType.BodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f, fill = true)
            )
            GradientButton(
                text = context.getString(R.string.terms_accept),
                onClick = onAccept,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = onDecline,
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(context.getString(R.string.terms_decline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(0.04f, fill = true))
        }
    }
}
