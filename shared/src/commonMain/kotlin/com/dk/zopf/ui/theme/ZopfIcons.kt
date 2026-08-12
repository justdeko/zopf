package com.dk.zopf.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Copyright Google LLC
// SPDX-License-Identifier: Apache-2.0
// https://github.com/google/material-design-icons
object ZopfIcons {
    val Add: ImageVector by lazy {
        icon("Add", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    }

    val Clear: ImageVector by lazy {
        icon(
            "Clear",
            "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 " +
                "17.59 19 19 17.59 13.41 12z",
        )
    }

    val Delete: ImageVector by lazy {
        icon(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
        )
    }

    val Edit: ImageVector by lazy {
        icon(
            "Edit",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41" +
                "l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
        )
    }

    val Refresh: ImageVector by lazy {
        icon(
            "Refresh",
            "M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 " +
                "7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 " +
                "4.22 1.78L13 11h7V4l-2.35 2.35z",
        )
    }

    val Folder: ImageVector by lazy {
        icon("Folder", "M2 6h6l2 2h10v12H2z")
    }

    val Warning: ImageVector by lazy {
        icon("Warning", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z")
    }

    val Play: ImageVector by lazy {
        icon("Play", "M8 5v14l11-7z")
    }

    val Stop: ImageVector by lazy {
        icon("Stop", "M6 6h12v12H6z")
    }

    val Terminal: ImageVector by lazy {
        icon(
            "Terminal",
            "M3 4h18v16H3zM5 6v12h14V6z M6.8 9.2 8 8l3 3-3 3-1.2-1.2L8.6 11z M12.5 12.8h5v1.6h-5z",
        )
    }

    val Send: ImageVector by lazy {
        icon("Send", "M2 21 23 12 2 3v7l15 2-15 2z")
    }

    val Back: ImageVector by lazy {
        icon("Back", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z")
    }

    val Check: ImageVector by lazy {
        icon("Check", "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z")
    }

    val Dropdown: ImageVector by lazy {
        icon("Dropdown", "M7 10l5 5 5-5z")
    }

    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3 5.25h18v1.5H3z M12 3.5h3v5h-3z " +
                "M3 11.25h18v1.5H3z M5 9.5h3v5H5z " +
                "M3 17.25h18v1.5H3z M14 15.5h3v5h-3z",
        )
    }

    val Link: ImageVector by lazy {
        icon(
            "Link",
            "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7" +
                "c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1" +
                "s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z",
        )
    }

    val FitToScreen: ImageVector by lazy {
        icon(
            "FitToScreen",
            "M3 3h7v2H5v5H3z M14 3h7v7h-2V5h-5z M3 14h2v5h5v2H3z M19 14h2v7h-7v-2h5z",
        )
    }

    val SidePanel: ImageVector by lazy {
        icon("SidePanel", "M3 4h18v16H3z M5 6v12h9V6z")
    }

    val SidePanelHidden: ImageVector by lazy {
        icon("SidePanelHidden", "M3 4h18v16H3z M5 6v12h14V6z")
    }

    val ZoomIn: ImageVector by lazy {
        icon("ZoomIn", "$MAGNIFIER M10 8h2v2h2v2h-2v2h-2v-2H8v-2h2z")
    }

    val ZoomOut: ImageVector by lazy {
        icon("ZoomOut", "$MAGNIFIER M8 10h6v2H8z")
    }

    val OpenWith: ImageVector by lazy {
        icon(
            "OpenWith",
            "M10 9h4V6h3l-5-5-5 5h3v3z M9 10H6V7l-5 5 5 5v-3h3v-4z M23 12l-5-5v3h-3v4h3v3z " +
                "M14 15h-4v3H7l5 5 5-5h-3z",
        )
    }

    val NodeAgent: ImageVector by lazy {
        icon("NodeAgent", "M12 2 14.2 9.8 22 12 14.2 14.2 12 22 9.8 14.2 2 12 9.8 9.8z")
    }

    val NodeShell: ImageVector by lazy {
        icon("NodeShell", "M4.4 5.6 3 7l5 5-5 5 1.4 1.4L10.8 12z M12.5 16.5h8.5v2h-8.5z")
    }

    val NodeConnector: ImageVector by lazy {
        icon(
            "NodeConnector",
            "M8 2h2v5H8z M14 2h2v5h-2z M5.5 8h13v3.5a6.5 6.5 0 0 1-5.5 6.42V22h-2v-4.08" +
                "A6.5 6.5 0 0 1 5.5 11.5z",
        )
    }

    val NodeGate: ImageVector by lazy {
        icon("NodeGate", "M6 4h4v16H6z M14 4h4v16h-4z")
    }

    val NodeBranch: ImageVector by lazy {
        icon("NodeBranch", "M12 2 22 12 12 22 2 12z")
    }

    val NodeInput: ImageVector by lazy {
        icon("NodeInput", "M4 18h16v2H4z M11 4h2v12h-2z M8 4h8v2H8z M8 14h8v2H8z")
    }

    val JumpToTop: ImageVector by lazy {
        icon("JumpToTop", "M4 3h16v2H4z M11 11H8l4-4 4 4h-3v10h-2z")
    }

    val JumpToBottom: ImageVector by lazy {
        icon("JumpToBottom", "M4 19h16v2H4z M13 13h3l-4 4-4-4h3V3h2z")
    }

    val List: ImageVector by lazy {
        icon(
            "List",
            "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z",
        )
    }

    val Workflow: ImageVector by lazy {
        icon(
            "Workflow",
            "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7" +
                "l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7" +
                "L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81" +
                "l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92" +
                "-1.31-2.92-2.92-2.92z",
        )
    }

    private const val MAGNIFIER =
        "M11 4a7 7 0 1 1 0 14 7 7 0 1 1 0-14z M11 6a5 5 0 1 0 0 10 5 5 0 1 0 0-10z " +
            "M16.5 15.1 15.1 16.5 20 21.4 21.4 20z"

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            }.build()
}

@Composable
fun PathIcon(
    image: ImageVector,
    contentDescription: String?,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember(image) { image.asPath() }
    val description = contentDescription
    val semantics =
        if (description == null) {
            Modifier
        } else {
            Modifier.semantics {
                this.contentDescription = description
                role = Role.Image
            }
        }
    Canvas(modifier.size(size).then(semantics)) {
        scale(
            scaleX = this.size.width / image.viewportWidth,
            scaleY = this.size.height / image.viewportHeight,
            pivot = Offset.Zero,
        ) {
            drawPath(path, tint)
        }
    }
}

private fun ImageVector.asPath(): Path =
    PathParser()
        .apply {
            root.filterIsInstance<VectorPath>().forEach { addPathNodes(it.pathData) }
        }.toPath()
