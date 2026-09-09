package com.dk.zopf.runtime

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.ignoredFields
import com.dk.zopf.model.modelFor
import com.dk.zopf.model.providerFor
import com.dk.zopf.model.toJsonSchema
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.ConnectorStore
import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.availableSkills
import com.dk.zopf.store.resolvePathAgainst
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NodeExecution(
    val run: NodeRun,
    val node: WorkflowNode,
    val workflow: Workflow,
    val workspace: Workspace?,
    val outputs: RunContext,
    val archive: RunArchive,
    val interactive: Boolean,
)

interface NodeExecutor {
    suspend fun execute(execution: NodeExecution)
}

class ProcessNodeExecutor(
    private val agents: AgentRunner = AgentRunner(),
    private val shell: ShellRunner = ShellRunner(),
    private val connector: ConnectorRunner = ConnectorRunner(),
    private val secretResolver: SecretResolver = SecretResolver(),
    private val settings: LiveSettings = LiveSettings(),
    private val permissions: PermissionBridge? = null,
) : NodeExecutor {
    override suspend fun execute(execution: NodeExecution) {
        when (execution.node.type) {
            NodeType.AGENT -> runAgent(execution)
            NodeType.SHELL -> runShell(execution)
            NodeType.CONNECTOR -> runConnector(execution)
            else -> error("${execution.node.type} isn't a process")
        }
    }

    private suspend fun runAgent(execution: NodeExecution) {
        val run = execution.run
        val node = execution.node
        val cwd = requireNotNull(run.cwd) { "An agent node runs somewhere" }
        val provider = AgentProviders.of(run.provider ?: resolveProvider(node, execution.workflow, settings.current))
        val can = provider.capabilities

        val written =
            agentPromptText(node, execution.workspace, cwd).getOrElse {
                run.notice(it.message.orEmpty(), isWarning = true)
                run.finish(RunStatus.FAILED)
                return
            }
        if (node.promptFile.isNotBlank()) run.notice("Prompt from ${node.promptFile}")

        val skills =
            if (can.skills) planSkills(node.skills, execution.skillsInScope(), cwd) else SkillPlan(emptyList(), emptyList())
        skills.notices.forEach { run.notice(it.text, it.isWarning) }

        val prompt =
            skills.withInvocation(
                execution.outputs
                    .interpolate(written)
                    .also { warnUnresolved(run, it) }
                    .text,
            )

        val mode = (node.permissionMode ?: execution.workflow.defaults.permissionMode)?.takeIf { can.toolPermissions }
        val sandbox = (node.sandbox ?: execution.workflow.defaults.sandbox)?.takeIf { can.sandbox }

        val askAbout =
            if (can.inlineApproval && settings.current.inlineApproval) askableTools(mode) else emptyList()
        val settingsFile = permissions?.settingsFile(askAbout)
        if (settingsFile != null) {
            run.notice("Will ask before ${askAbout.joinToString()}")
        }

        execution.warnIgnored(provider.id)

        val invocation =
            AgentInvocation(
                prompt = prompt,
                cwd = cwd,
                model = resolveModel(node, execution.workflow, settings.current, provider.id),
                permissionMode = mode,
                sandbox = sandbox,
                allowedTools = if (can.toolPermissions) node.allowedTools else emptyList(),
                addDirs =
                    if (can.extraDirectories) {
                        node.alsoRead.mapNotNull { execution.workspace?.resolveRepo(execution.workflow, it) }
                    } else {
                        emptyList()
                    },
                pluginDirs = skills.pluginDirs,
                settingsFile = settingsFile,
                jsonSchema =
                    node.schema
                        .takeIf { it.isNotEmpty() && can.outputSchema }
                        ?.toJsonSchema()
                        ?.toString(),
            )
        val session = agents.start(provider, invocation)
        run.live = AgentLive(session)
        run.sessionId = session.sessionId
        run.command = session.command
        run.model = invocation.model
        run.status = RunStatus.STARTING
        if (sandbox != null) run.notice("Sandbox: ${sandbox.cliValue}")

        if (provider.promptChannel == PromptChannel.STDIN) {
            session.send(prompt).getOrThrow()
            if (!execution.interactive) session.endInput()
        }

        val exit =
            within(
                run = run,
                seconds = if (execution.interactive) null else execution.deadline(),
                stop = session::stop,
                kill = session::kill,
                drain = {
                    runCatching {
                        session
                            .events { line -> execution.archive.appendRaw(node.id, line) }
                            .collect { event -> run.consume(event) }
                    }.onFailure { run.notice("Stream ended: ${it.message}", isWarning = true) }
                },
                awaitExit = session::awaitExit,
            )
        if (invocation.jsonSchema != null) run.takeFieldsFromJsonResult()
        if (session.stderrText.isNotBlank() && exit != 0) {
            run.notice(session.stderrText, isWarning = true)
        }
        run.finish(outcome(run, exit), exit)
    }

    private suspend fun runShell(execution: NodeExecution) {
        val run = execution.run
        val node = execution.node
        val cwd = requireNotNull(run.cwd) { "A shell node runs somewhere" }

        val command =
            execution.outputs
                .interpolate(node.command)
                .also { warnUnresolved(run, it) }
                .text
        val session = shell.start(ShellInvocation(command, cwd))
        run.live = ShellLive(session)
        run.command = listOf(command)
        run.status = RunStatus.STARTING
        run.notice("$ $command")

        val exit =
            within(
                run = run,
                seconds = execution.deadline(),
                stop = session::stop,
                kill = session::kill,
                drain = {
                    runCatching {
                        session.lines().collect { line ->
                            execution.archive.appendRaw(node.id, line.text)
                            run.consume(line)
                        }
                    }.onFailure { run.notice("Output ended: ${it.message}", isWarning = true) }
                },
                awaitExit = session::awaitExit,
            )
        run.exitCode = exit
        run.finish(outcome(run, exit), exit)
    }

    private suspend fun runConnector(execution: NodeExecution) {
        val run = execution.run
        val node = execution.node
        val dir = requireNotNull(run.cwd) { "A connector node runs in the connector's directory" }
        val connectorDef = ConnectorStore.load(dir).getOrThrow()
        val manifest = connectorDef.manifest

        val inputs = resolveInputs(execution, manifest)
        val missing =
            manifest.inputs
                .filter { it.required && inputs[it.name].isNullOrBlank() }
                .map { it.name }
        if (missing.isNotEmpty()) {
            run.notice("${manifest.name} needs ${missing.joinToString()}. Set them on this node.", isWarning = true)
            run.finish(RunStatus.FAILED)
            return
        }
        val secrets = secretResolver.resolve(manifest.env)
        val unresolved = secrets.filter { it.isMissing && it.declared.required }
        if (unresolved.isNotEmpty()) {
            run.notice(
                "Couldn't find ${unresolved.joinToString { it.name }}. ${manifest.name} reads " +
                    unresolved.joinToString { "${it.name} (${it.declared.sourceLabel})" },
                isWarning = true,
            )
            run.finish(RunStatus.FAILED)
            return
        }

        secrets
            .filterNot { it.isMissing }
            .takeIf { it.isNotEmpty() }
            ?.let { run.notice("Secrets: ${it.joinToString()}") }
        secrets
            .filter { it.isMissing }
            .takeIf { it.isNotEmpty() }
            ?.let { run.notice("Optional and not set: ${it.joinToString { s -> s.name }}") }

        val timeoutSeconds = execution.deadline() ?: manifest.timeoutSeconds
        val invocation =
            ConnectorInvocation(
                script = connectorDef.script,
                cwd = dir,
                inputs = inputs,
                timeoutSeconds = timeoutSeconds,
                env = secrets.mapNotNull { s -> s.value?.let { s.name to it } }.toMap(),
            )
        val session = connector.start(invocation)
        run.live = ConnectorLive(session)
        run.command = session.command
        run.status = RunStatus.STARTING
        run.notice("${manifest.name} ← ${invocation.stdinJson()}")

        val exit =
            within(
                run = run,
                seconds = timeoutSeconds,
                stop = session::stop,
                kill = session::kill,
                drain = {
                    runCatching {
                        session.lines().collect { line ->
                            execution.archive.appendRaw(node.id, line.text)
                            run.consume(line)
                        }
                    }.onFailure { run.notice("Output ended: ${it.message}", isWarning = true) }
                },
                awaitExit = session::awaitExit,
            )
        val output = session.output()

        if (!output.sawJson) {
            run.notice(
                "${manifest.name} printed no JSON object, so its plain output is the result",
                isWarning = true,
            )
        }
        output.error?.let {
            run.notice(it, isWarning = true)
            run.status = RunStatus.FAILED
        }

        manifest.outputs
            .map { it.name }
            .filterNot { it == "result" || it in output.fields }
            .takeIf { it.isNotEmpty() }
            ?.let {
                run.notice(
                    "${manifest.name} declares ${it.joinToString()} but didn't return " +
                        if (it.size == 1) "it" else "them",
                    isWarning = true,
                )
            }

        run.produce(output.result, output.fields)
        run.exitCode = exit
        run.finish(outcome(run, exit), exit)
    }

    private fun resolveInputs(
        execution: NodeExecution,
        manifest: ConnectorManifest,
    ): Map<String, String> {
        val provided =
            execution.node.inputs.mapValues { (_, raw) ->
                execution.outputs
                    .interpolate(raw)
                    .also { warnUnresolved(execution.run, it) }
                    .text
            }
        return buildMap {
            manifest.inputs.forEach { put(it.name, it.default) }
            putAll(provided)
        }
    }
}

