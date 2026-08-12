package com.dk.zopf.cli

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.NodeExecution
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.ShellLine
import com.dk.zopf.store.WorkflowStore
import com.dk.zopf.store.Workspace
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

class Streams {
    private val outBytes = ByteArrayOutputStream()
    private val errBytes = ByteArrayOutputStream()

    val out = PrintStream(outBytes, true)
    val err = PrintStream(errBytes, true)

    fun output(): String = outBytes.toString()

    fun errors(): String = errBytes.toString()
}

class Sandbox {
    private val dirs = mutableListOf<Path>()

    val workspace: Workspace = Workspace.create(dir().resolve("ws"))
    val archiveRoot: Path = dir()

    fun dir(): Path = Files.createTempDirectory("zopf-cli").also { dirs.add(it) }

    fun save(workflow: Workflow): Workflow = workflow.also { WorkflowStore(workspace).save(it) }

    fun cleanup() = dirs.forEach { it.toFile().deleteRecursively() }

    fun run(
        args: List<String>,
        executor: NodeExecutor = FakeExecutor(),
        streams: Streams = Streams(),
    ): Pair<Int, Streams> {
        val full = args + listOf("--workspace", workspace.root.toString())
        val code = runWorkflow(Options.parse(full, RUN_OPTIONS), streams.out, streams.err, executor, archiveRoot)
        return code to streams
    }
}

class FakeExecutor(
    private val fail: Set<String> = emptySet(),
    private val output: (String) -> String = { "green" },
) : NodeExecutor {
    val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val models: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val providers: MutableList<AgentProviderId?> = Collections.synchronizedList(mutableListOf())
    val directories: MutableList<Path?> = Collections.synchronizedList(mutableListOf())

    override suspend fun execute(execution: NodeExecution) {
        val id = execution.node.id
        started.add(id)
        models.add(execution.node.model ?: execution.workflow.defaults.model)
        providers.add(execution.run.provider)
        directories.add(execution.run.cwd)

        val text = output(id)
        execution.archive.appendRaw(id, text)
        execution.run.consume(ShellLine(text, isError = false))
        if (id in fail) error("$id was told to fail")
    }
}

fun workflow(
    nodes: List<WorkflowNode>,
    edges: List<Pair<String, String>> = emptyList(),
    name: String = "demo",
) = Workflow(name = name, nodes = nodes, edges = edges.map { WorkflowEdge(it.first, it.second) })

fun shell(id: String) = WorkflowNode(id = id, type = NodeType.SHELL, command = "echo $id")

fun agent(id: String) = WorkflowNode(id = id, type = NodeType.AGENT, prompt = "do $id")

fun gate(id: String) = WorkflowNode(id = id, type = NodeType.GATE, title = "Ship it?")

fun input(
    id: String,
    question: String = "Which branch?",
    choices: List<String> = emptyList(),
    default: String = "",
) = WorkflowNode(id = id, type = NodeType.INPUT, prompt = question, choices = choices, default = default)
