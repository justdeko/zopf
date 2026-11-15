package com.dk.zopf.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dk.zopf.ui.theme.PathIcon

private const val SpinMillis = 24_000
private const val EnterMillis = 300
private const val EnterDelayMillis = 80
private const val ExitMillis = 150
private const val EnterScale = 0.92f
private const val BodyAlpha = 0.8f

@Composable
fun EmptyState(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = EmptyCaption(title, body, icon, isError),
            transitionSpec = {
                (fadeIn(tween(EnterMillis, delayMillis = EnterDelayMillis)) + scaleIn(initialScale = EnterScale)) togetherWith
                    (fadeOut(tween(ExitMillis)) + scaleOut(targetScale = EnterScale))
            },
            label = "emptyState",
        ) { caption -> Caption(caption) }
    }
}

private data class EmptyCaption(
    val title: String,
    val body: String,
    val icon: ImageVector,
    val isError: Boolean,
)

@Composable
private fun Caption(caption: EmptyCaption) {
    val scheme = MaterialTheme.colorScheme
    val container = if (caption.isError) scheme.errorContainer else scheme.secondaryContainer
    val content = if (caption.isError) scheme.onErrorContainer else scheme.onSecondaryContainer
    val badge = if (caption.isError) scheme.error else scheme.primary
    val onBadge = if (caption.isError) scheme.onError else scheme.onPrimary

    val turn by rememberInfiniteTransition(label = "emptySpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(SpinMillis, easing = LinearEasing)),
        label = "emptySpinAngle",
    )

    Surface(
        modifier = Modifier.widthIn(max = 440.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = container,
        contentColor = content,
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(52.dp)
                        .graphicsLayer { rotationZ = turn }
                        .background(badge, MaterialShapes.Cookie9Sided.toShape()),
                )
                PathIcon(caption.icon, contentDescription = null, size = 22.dp, tint = onBadge)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                caption.title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                caption.body,
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = BodyAlpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}
