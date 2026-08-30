package com.dk.zopf.cli

import com.dk.zopf.runtime.ConsoleEntry
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.WorkflowRun
import com.dk.zopf.runtime.format
import com.dk.zopf.runtime.spend
import java.io.PrintStream
import java.time.Duration

enum class Format { TEXT, JSON, QUIET }

interface RunRenderer {
    fun starting(run: WorkflowRun) = Unit

    fun entry(
        node: NodeRun,
        entry: ConsoleEntry,
    ) = Unit

    fun raw(
        nodeId: String,
        line: String,
    ) = Unit

    fun settled(node: NodeRun) = Unit

    fun finished(run: WorkflowRun) = Unit
}

fun renderer(
    format: Format,
    out: PrintStream,
    colour: Boolean,
): RunRenderer =
    when (format) {
        Format.TEXT -> TextRenderer(out, colour)
        Format.JSON -> JsonRenderer(out)
        Format.QUIET -> QuietRenderer(out, colour)
    }

class TextRenderer(
    private val out: PrintStream,
    private val colour: Boolean,
) : RunRenderer {
    private val lock = Any()
    private var width = 0

    override fun starting(run: WorkflowRun) {
        width = run.nodes.maxOfOrNull { it.nodeId.length } ?: 0
        say(
            null,
            "${run.workflowName} · ${run.nodes.size} ${if (run.nodes.size == 1) "node" else "nodes"}" +
                (run.workspaceRoot?.let { " · $it" } ?: ""),
        )
    }

    override fun entry(
        node: NodeRun,
        entry: ConsoleEntry,
    ) {
        when (entry) {
            is ConsoleEntry.Message -> {
                entry.text.lines().forEach {
                    say(node, if (entry.isThinking) dim(it) else it)
                }
            }

            is ConsoleEntry.ToolCall -> {
                say(node, "${entry.name} ${entry.summary}".trimEnd())
            }

            is ConsoleEntry.Output -> {
                say(node, if (entry.isError) warn(entry.text) else entry.text)
            }

            is ConsoleEntry.Notice -> {
                say(node, if (entry.isWarning) warn(entry.text) else dim(entry.text))
            }

            is ConsoleEntry.Prompt -> {
                say(node, entry.text)
            }

            is ConsoleEntry.Summary -> {
                say(node, dim(summaryOf(entry)))
            }
        }
    }

    override fun settled(node: NodeRun) {
        val detail =
            listOfNotNull(
                node.status.label.lowercase(),
                format(node.elapsed()),
                node.exitCode?.takeIf { it != 0 }?.let { "exit $it" },
                spend(node.costUsd, node.tokens),
            )
        say(node, tint(node.status, detail.joinToString(" · ")))
    }

    override fun finished(run: WorkflowRun) {
        val detail =
            listOfNotNull(
                run.status.label,
                "${run.settledCount}/${run.nodes.size}",
                format(run.elapsed()),
                spend(run.costUsd, run.tokens),
            )
        say(null, tint(run.status, "${run.workflowName} · ${detail.joinToString(" · ")}"))
        run.nodes
            .filter { it.status != RunStatus.SUCCEEDED }
            .forEach { say(null, dim("  ${it.nodeId}: ${it.status.label.lowercase()}")) }
    }

    private fun say(
        node: NodeRun?,
        text: String,
    ) {
        val prefix = node?.nodeId?.padEnd(width)?.plus(" │ ") ?: "· "
        synchronized(lock) {
            text.lines().forEach { out.println(prefix + it) }
            out.flush()
        }
    }

    private fun dim(text: String) = if (colour) "\u001B[2m$text\u001B[0m" else text

    private fun warn(text: String) = if (colour) "\u001B[33m$text\u001B[0m" else text

    private fun tint(
        status: RunStatus,
        text: String,
    ): String =
        when {
            !colour -> text
            status == RunStatus.SUCCEEDED -> "\u001B[32m$text\u001B[0m"
            status == RunStatus.FAILED -> "\u001B[31m$text\u001B[0m"
            else -> "\u001B[33m$text\u001B[0m"
        }
}

class JsonRenderer(
    private val out: PrintStream,
) : RunRenderer {
    private val lock = Any()

    override fun raw(
        nodeId: String,
        line: String,
    ) {
        synchronized(lock) {
            out.println(line)
            out.flush()
        }
    }
}

class QuietRenderer(
    out: PrintStream,
    colour: Boolean,
) : RunRenderer {
    private val text = TextRenderer(out, colour)

    override fun finished(run: WorkflowRun) = text.finished(run)
}

fun summaryOf(entry: ConsoleEntry.Summary): String =
    listOfNotNull(
        entry.text?.takeIf { it.isNotBlank() },
        entry.durationMs?.let { format(Duration.ofMillis(it)) },
        spend(entry.costUsd, entry.tokens),
    ).joinToString(" · ")
