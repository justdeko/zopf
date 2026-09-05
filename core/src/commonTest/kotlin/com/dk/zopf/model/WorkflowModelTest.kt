package com.dk.zopf.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowValidationTest {
    private fun List<WorkflowIssue>.messages() = map { it.message }

    private fun List<WorkflowIssue>.mentioning(fragment: String) = filter { fragment in it.message }

    private fun wf(
        vararg nodes: WorkflowNode,
        edges: List<WorkflowEdge> = emptyList(),
    ) = Workflow(name = "w", nodes = nodes.toList(), edges = edges)

    private fun shell(
        id: String,
        command: String = "echo $id",
    ) = WorkflowNode(id, NodeType.SHELL, command = command)

    @Test
    fun `a node naming a repo the workflow never declared is flagged`() {
        val w =
            Workflow(
                name = "w",
                repos = listOf(RepoRef("app", "~/dev/zopf")),
                nodes =
                    listOf(
                        WorkflowNode("a", NodeType.AGENT, prompt = "hi", repo = "app"),
                        WorkflowNode("b", NodeType.AGENT, prompt = "hi", repo = "kuiver", alsoRead = listOf("gone")),
                    ),
                edges = listOf(WorkflowEdge("a", "b")),
            )

        val issues = w.validate()

        assertEquals(1, issues.mentioning("runs in \"kuiver\"").size)
        assertEquals(1, issues.mentioning("also reads \"gone\"").size)
        assertTrue(issues.none { "app" in it.message })
    }

    @Test
    fun `an output field that shadows a built-in one, or that cannot be named, is caught`() {
        val w =
            wf(
                WorkflowNode(
                    "review",
                    NodeType.AGENT,
                    prompt = "hi",
                    schema =
                        listOf(
                            SchemaField("severity"),
                            SchemaField("severity"),
                            SchemaField("result"),
                            SchemaField("total cost"),
                            SchemaField(""),
                        ),
                ),
            )

        val issues = w.validate()

        assertEquals(1, issues.mentioning("more than once").size)
        assertEquals(1, issues.mentioning("\${review.result} already means").size)
        assertEquals(1, issues.mentioning("can't name").size)
        assertEquals(1, issues.mentioning("no name").size)
    }

    @Test
    fun `only an agent node can declare an output schema`() {
        val w = wf(shell("build").copy(schema = listOf(SchemaField("severity"))))

        assertEquals(1, w.validate().mentioning("only an agent node").size)
    }

    @Test
    fun `a node whose CLI isn't installed is warned about before the run, not during it`() {
        val w =
            wf(
                WorkflowNode("plan", NodeType.AGENT, prompt = "hi"),
                WorkflowNode("ship", NodeType.AGENT, provider = AgentProviderId.CODEX, prompt = "hi"),
                edges = listOf(WorkflowEdge("plan", "ship")),
            )

        val issues = w.validate(executableExists = { it == AgentProviderId.CLAUDE })

        val missing = issues.mentioning("isn't on your PATH")
        assertEquals(listOf("ship"), missing.map { it.nodeId })
        assertEquals(listOf(WorkflowIssue.Severity.WARNING), missing.map { it.severity })
        assertTrue("codex" in missing.single().message, missing.single().message)
    }

    @Test
    fun `nothing is said about a CLI when zopf has no way to look`() {
        val w = wf(WorkflowNode("plan", NodeType.AGENT, prompt = "hi"))

        assertEquals(emptyList(), w.validate().messages())
    }

    @Test
    fun `a clean schema is silent`() {
        val w =
            wf(
                WorkflowNode(
                    "review",
                    NodeType.AGENT,
                    prompt = "hi",
                    schema = listOf(SchemaField("severity"), SchemaField("findings", SchemaFieldType.ARRAY)),
                ),
            )

        assertEquals(emptyList(), w.validate().messages())
    }

    @Test
    fun `a repo whose path has gone away is flagged once, for the workflow`() {
        val w = Workflow(name = "w", repos = listOf(RepoRef("app", "/nope")))

        val issues = w.validate(repoExists = { false })

        assertEquals(listOf(null), issues.map { it.nodeId })
        assertTrue("/nope" in issues.single().message)
    }

    @Test
    fun `a reference to a node that is not upstream is flagged, and one that is is not`() {
        val w =
            wf(
                WorkflowNode("analyze", NodeType.AGENT, prompt = "look"),
                WorkflowNode("fix", NodeType.AGENT, prompt = "apply \${analyze.result}"),
                WorkflowNode("stray", NodeType.AGENT, prompt = "apply \${fix.result}"),
                edges = listOf(WorkflowEdge("analyze", "fix")),
            )

        val issues = w.validate()

        assertTrue(issues.none { it.nodeId == "fix" })
        assertEquals(1, issues.mentioning("nothing connects fix to it").size)
    }

    @Test
    fun `a reference to a deleted node is called out as such`() {
        val w = wf(WorkflowNode("fix", NodeType.AGENT, prompt = "apply \${analyze.result}"))

        assertEquals(1, w.validate().mentioning("no longer a node").size)
    }

    @Test
    fun `a reference to a field the upstream node never produces is an error, and a declared one is not`() {
        val w =
            wf(
                WorkflowNode(
                    "review",
                    NodeType.AGENT,
                    prompt = "look",
                    schema = listOf(SchemaField("verdict")),
                ),
                WorkflowNode(
                    "act",
                    NodeType.AGENT,
                    prompt = "\${review.verdict} and \${review.result} and \${review.brdict}",
                ),
                edges = listOf(WorkflowEdge("review", "act")),
            )

        val issues = w.validate().mentioning("review produces")

        assertEquals(1, issues.size)
        assertEquals("act", issues.single().nodeId)
        assertTrue("\${review.brdict}" in issues.single().message)
        assertTrue("verdict" in issues.single().message)
    }

    @Test
    fun `a shell node's exit code is a reference but an agent's cost is not its own`() {
        val w =
            wf(
                shell("build"),
                WorkflowNode("tell", NodeType.AGENT, prompt = "\${build.exitCode} \${build.costUsd}"),
                edges = listOf(WorkflowEdge("build", "tell")),
            )

        assertEquals(1, w.validate().mentioning("build produces").size)
    }

    @Test
    fun `a connector's output is only checked when its manifest is there to check against`() {
        val w =
            wf(
                WorkflowNode("post", NodeType.CONNECTOR, connector = "slack-post"),
                WorkflowNode("tell", NodeType.AGENT, prompt = "\${post.permalink}"),
                edges = listOf(WorkflowEdge("post", "tell")),
            )

        assertEquals(emptyList(), w.validate().mentioning("post produces"))

        val manifest =
            ConnectorManifest(
                name = "slack-post",
                outputs = listOf(ConnectorOutputField("permalink")),
            )
        assertEquals(emptyList(), w.validate(connector = { manifest }).mentioning("post produces"))

        assertEquals(1, w.validate(connector = { manifest.copy(outputs = emptyList()) }).mentioning("post produces").size)
    }

    @Test
    fun `a field on a node that is not upstream is reported once, as the missing edge it really is`() {
        val w =
            wf(
                WorkflowNode("analyze", NodeType.AGENT, prompt = "look"),
                WorkflowNode("stray", NodeType.AGENT, prompt = "\${analyze.nope}"),
            )

        val issues = w.validate()

        assertEquals(1, issues.mentioning("nothing connects analyze to it").size)
        assertEquals(emptyList(), issues.mentioning("analyze produces"))
    }

    @Test
    fun `each node type is asked for the one field it cannot run without`() {
        val w =
            wf(
                WorkflowNode("a", NodeType.AGENT),
                WorkflowNode("b", NodeType.SHELL),
                WorkflowNode("c", NodeType.CONNECTOR),
                WorkflowNode("d", NodeType.BRANCH),
                WorkflowNode("e", NodeType.GATE),
            )

        val messages = w.validate().mentioning("needs").messages()

        assertEquals(
            listOf("a needs a prompt", "b needs a command", "c needs a connector", "d needs an expression"),
            messages,
        )
    }

    @Test
    fun `a branch needs both of its edges labelled, and only one of each`() {
        val unlabelled =
            wf(
                WorkflowNode("ok", NodeType.BRANCH, expression = "true"),
                WorkflowNode("ship", NodeType.GATE),
                WorkflowNode("stop", NodeType.GATE),
                edges = listOf(WorkflowEdge("ok", "ship", true), WorkflowEdge("ok", "stop")),
            )

        assertEquals(1, unlabelled.validate().mentioning("neither true nor false").size)

        val doubled =
            unlabelled.copy(
                edges = listOf(WorkflowEdge("ok", "ship", true), WorkflowEdge("ok", "stop", true)),
            )

        assertEquals(1, doubled.validate().mentioning("2 \"true\" edges").size)
    }

    @Test
    fun `a floating node is a warning, not an error, and a lone node is neither`() {
        val floating =
            wf(
                WorkflowNode("a", NodeType.GATE),
                WorkflowNode("b", NodeType.GATE),
                WorkflowNode("orphan", NodeType.GATE),
                edges = listOf(WorkflowEdge("a", "b")),
            )

        val issue = floating.validate().single()
        assertEquals("orphan", issue.nodeId)
        assertEquals(WorkflowIssue.Severity.WARNING, issue.severity)

        assertTrue(wf(WorkflowNode("a", NodeType.GATE)).validate().isEmpty())
    }

    @Test
    fun `the plan's example workflow validates clean`() {
        assertEquals(emptyList(), exampleWorkflow.validate().messages())
    }

    private fun exampleWithConnector(manifest: ConnectorManifest?) = exampleWorkflow.validate(connector = { manifest })

    @Test
    fun `a connector nothing has installed is flagged on the node that calls it`() {
        val issues = exampleWithConnector(null)

        assertEquals(listOf("notify"), issues.map { it.nodeId })
        assertTrue("slack-post" in issues.single().message)
    }

    @Test
    fun `a node validates clean against the manifest it fills in`() {
        val manifest =
            ConnectorManifest(
                name = "slack-post",
                inputs =
                    listOf(
                        ConnectorInput("channel", required = true),
                        ConnectorInput("text", required = true),
                    ),
            )

        assertEquals(emptyList(), exampleWithConnector(manifest).messages())
    }

    @Test
    fun `a required input the node leaves out is an error, unless the manifest defaults it`() {
        val required = ConnectorInput("webhook", required = true)
        val defaulted = required.copy(default = "https://example.test/hook")

        val missing = exampleWithConnector(ConnectorManifest("slack-post", inputs = listOf(required)))
        val filledIn = exampleWithConnector(ConnectorManifest("slack-post", inputs = listOf(defaulted)))

        assertEquals(1, missing.mentioning("needs an input for \"webhook\"").size)
        assertTrue(filledIn.none { "webhook" in it.message })
    }

    @Test
    fun `an input the manifest does not declare is a warning, not an error`() {
        val issues =
            exampleWithConnector(
                ConnectorManifest("slack-post", inputs = listOf(ConnectorInput("channel"))),
            ).mentioning("doesn't declare")

        assertEquals(1, issues.size)
        assertTrue("text" in issues.single().message)

        assertEquals(WorkflowIssue.Severity.WARNING, issues.single().severity)
    }

    @Test
    fun `connectors are not checked at all when nothing knows what is installed`() {
        assertEquals(emptyList(), exampleWorkflow.validate().mentioning("slack-post"))
    }

    private fun withPromptFile(
        file: String,
        prompt: String = "",
    ) = wf(WorkflowNode("review", NodeType.AGENT, prompt = prompt, promptFile = file))

    @Test
    fun `a prompt file satisfies the node that would otherwise need a prompt`() {
        assertEquals(emptyList(), withPromptFile("prompts/review.md").validate().messages())
    }

    @Test
    fun `a prompt file that isn't on disk is an error on the node`() {
        val issues = withPromptFile("prompts/gone.md").validate(fileExists = { false })

        assertEquals(listOf("review"), issues.map { it.nodeId })
        assertEquals(WorkflowIssue.Severity.ERROR, issues.single().severity)
        assertTrue("prompts/gone.md" in issues.single().message)
    }

    @Test
    fun `an inline prompt under a prompt file is a warning, because nothing sends it`() {
        val issues = withPromptFile("prompts/review.md", prompt = "left over").validate()

        assertEquals(1, issues.size)
        assertEquals(WorkflowIssue.Severity.WARNING, issues.single().severity)
        assertTrue("ignored" in issues.single().message)
    }

    private fun twoNodesWithPromptFile() =
        wf(
            shell("build", command = "true"),
            WorkflowNode("review", NodeType.AGENT, promptFile = "prompts/review.md"),
            edges = listOf(WorkflowEdge("build", "review")),
        )

    @Test
    fun `a reference inside a prompt file is checked, and the issue says which file`() {
        val issues =
            twoNodesWithPromptFile()
                .validate(promptText = { "Look at \${gone.result} and \${build.result}" })

        assertEquals(listOf("review"), issues.map { it.nodeId })
        assertTrue("gone" in issues.single().message)

        assertTrue("prompts/review.md" in issues.single().message)
    }

    @Test
    fun `a prompt file reference to a node that isn't upstream is flagged too`() {
        val w = twoNodesWithPromptFile().copy(edges = emptyList())

        val issues = w.validate(promptText = { "\${build.result}" }).mentioning("nothing connects")

        assertEquals(listOf("review"), issues.map { it.nodeId })
        assertTrue("prompts/review.md" in issues.single().message)
    }

    @Test
    fun `a prompt file nothing can read is not guessed at`() {
        assertEquals(emptyList(), twoNodesWithPromptFile().validate().messages())
    }

    @Test
    fun `a skill name nothing can resolve is a warning on the node that names it`() {
        val issues = exampleWorkflow.validate(knownSkills = emptySet()).mentioning("code-review")

        assertEquals(listOf("analyze"), issues.map { it.nodeId })

        assertEquals(WorkflowIssue.Severity.WARNING, issues.single().severity)
    }

    @Test
    fun `skills are not checked at all when nothing knows what is installed`() {
        assertEquals(emptyList(), exampleWorkflow.validate().mentioning("code-review"))
        assertEquals(emptyList(), exampleWorkflow.validate(knownSkills = setOf("code-review")).messages())
    }

    @Test
    fun `a failure edge is only worth drawing out of a node that can fail`() {
        fun recovering(source: WorkflowNode) =
            wf(
                source,
                shell("recover", command = "echo oops"),
                edges = listOf(WorkflowEdge(source.id, "recover", on = EdgeTrigger.FAILURE)),
            ).validate()

        listOf(
            WorkflowNode("approve", NodeType.GATE),
            WorkflowNode("ask", NodeType.INPUT, prompt = "Which branch?"),
        ).forEach { source ->
            val issue = recovering(source).single { it.nodeId == source.id }
            assertEquals(WorkflowIssue.Severity.WARNING, issue.severity, source.id)
            assertTrue("never fails" in issue.message, issue.message)
        }

        assertEquals(emptyList(), recovering(shell("tests", command = "./gradlew test")).messages())
    }

    @Test
    fun `an input node with no question is caught before it asks nothing`() {
        val workflow = wf(WorkflowNode("ask", NodeType.INPUT))

        assertEquals(listOf("ask needs a question"), workflow.validate().mentioning("question").messages())
    }

    @Test
    fun `a default has to be one of the choices offered beside it`() {
        fun asking(default: String) =
            wf(
                WorkflowNode(
                    "ask",
                    NodeType.INPUT,
                    prompt = "Deploy where?",
                    choices = listOf("staging", "prod"),
                    default = default,
                ),
            ).validate()

        val issue = asking("sandbox").single { it.nodeId == "ask" }
        assertEquals(WorkflowIssue.Severity.WARNING, issue.severity)
        assertTrue("sandbox" in issue.message, issue.message)

        assertEquals(emptyList(), asking("staging").messages())
    }

    @Test
    fun `a when on a non-branch edge is an error rather than a node that never runs`() {
        val workflow =
            wf(
                shell("tests", command = "./gradlew test"),
                shell("ship"),
                edges = listOf(WorkflowEdge("tests", "ship", condition = true)),
            )

        val issue = workflow.validate().single { it.nodeId == "tests" }
        assertEquals(WorkflowIssue.Severity.ERROR, issue.severity)
        assertTrue("isn't a branch" in issue.message, issue.message)
    }

    @Test
    fun `a loop is an error rather than a run that fails halfway`() {
        val workflow =
            wf(
                shell("a"),
                shell("b"),
                edges = listOf(WorkflowEdge("a", "b"), WorkflowEdge("b", "a")),
            )

        val issue = workflow.validate().single { "loop" in it.message }
        assertEquals(WorkflowIssue.Severity.ERROR, issue.severity)
        assertTrue("a" in issue.message && "b" in issue.message, issue.message)
    }

    @Test
    fun `two nodes sharing an id are an error, and the message says which id`() {
        val workflow = wf(shell("a", command = "echo first"), shell("a", command = "echo second"))

        val issue = workflow.validate().single { "unique" in it.message }
        assertEquals(WorkflowIssue.Severity.ERROR, issue.severity)
        assertTrue("2 nodes called \"a\"" in issue.message, issue.message)
    }

    @Test
    fun `an edge naming a node that does not exist is an error`() {
        val workflow =
            wf(
                shell("build"),
                shell("deploy"),
                edges = listOf(WorkflowEdge("buld", "deploy"), WorkflowEdge("build", "deploy")),
            )

        val issue = workflow.validate().single { "buld" in it.message }
        assertEquals(WorkflowIssue.Severity.ERROR, issue.severity)
    }

    @Test
    fun `a node wired to itself is an error`() {
        val workflow = wf(shell("a"), edges = listOf(WorkflowEdge("a", "a")))

        assertTrue(workflow.validate().any { "feeds itself" in it.message })
    }

    @Test
    fun `a diamond is not mistaken for a loop`() {
        val workflow =
            wf(
                shell("start"),
                shell("left"),
                shell("right"),
                shell("join"),
                edges =
                    listOf(
                        WorkflowEdge("start", "left"),
                        WorkflowEdge("start", "right"),
                        WorkflowEdge("left", "join"),
                        WorkflowEdge("right", "join"),
                    ),
            )

        assertEquals(emptyList(), workflow.validate().messages())
    }
}

