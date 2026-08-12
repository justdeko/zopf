package com.dk.zopf.model

internal val exampleWorkflow =
    Workflow(
        name = "refactor-api",
        description = "Analyze the diff, apply fixes, notify",
        repos = listOf(RepoRef("app", "~/dev/zopf"), RepoRef("kuiver", "~/dev/kuiver")),
        skills = listOf("~/.claude/skills/code-review"),
        defaults = NodeDefaults(model = "opus", permissionMode = PermissionMode.ACCEPT_EDITS),
        nodes =
            listOf(
                WorkflowNode(
                    id = "analyze",
                    type = NodeType.AGENT,
                    title = "Analyze diff",
                    position = Position(40f, 120f),
                    repo = "app",
                    alsoRead = listOf("kuiver"),
                    allowedTools = listOf("Read", "Bash(git diff *)"),
                    skills = listOf("code-review"),
                    prompt = "Review the staged diff and list the risky changes.\n",
                ),
                WorkflowNode(
                    id = "fix",
                    type = NodeType.AGENT,
                    prompt = "Apply the fixes for these findings:\n\${analyze.result}\n",
                ),
                WorkflowNode(id = "build", type = NodeType.SHELL, command = "./gradlew :desktopApp:run", repo = "app"),
                WorkflowNode(id = "ok", type = NodeType.BRANCH, expression = "\${build.exitCode} == 0"),
                WorkflowNode(id = "approve", type = NodeType.GATE, title = "Ship it?"),
                WorkflowNode(
                    id = "notify",
                    type = NodeType.CONNECTOR,
                    connector = "slack-post",
                    inputs = mapOf("channel" to "#eng", "text" to "\${analyze.result}"),
                ),
            ),
        edges =
            listOf(
                WorkflowEdge("analyze", "fix"),
                WorkflowEdge("fix", "build"),
                WorkflowEdge("build", "ok"),
                WorkflowEdge("ok", "approve", condition = true),
                WorkflowEdge("approve", "notify"),
            ),
    )
