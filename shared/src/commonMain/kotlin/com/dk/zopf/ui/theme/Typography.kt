package com.dk.zopf.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val ZopfTypography: Typography =
    Typography().let { base ->
        base.copy(
            headlineSmall = base.headlineSmall.desktopHeadline(22.sp),
            headlineSmallEmphasized = base.headlineSmallEmphasized.desktopHeadline(22.sp),
            headlineMedium = base.headlineMedium.desktopHeadline(26.sp),
            headlineMediumEmphasized = base.headlineMediumEmphasized.desktopHeadline(26.sp),
        )
    }

private fun TextStyle.desktopHeadline(size: androidx.compose.ui.unit.TextUnit) =
    copy(
        fontSize = size,
        lineHeight = size * 1.27f,
        letterSpacing = (-0.015).em,
    )