class WorkflowEditsTest {
    private fun workflow(
        vararg nodes: WorkflowNode,
        edges: List<WorkflowEdge> = emptyList(),
    ) = Workflow(name = "w", nodes = nodes.toList(), edges = edges)

    @Test
    fun `added nodes get an unused id derived from their type`() {
        var w = Workflow(name = "w")
        repeat(3) { w = w.addNode(NodeType.AGENT).first }
        w = w.addNode(NodeType.SHELL).first

        assertEquals(listOf("agent", "agent-2", "agent-3", "shell"), w.nodes.map { it.id })
    }

    @Test
    fun `an id already taken by a hand-written file is skipped rather than clobbered`() {
        val existing = workflow(WorkflowNode("shell", NodeType.AGENT, title = "hand written"))

        val (updated, added) = existing.addNode(NodeType.SHELL)

        assertEquals("shell-2", added.id)
        assertEquals("hand written", updated.node("shell")?.title)
    }

    @Test
    fun `a duplicate keeps the fields and the references, and picks up none of the edges`() {
        val original =
            workflow(
                WorkflowNode("plan", NodeType.AGENT),
                WorkflowNode(
                    "fix",
                    NodeType.AGENT,
                    title = "Apply the fixes",
                    prompt = "Apply:\n\${plan.result}",
                    position = Position(10f, 20f),
                ),
                edges = listOf(WorkflowEdge("plan", "fix")),
            )

        val (updated, clone) = requireNotNull(original.duplicateNode("fix"))

        assertEquals("fix-2", clone.id)
        assertEquals("Apply the fixes copy", clone.title)
        assertEquals("Apply:\n\${plan.result}", clone.prompt)
        assertEquals(listOf(WorkflowEdge("plan", "fix")), updated.edges)
        assertTrue(clone.position!!.x > 10f, "the copy landed exactly on top of the original")
    }

