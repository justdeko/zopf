package com.dk.zopf.ui.runs

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dk.zopf.runtime.run.RunStatus

private const val WaveAmplitude = 0.7f
private const val WaveDriftDivisor = 4
private const val ChipTint = 0.16f
private const val NeutralChipAlpha = 0.08f

@Composable
internal fun ThinWavyBar(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val stroke = with(LocalDensity.current) { Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round) }
    val wavelength = 24.dp
    LinearWavyProgressIndicator(
        progress = { 1f },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = Color.Transparent,
        stroke = stroke,
        trackStroke = stroke,
        stopSize = 0.dp,
        amplitude = { WaveAmplitude },
        wavelength = wavelength,
        waveSpeed = wavelength / WaveDriftDivisor,
    )
}

@Composable
internal fun StatusPill(status: RunStatus) {
    val accent = status.color()
    val container by animateColorAsState(
        accent.copy(alpha = ChipTint).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh),
        MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "statusPill",
    )
    Chip(status.label, container, accent, emphasized = true)
}

@Composable
internal fun FactChip(
    text: String,
    isError: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    if (isError) {
        Chip(text, scheme.errorContainer, scheme.onErrorContainer)
    } else {
        Chip(text, scheme.onSurface.copy(alpha = NeutralChipAlpha), scheme.onSurfaceVariant)
    }
}

@Composable
private fun Chip(
    text: String,
    container: Color,
    content: Color,
    emphasized: Boolean = false,
) {
    Box(
        Modifier
            .background(container, RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (emphasized) FontWeight.SemiBold else null,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
internal fun String.withStatusTint(status: RunStatus): AnnotatedString {
    val accent = status.color()
    val label = status.label
    return buildAnnotatedString {
        if (startsWith(label)) {
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(label) }
            append(substring(label.length))
        } else {
            append(this@withStatusTint)
        }
    }
}
