package com.dk.zopf.ui.editor

import androidx.compose.runtime.Stable

@Stable
class EditorCommands(
    val save: () -> Unit,
    val close: () -> Unit,
    val openSettings: () -> Unit,
    val isDirty: () -> Boolean,
    val isInspectorVisible: () -> Boolean,
    val toggleInspector: () -> Unit,
    val isNodeDragEnabled: () -> Boolean,
    val toggleNodeDrag: () -> Unit,
    val zoomIn: () -> Unit,
    val zoomOut: () -> Unit,
    val fit: () -> Unit,
    val relayout: () -> Unit,
)