    @Test
    fun `duplicating a node with no title leaves it untitled, since its id already tells them apart`() {
        val original = workflow(WorkflowNode("shell", NodeType.SHELL, command = "echo hi"))

        val (_, clone) = requireNotNull(original.duplicateNode("shell"))

        assertEquals("", clone.title)
        assertEquals("shell-2", clone.id)
    }

    @Test
    fun `duplicating an id that isn't there is null rather than a no-op copy`() {
        assertNull(workflow(WorkflowNode("a", NodeType.GATE)).duplicateNode("b"))
    }

    @Test
    fun `renaming a node carries its edges and every reference to it`() {
        val original =
            workflow(
                WorkflowNode("analyze", NodeType.AGENT),
                WorkflowNode("fix", NodeType.AGENT, prompt = "Apply:\n\${analyze.result}\ncost \${analyze.costUsd}"),
                WorkflowNode("build", NodeType.SHELL, command = "echo \${analyze.result}"),
                WorkflowNode("ok", NodeType.BRANCH, expression = "\${build.exitCode} == 0"),
                edges = listOf(WorkflowEdge("analyze", "fix"), WorkflowEdge("fix", "build")),
            )

        val renamed = original.renameNode("analyze", "review").getOrThrow()

        assertEquals(
            "Apply:\n\${review.result}\ncost \${review.costUsd}",
            renamed.node("fix")?.prompt,
        )
        assertEquals("echo \${review.result}", renamed.node("build")?.command)
        assertEquals("\${build.exitCode} == 0", renamed.node("ok")?.expression)
        assertEquals(listOf("review" to "fix", "fix" to "build"), renamed.edges.map { it.from to it.to })
    }

