package com.dk.zopf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.WorkflowRun
import com.dk.zopf.ui.AppState
import com.dk.zopf.ui.Screen

@Composable
fun ApplicationScope.ZopfTray(
    app: AppState,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    if (!isTraySupported) return

    val runs = app.runs

    val active = runs.runs.filter { it.isActive }

    fun show(run: WorkflowRun? = null) {
        run?.let {
            runs.select(it)
            app.screen = Screen.RUNS
            app.showRunPanel = true
        }
        onShowWindow()
    }

    Tray(
        icon = rememberMenuBarIcon(active.size, runs.waitingCount),
        tooltip = tooltip(active),
        onAction = { show() },
    ) {
        Item("Show zopf", onClick = { show() })
        Separator()

        val pending = runs.awaitingPermission
        if (pending.isNotEmpty()) {
            pending.forEach { (run, node) ->
                val request = node.pendingPermission?.request
                Menu("${run.workflowName} · ${request?.toolName ?: "needs you"}") {
                    request?.summary?.takeIf { it.isNotBlank() }?.let {
                        Item(it.take(60), enabled = false, onClick = {})
                        Separator()
                    }
                    Item("Allow", onClick = { runs.decide(node, allow = true) })
                    Item("Allow for this run", onClick = { runs.decide(node, allow = true, forRestOfRun = true) })
                    Item("Deny", onClick = { runs.decide(node, allow = false) })
                    Separator()
                    Item("Show", onClick = { show(run) })
                }
            }
            Separator()
        }

        val gates = runs.awaitingApproval
        if (gates.isNotEmpty()) {
            gates.forEach { (run, node) ->
                Menu("${run.workflowName} · ${node.nodeTitle}") {
                    Item("Approve", onClick = { runs.approve(node, true) })
                    Item("Reject", onClick = { runs.approve(node, false) })
                    Separator()
                    Item("Show", onClick = { show(run) })
                }
            }
            Separator()
        }

        val questions = runs.awaitingInput
        if (questions.isNotEmpty()) {
            questions.forEach { (run, node) ->
                val question = node.pendingQuestion
                Menu("${run.workflowName} · ${question?.question?.take(40) ?: "needs an answer"}") {
                    if (question != null && question.choices.isNotEmpty()) {
                        question.choices.forEach { choice ->
                            Item(choice, onClick = { runs.answer(node, choice) })
                        }
                    } else {
                        Item("Type an answer in zopf…", onClick = { show(run) })
                    }
                    Separator()
                    Item("Cancel run", onClick = { runs.answer(node, null) })
                    Item("Show", onClick = { show(run) })
                }
            }
            Separator()
        }

        if (active.isEmpty()) {
            Item("Nothing running", enabled = false, onClick = {})
        } else {
            active.forEach { run ->
                Menu("${run.workflowName} · ${run.summary()}") {
                    Item("Show", onClick = { show(run) })
                    Item("Stop", onClick = { runs.stop(run) })

                    run.nodes.firstOrNull { it.canTakeOver && it.status.isActive }?.let { node ->
                        Item("Take over in ${runs.terminalApp}", onClick = { runs.takeOver(node) })
                    }
                }
            }
            Item("Stop everything", onClick = { runs.stopAll() })
        }

        Separator()
        Menu("Run", enabled = app.listing.workflows.isNotEmpty()) {
            app.listing.workflows.forEach { workflow ->
                Item(workflow.name, onClick = { app.runWorkflowNamed(workflow.name) })
            }
        }

        Separator()
        Item("Quit zopf", onClick = onQuit)
    }
}

private fun tooltip(active: List<WorkflowRun>): String =
    when {
        active.isEmpty() -> {
            "zopf · nothing running"
        }

        active.size == 1 -> {
            "zopf · ${active.first().workflowName}: ${active.first().summary()}"
        }

        else -> {
            "zopf · ${active.size} runs, " +
                "${active.count { it.status == RunStatus.WAITING }} waiting for you"
        }
    }

@Composable
private fun rememberMenuBarIcon(
    active: Int,
    waiting: Int,
): Painter = remember(active, waiting) { MenuBarIcon(active, waiting) }

private val trayText: TextMeasurer by lazy {
    TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
}

private class MenuBarIcon(
    private val active: Int,
    private val waiting: Int,
) : Painter() {
    override val intrinsicSize: Size get() = Size.Unspecified

    override fun DrawScope.onDraw() {
        val glyphHeight = size.height * 0.86f
        val factor = glyphHeight / GLYPH_HEIGHT
        val glyphWidth = GLYPH_WIDTH * factor

        val label =
            when {
                active <= 0 -> null
                active > 9 -> "9+"
                else -> active.toString()
            }
        val measured =
            label?.let {
                trayText.measure(
                    text = AnnotatedString(it),
                    style =
                        TextStyle(
                            color = Color.Black,
                            fontSize = (size.height * 0.66f).toSp(),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    density = this,
                )
            }

        val gap = if (measured == null) 0f else size.width * 0.10f
        val total = glyphWidth + gap + (measured?.size?.width?.toFloat() ?: 0f)
        val left = (size.width - total) / 2f

        translate(left = left - GLYPH_LEFT * factor, top = (size.height - glyphHeight) / 2f - GLYPH_TOP * factor) {
            scale(factor, factor, pivot = Offset.Zero) {
                drawPath(
                    path = braid,
                    color = Color.Black,
                    style = Stroke(width = STROKE),
                )
            }
        }

        measured?.let {
            drawText(
                it,
                topLeft =
                    Offset(
                        x = left + glyphWidth + gap,
                        y = (size.height - it.size.height) / 2f,
                    ),
            )
        }

        if (waiting > 0) {
            drawCircle(
                color = Color.Black,
                radius = size.height * 0.09f,
                center = Offset(size.width - size.height * 0.09f, size.height * 0.09f),
            )
        }
    }

    private companion object {
        const val GLYPH_LEFT = 6.5f
        const val GLYPH_TOP = 1.5f
        const val GLYPH_WIDTH = 11f
        const val GLYPH_HEIGHT = 21f
        const val STROKE = 2.4f

        val braid: Path =
            PathParser()
                .parsePathString(
                    "M8 3C8 5.5 16 5.5 16 8C16 8.85 15.07 9.41 13.85 9.88 " +
                        "M10.15 11.12C8.93 11.59 8 12.15 8 13C8 15.5 16 15.5 16 18C16 19.5 13.3 20 12 21" +
                        "C10.7 20 8 19.5 8 18 " +
                        "M16 3C16 3.85 15.07 4.41 13.85 4.88 " +
                        "M10.15 6.12C8.93 6.59 8 7.15 8 8C8 10.5 16 10.5 16 13C16 13.85 15.07 14.41 13.85 14.88 " +
                        "M10.15 16.12C8.93 16.59 8 17.15 8 18",
                ).toPath()
    }
}