const val NODE_TIMEOUT_EXIT = 124

private const val TERM_GRACE_MILLIS = 2_000L

private fun NodeExecution.deadline(): Int? = node.timeoutSeconds ?: workflow.defaults.timeoutSeconds

private suspend fun within(
    run: NodeRun,
    seconds: Int?,
    stop: () -> Unit,
    kill: () -> Unit,
    drain: suspend () -> Unit,
    awaitExit: suspend () -> Int,
): Int =
    coroutineScope {
        if (seconds == null || seconds <= 0) {
            drain()
            return@coroutineScope awaitExit()
        }

        val expired = AtomicBoolean(false)
        val watchdog =
            launch {
                delay(seconds.seconds)
                expired.set(true)
                run.notice("Gave up after ${seconds}s. Nothing finished it.", isWarning = true)
                stop()
                delay(TERM_GRACE_MILLIS.milliseconds)
                kill()
            }

        drain()
        val exit = awaitExit()
        watchdog.cancel()
        if (expired.get()) NODE_TIMEOUT_EXIT else exit
    }

internal fun agentPromptText(
    node: WorkflowNode,
    workspace: Workspace?,
    cwd: Path,
): Result<String> {
    if (node.promptFile.isBlank()) return Result.success(node.prompt)

    val file = workspace?.resolvePath(node.promptFile) ?: resolvePathAgainst(node.promptFile, cwd)
    return runCatching {
        require(file.isRegularFile()) { "No prompt file at $file" }
        file.readText()
    }
}