    @Test
    fun `renaming refuses a taken id and an id that could not appear in a reference`() {
        val w = workflow(WorkflowNode("a", NodeType.AGENT), WorkflowNode("b", NodeType.AGENT))

        assertTrue(w.renameNode("a", "b").isFailure)
        assertTrue(w.renameNode("a", "has spaces").isFailure)
        assertTrue(w.renameNode("a", "curly{}").isFailure)
        assertTrue(w.renameNode("a", "fine-one_2").isSuccess)
    }

    @Test
    fun `renaming a connector input rewrites the value, not the input name`() {
        val w =
            workflow(
                WorkflowNode("analyze", NodeType.AGENT),
                WorkflowNode("notify", NodeType.CONNECTOR, inputs = mapOf("text" to "\${analyze.result}")),
            )

        val renamed = w.renameNode("analyze", "review").getOrThrow()

        assertEquals(mapOf("text" to "\${review.result}"), renamed.node("notify")?.inputs)
    }

    @Test
    fun `removing a node takes its edges with it`() {
        val w =
            workflow(
                WorkflowNode("a", NodeType.AGENT),
                WorkflowNode("b", NodeType.AGENT),
                WorkflowNode("c", NodeType.AGENT),
                edges = listOf(WorkflowEdge("a", "b"), WorkflowEdge("b", "c"), WorkflowEdge("a", "c")),
            )

        val updated = w.removeNode("b")

        assertNull(updated.node("b"))
        assertEquals(listOf("a" to "c"), updated.edges.map { it.from to it.to })
    }

