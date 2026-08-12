package com.dk.zopf.ui.preview

import com.dk.zopf.model.ConnectorInput
import com.dk.zopf.model.ConnectorManifest
import com.dk.zopf.model.ConnectorOutputField
import com.dk.zopf.model.ConnectorSecret
import com.dk.zopf.model.EdgeTrigger
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.ConsoleEntry
import com.dk.zopf.runtime.NodeRun
import com.dk.zopf.runtime.PendingPermission
import com.dk.zopf.runtime.PendingQuestion
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.showing
import com.dk.zopf.store.Connector
import com.dk.zopf.store.OpenWorkspace
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.WorkspaceConfig
import com.dk.zopf.ui.editor.EditorState
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

internal object PreviewFixtures {
    val workspaceRoot: Path = Paths.get(System.getProperty("user.home"), "zopf-preview-workspace")

    fun workspace(): Workspace = Workspace(root = workspaceRoot, config = WorkspaceConfig(name = "Preview workspace"))

    fun openWorkspace(): OpenWorkspace = OpenWorkspace(path = workspaceRoot, workspace = workspace(), lastOpened = System.currentTimeMillis())

    fun workflow(): Workflow =
        Workflow(
            name = "ship-feature",
            description = "Plans a change, runs the tests, and posts the result to Slack.",
            repos = listOf(RepoRef(id = "app", path = "..")),
            nodes =
                listOf(
                    WorkflowNode(
                        id = "brief",
                        type = NodeType.INPUT,
                        title = "Brief",
                        prompt = "What should this change do?",
                    ),
                    WorkflowNode(
                        id = "plan",
                        type = NodeType.AGENT,
                        title = "Plan the change",
                        repo = "app",
                        prompt = "Read \${brief.result} and sketch an implementation plan.",
                        model = "sonnet",
                    ),
                    WorkflowNode(
                        id = "tests",
                        type = NodeType.SHELL,
                        title = "Run tests",
                        repo = "app",
                        command = "./gradlew :core:jvmTest",
                    ),
                    WorkflowNode(
                        id = "route",
                        type = NodeType.BRANCH,
                        title = "Tests passed?",
                        expression = "\${tests.exitCode} == 0",
                    ),
                    WorkflowNode(
                        id = "notify",
                        type = NodeType.CONNECTOR,
                        title = "Notify Slack",
                        connector = "slack-notify",
                        inputs = mapOf("channel" to "#eng", "message" to "\${tests.result}"),
                    ),
                    WorkflowNode(
                        id = "review",
                        type = NodeType.GATE,
                        title = "Ship it?",
                    ),
                ),
            edges =
                listOf(
                    WorkflowEdge(from = "brief", to = "plan"),
                    WorkflowEdge(from = "plan", to = "tests"),
                    WorkflowEdge(from = "tests", to = "route"),
                    WorkflowEdge(from = "route", to = "notify", condition = true),
                    WorkflowEdge(from = "route", to = "review", condition = false),
                    WorkflowEdge(from = "plan", to = "notify", on = EdgeTrigger.FAILURE),
                ),
        )

    fun connectorManifest(): ConnectorManifest =
        ConnectorManifest(
            name = "slack-notify",
            description = "Posts a message to a Slack channel via a bot token.",
            run = "run.sh",
            inputs =
                listOf(
                    ConnectorInput("channel", "Channel to post to, e.g. #eng", required = true),
                    ConnectorInput("message", "Message body", required = true),
                ),
            env = listOf(ConnectorSecret("SLACK_TOKEN", "Bot token", keychain = "slack-token")),
            outputs = listOf(ConnectorOutputField("messageId", "The posted message's id")),
            timeoutSeconds = 30,
        )

    fun connector(): Connector =
        Connector(
            manifest = connectorManifest(),
            dir = workspaceRoot.resolve("connectors/slack-notify"),
            source = "workspace",
        )

    fun editorState(workflow: Workflow = workflow()): EditorState =
        EditorState(
            initial = workflow,
            workspace = workspace(),
            connectorsProvider = { listOf(connector()) },
            onSave = {},
        )

    fun nodeRun(
        status: RunStatus = RunStatus.RUNNING,
        nodeId: String = "plan",
        nodeTitle: String = "Plan the change",
        nodeType: NodeType = NodeType.AGENT,
        withTranscript: Boolean = true,
        pendingPermission: PendingPermission? = null,
        pendingQuestion: PendingQuestion? = null,
    ): NodeRun {
        val run =
            NodeRun(
                id = "run-preview",
                workflowName = "ship-feature",
                nodeId = nodeId,
                nodeTitle = nodeTitle,
                nodeType = nodeType,
                cwd = workspaceRoot,
                startedAt = Instant.now().minusSeconds(96),
            )
        run.showing(
            status = status,
            model = "claude-sonnet-5",
            sessionId = "8f14e45f-ceea-4b7f-8c31-preview",
            costUsd = 0.0842,
            question = pendingQuestion,
            permission = pendingPermission,
        )

        if (withTranscript) {
            run.entries.add(
                ConsoleEntry.Message(
                    0,
                    "Reading the workflow engine before making changes.",
                    isThinking = true,
                    isStreaming = false,
                ),
            )
            run.entries.add(
                ConsoleEntry.Message(1, "I'll check WorkflowEngine.kt and NodeRun.kt first.", isStreaming = false),
            )
            run.entries.add(
                ConsoleEntry.ToolCall(2, "tool-1", "Read", "core/.../WorkflowEngine.kt", result = "398 lines"),
            )
            run.entries.add(ConsoleEntry.ToolCall(3, "tool-2", "Bash", "./gradlew :core:jvmTest"))
            run.entries.add(ConsoleEntry.Output(4, "BUILD SUCCESSFUL in 42s", isError = false))
            run.entries.add(ConsoleEntry.Notice(5, "Denied: Bash rm -rf build", isWarning = true))
            if (status.isFinished) {
                run.entries.add(
                    ConsoleEntry.Summary(
                        key = 6,
                        text = "Plan is ready in the transcript above.",
                        costUsd = run.costUsd,
                        durationMs = 96_000,
                        isError = status == RunStatus.FAILED,
                    ),
                )
            }
        }
        return run
    }

    fun pendingQuestion(): PendingQuestion =
        PendingQuestion(
            question = "Which environment should this deploy to?",
            choices = listOf("staging", "production"),
            default = "staging",
        )
}