private fun NodeExecution.skillsInScope(): List<DiscoveredSkill> = workspace?.let { availableSkills(it, workflow) }.orEmpty()

private fun NodeExecution.warnIgnored(provider: AgentProviderId) {
    val ignored = provider.ignoredFields(node)
    if (ignored.isEmpty()) return
    run.notice("${provider.label} has no ${ignored.joinToString()}, so it is left out", isWarning = true)
}

internal fun resolveModel(
    node: WorkflowNode,
    workflow: Workflow,
    settings: AppSettings,
    provider: AgentProviderId = resolveProvider(node, workflow, settings),
): String? = workflow.modelFor(node, provider, settings.defaultProvider, settings.defaultModel)

internal fun resolveProvider(
    node: WorkflowNode,
    workflow: Workflow,
    settings: AppSettings,
): AgentProviderId = workflow.providerFor(node, settings.defaultProvider)

internal fun outcome(
    run: NodeRun,
    exit: Int,
): RunStatus =
    when {
        run.detached -> RunStatus.DETACHED
        run.stopping -> RunStatus.STOPPED
        exit != 0 -> RunStatus.FAILED
        run.status == RunStatus.FAILED -> RunStatus.FAILED
        else -> RunStatus.SUCCEEDED
    }

internal fun warnUnresolved(
    run: NodeRun,
    interpolated: Interpolated,
) {
    if (interpolated.isComplete) return
    run.notice(
        "Nothing has produced ${interpolated.unresolved.joinToString { "\${$it}" }} yet, " +
            "so the reference is left as written",
        isWarning = true,
    )
}

private class AgentLive(
    private val session: AgentSession,
) : LiveProcess {
    override fun stop() = session.stop()

    override fun send(text: String): Result<Unit> = session.send(text)

    override fun endInput() = session.endInput()
}

private class ShellLive(
    private val session: ShellSession,
) : LiveProcess {
    override fun stop() = session.stop()

    override fun send(text: String): Result<Unit> = Result.failure(UnsupportedOperationException("A shell node doesn't take follow-ups"))

    override fun endInput() = Unit
}

private class ConnectorLive(
    private val session: ConnectorSession,
) : LiveProcess {
    override fun stop() = session.stop()

    override fun send(text: String): Result<Unit> = Result.failure(UnsupportedOperationException("A connector takes its inputs up front"))

    override fun endInput() = Unit
}