    @Test
    fun `ancestors walk the whole graph above a node, not just its direct parents`() {
        val w =
            workflow(
                WorkflowNode("analyze", NodeType.AGENT),
                WorkflowNode("fix", NodeType.AGENT),
                WorkflowNode("build", NodeType.SHELL),
                WorkflowNode("notify", NodeType.CONNECTOR),
                edges =
                    listOf(
                        WorkflowEdge("analyze", "fix"),
                        WorkflowEdge("analyze", "build"),
                        WorkflowEdge("fix", "notify"),
                        WorkflowEdge("build", "notify"),
                    ),
            )

        assertEquals(setOf("analyze", "fix", "build"), w.ancestorsOf("notify"))
        assertEquals(setOf("analyze"), w.ancestorsOf("fix"))
        assertEquals(emptySet(), w.ancestorsOf("analyze"))
    }

    @Test
    fun `a connector's declared outputs are references, and nobody else's are`() {
        val node = WorkflowNode("post", NodeType.CONNECTOR, connector = "slack-post")
        val manifest =
            ConnectorManifest(
                name = "slack-post",
                outputs = listOf(ConnectorOutputField("result"), ConnectorOutputField("permalink")),
            )

        assertEquals(listOf("result", "exitCode", "permalink"), node.outputFields(manifest))

        assertEquals(listOf("result", "exitCode"), node.outputFields(null))

        assertEquals(
            listOf("result", "sessionId", "costUsd"),
            WorkflowNode("ask", NodeType.AGENT).outputFields(manifest),
        )
    }

