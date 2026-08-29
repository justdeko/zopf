package com.dk.zopf.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dk.zopf.ui.theme.ZopfIcons
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private const val SettleMillis = 400L

@Composable
internal fun SourcePane(
    state: EditorState,
    onApplied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(state) { mutableStateOf(state.sourceText) }
    var applied by remember(state) { mutableStateOf(draft) }
    var mirrored by remember(state) { mutableStateOf(state.workflow) }
    var error by remember(state) { mutableStateOf<String?>(null) }

    LaunchedEffect(draft) {
        if (draft == applied) return@LaunchedEffect
        delay(SettleMillis.milliseconds)
        state
            .applySource(draft)
            .onSuccess {
                error = null
                mirrored = state.workflow
                onApplied()
            }.onFailure { error = it.message ?: "This isn't YAML zopf can read." }
        applied = draft
    }

    LaunchedEffect(state.workflow) {
        if (state.workflow == mirrored || draft != applied) return@LaunchedEffect
        draft = state.sourceText
        applied = draft
        mirrored = state.workflow
        error = null
    }

    Column(modifier.fillMaxSize()) {
        error?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    ZopfIcons.Warning,
                    contentDescription = "This YAML doesn't parse",
                    Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            HorizontalDivider()
        }

        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            textStyle =
                LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}