    @Test
    fun `a declared output schema is what an agent node's references are`() {
        val node =
            WorkflowNode(
                "review",
                NodeType.AGENT,
                schema = listOf(SchemaField("severity"), SchemaField("findings", SchemaFieldType.ARRAY)),
            )

        assertEquals(listOf("result", "sessionId", "costUsd", "severity", "findings"), node.outputFields())

        assertEquals(listOf("result", "sessionId", "costUsd"), WorkflowNode("ask", NodeType.AGENT).outputFields())
    }

    @Test
    fun `an optional field is declared nullable, since a partial required list is not a strict schema`() {
        val schema =
            listOf(
                SchemaField("severity", description = "low, medium or high"),
                SchemaField("notes", SchemaFieldType.ARRAY, required = false),
            ).toJsonSchema()

        assertEquals(
            """{"type":"object","properties":{"severity":{"type":"string","description":"low, medium or high"},""" +
                """"notes":{"type":["array","null"]}},"required":["severity","notes"],"additionalProperties":false}""",
            schema.toString(),
        )
    }

    @Test
    fun `positions are written onto the nodes they belong to, and dropped from the rest`() {
        val w =
            workflow(
                WorkflowNode("a", NodeType.AGENT, position = Position(1f, 1f)),
                WorkflowNode("b", NodeType.AGENT, position = Position(2f, 2f)),
            )

        val placed = w.withPositions(mapOf("a" to Position(40f, 120f)))

        assertEquals(Position(40f, 120f), placed.node("a")?.position)

        assertNull(placed.node("b")?.position)
    }

    @Test
    fun `dropping a repo also drops it as the default, but leaves the nodes to validation`() {
        val w =
            Workflow(
                name = "w",
                repos = listOf(RepoRef("app", "~/dev/zopf")),
                defaults = NodeDefaults(repo = "app"),
                nodes = listOf(WorkflowNode("a", NodeType.AGENT, repo = "app")),
            )

        val updated = w.removeRepo("app")

        assertTrue(updated.repos.isEmpty())
        assertNull(updated.defaults.repo)
        assertEquals("app", updated.node("a")?.repo)
    }
}
