package com.dk.zopf.cli

import com.dk.zopf.model.AgentProviderId
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.Release
import com.dk.zopf.runtime.UpdateCheck
import com.dk.zopf.runtime.Version
import com.dk.zopf.store.NodeRunRecord
import com.dk.zopf.store.RunArchive
import com.dk.zopf.store.RunRecord
import java.net.UnknownHostException
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandsTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanup() = sandbox.cleanup()

    private fun options(vararg args: String) = Options.parse(args.toList() + listOf("--workspace", sandbox.workspace.root.toString()), LIST_OPTIONS)

    @Test
    fun `list names every workflow and every file that didn't parse`() {
        sandbox.save(workflow(nodes = listOf(shell("build")), name = "smoke").copy(description = "A first line\n\nand more"))
        sandbox.workspace.workflowsDir
            .resolve("bent.yaml")
            .writeText("nodes: [")
        val streams = Streams()

        val code = listWorkflows(options(), streams.out)

        assertEquals(EXIT_OK, code)
        assertContains(streams.output(), "smoke")
        assertContains(streams.output(), "1 node · A first line")
        assertContains(streams.output(), "bent.yaml: doesn't parse")
    }

    @Test
    fun `validate is quiet about a clean workflow and exits zero`() {
        sandbox.save(workflow(nodes = listOf(shell("build"))))
        val streams = Streams()

        val code = validateWorkflows(options(), streams.out)

        assertEquals(EXIT_OK, code)
        assertContains(streams.output(), "demo: ok")
    }

    @Test
    fun `validate refuses a workflow the editor would paint red`() {
        sandbox.save(
            workflow(nodes = listOf(shell("build").copy(repo = "app"))),
        )
        val streams = Streams()

        val code = validateWorkflows(options(), streams.out)

        assertEquals(EXIT_USAGE, code)
        assertContains(streams.output(), "error:")
        assertContains(streams.output(), "isn't declared")
    }

    @Test
    fun `a warning is worth saying and not worth failing over`() {
        sandbox.save(
            workflow(nodes = listOf(shell("build"), shell("stray")), edges = emptyList()),
        )
        val streams = Streams()

        val code = validateWorkflows(options(), streams.out)

        assertEquals(EXIT_OK, code)
        assertContains(streams.output(), "warning:")
        assertContains(streams.output(), "isn't connected to anything")
    }

    @Test
    fun `a repo that has moved is what validate is for in CI`() {
        sandbox.save(
            workflow(nodes = listOf(shell("build").copy(repo = "app")))
                .copy(repos = listOf(RepoRef("app", "/nowhere/at/all"))),
        )
        val streams = Streams()

        assertEquals(EXIT_USAGE, validateWorkflows(options(), streams.out))
        assertContains(streams.output(), "/nowhere/at/all")
    }

    @Test
    fun `validate on a name that isn't there is a usage error`() {
        val streams = Streams()
        assertEquals(EXIT_USAGE, zopfIn(listOf("validate", "nope"), streams))
        assertContains(streams.errors(), "No workflow called \"nope\"")
    }

    @Test
    fun `runs reads the archive the app writes, newest first`() {
        val first = archive("older", "SUCCEEDED", cost = 0.25)
        Thread.sleep(1100)
        val second = archive("newer", "FAILED", cost = null)
        val streams = Streams()

        listRuns(Options.parse(emptyList(), RUNS_OPTIONS), streams.out, sandbox.archiveRoot)

        val lines = streams.output().trim().lines()
        assertTrue(lines.first().contains("newer"), "newest run should be first:\n${streams.output()}")
        assertContains(streams.output(), "$0.2500")
        assertContains(streams.output(), first.dir.toString())
        assertContains(streams.output(), second.dir.toString())
    }

    @Test
    fun `--last says how far back to look`() {
        repeat(3) { archive("run$it", "SUCCEEDED", cost = null) }
        val streams = Streams()

        listRuns(Options.parse(listOf("--last", "1"), RUNS_OPTIONS), streams.out, sandbox.archiveRoot)

        assertEquals(
            2,
            streams
                .output()
                .trim()
                .lines()
                .size,
            "one run is two lines: the run and where it is",
        )
    }

    private fun archive(
        workflow: String,
        status: String,
        cost: Double?,
    ): RunArchive =
        RunArchive.create(workflow, "ws", sandbox.archiveRoot).also {
            it.write(
                RunRecord(
                    id = workflow,
                    workflow = workflow,
                    startedAt = "2026-08-11T09:00:00Z",
                    status = status,
                    nodes =
                        listOf(
                            NodeRunRecord(
                                nodeId = "build",
                                type = NodeType.SHELL,
                                status = status,
                                startedAt = "2026-08-11T09:00:00Z",
                                costUsd = cost,
                            ),
                        ),
                ),
            )
        }

    private fun zopfIn(
        args: List<String>,
        streams: Streams,
    ): Int = zopf(args + listOf("--workspace", sandbox.workspace.root.toString()), streams.out, streams.err)

    @Test
    fun `prune keeps the newest and deletes the rest`() {
        repeat(5) { index ->
            archive("run-$index", "SUCCEEDED", null)
            Thread.sleep(5)
        }
        val streams = Streams()

        pruneRuns(Options.parse(listOf("--keep", "2"), PRUNE_OPTIONS, PRUNE_SWITCHES), streams.out, sandbox.archiveRoot)

        assertEquals(2, RunArchive.all(sandbox.archiveRoot).size)
        assertContains(streams.output(), "3 run(s) gone")
    }

    @Test
    fun `prune --dry-run deletes nothing`() {
        repeat(3) { archive("run-$it", "SUCCEEDED", null) }
        val streams = Streams()

        pruneRuns(Options.parse(listOf("--keep", "1", "--dry-run"), PRUNE_OPTIONS, PRUNE_SWITCHES), streams.out, sandbox.archiveRoot)

        assertEquals(3, RunArchive.all(sandbox.archiveRoot).size)
        assertContains(streams.output(), "would delete")
    }

    @Test
    fun `prune leaves a run that isn't old enough`() {
        repeat(3) { archive("run-$it", "SUCCEEDED", null) }

        pruneRuns(
            Options.parse(listOf("--keep", "0", "--older-than", "7"), PRUNE_OPTIONS, PRUNE_SWITCHES),
            Streams().out,
            sandbox.archiveRoot,
        )

        assertEquals(3, RunArchive.all(sandbox.archiveRoot).size)
    }

    @Test
    fun `a dry run prints the order and starts nothing`() {
        sandbox.save(
            Workflow(
                name = "shape",
                nodes =
                    listOf(
                        WorkflowNode("a", NodeType.SHELL, command = "echo a"),
                        WorkflowNode("b", NodeType.SHELL, command = "echo b"),
                        WorkflowNode("c", NodeType.SHELL, command = "echo c"),
                    ),
                edges = listOf(WorkflowEdge("a", "c"), WorkflowEdge("b", "c")),
            ),
        )
        val streams = Streams()

        val code = zopfIn(listOf("run", "shape", "--dry-run"), streams)

        assertEquals(EXIT_OK, code)
        assertContains(streams.output(), "1. a, b")
        assertContains(streams.output(), "2. c")
        assertEquals(0, RunArchive.all(sandbox.archiveRoot).size)
    }

    @Test
    fun `version prints the build it came from`() {
        val streams = Streams()

        assertEquals(EXIT_OK, zopf(listOf("--version"), streams.out, streams.err))
        assertContains(streams.output(), "zopf ")
    }

    @Test
    fun `version mentions a newer release on stderr, so stdout stays greppable`() {
        val cache = sandbox.dir().resolve("update.json")
        updateCheck("9.9.9", cache).fetch()
        val streams = Streams()

        printVersion(streams.out, streams.err, check = updateCheck(null, cache), notify = true)

        assertContains(streams.output(), "zopf ")
        assertFalse(streams.output().contains("9.9.9"), "the notice belongs on stderr")
        assertContains(streams.errors(), "zopf 9.9.9 is out")
        assertContains(streams.errors(), "https://example.test/9.9.9")
    }

    @Test
    fun `version says nothing extra when no check has ever run`() {
        val streams = Streams()

        printVersion(streams.out, streams.err, check = updateCheck(latest = null), notify = true)

        assertEquals("", streams.errors())
    }

    @Test
    fun `a redirect or a CI job gets the version and no notice`() {
        val streams = Streams()

        printVersion(streams.out, streams.err, check = updateCheck("9.9.9"), notify = false)

        assertEquals("", streams.errors())
    }

    @Test
    fun `check-update names the newer release and where to get it`() {
        val streams = Streams()

        val code = checkForUpdate(streams.out, streams.err, updateCheck("9.9.9"))

        assertEquals(EXIT_OK, code)
        assertContains(streams.output(), "zopf 9.9.9 is out")
        assertContains(streams.output(), "https://example.test/9.9.9")
    }

    @Test
    fun `check-update on the latest build says so and still exits zero`() {
        val streams = Streams()

        assertEquals(EXIT_OK, checkForUpdate(streams.out, streams.err, updateCheck("0.0.1")))
        assertContains(streams.output(), "is the latest release")
    }

    @Test
    fun `a check that cannot reach GitHub is a failure, not a verdict about zopf`() {
        val streams = Streams()

        val code = checkForUpdate(streams.out, streams.err, updateCheck(latest = null))

        assertEquals(EXIT_FAILED, code)
        assertContains(streams.errors(), "couldn't ask GitHub")
    }

    private fun updateCheck(
        latest: String?,
        file: Path = sandbox.dir().resolve("update.json"),
    ) = UpdateCheck(
        source = {
            latest
                ?.let { Result.success(Release(Version.parse(it)!!, "https://example.test/$it")) }
                ?: Result.failure(UnknownHostException("api.github.com"))
        },
        file = file,
        current = "0.0.1",
    )
}

class RunCommandTest {
    private val sandbox = Sandbox()

    @AfterTest
    fun cleanup() = sandbox.cleanup()

    @Test
    fun `a workflow that runs through exits zero`() {
        sandbox.save(workflow(nodes = listOf(shell("build"), shell("test")), edges = listOf("build" to "test")))
        val executor = FakeExecutor()

        val (code, streams) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("build", "test"), executor.started)
        assertContains(streams.output(), "demo · Done")
    }

    @Test
    fun `run refuses what validate refuses, instead of failing halfway through the graph`() {
        sandbox.save(workflow(nodes = listOf(shell("build").copy(repo = "app"), shell("test")), edges = listOf("build" to "test")))
        val executor = FakeExecutor()

        val (code, streams) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_USAGE, code)
        assertEquals(emptyList(), executor.started)
        assertContains(streams.errors(), "isn't declared")
        assertEquals(0, RunArchive.all(sandbox.archiveRoot).size)
    }

    @Test
    fun `the refusal goes to stderr, so --format json stays parseable`() {
        sandbox.save(workflow(nodes = listOf(shell("build").copy(repo = "app"))))

        val (_, streams) = sandbox.run(listOf("demo", "--format", "json"))

        assertEquals("", streams.output())
        assertContains(streams.errors(), "didn't start")
    }

    @Test
    fun `a warning is not a reason to refuse a run`() {
        sandbox.save(workflow(nodes = listOf(shell("build"), shell("stray"))))
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("build", "stray"), executor.started.sorted())
    }

    @Test
    fun `a failed node exits one, and says which`() {
        sandbox.save(workflow(nodes = listOf(shell("build"), shell("ship")), edges = listOf("build" to "ship")))

        val (code, streams) = sandbox.run(listOf("demo"), FakeExecutor(fail = setOf("build")))

        assertEquals(EXIT_FAILED, code)
        assertContains(streams.output(), "build: failed")
        assertContains(streams.output(), "ship: skipped")
    }

    @Test
    fun `a gate with nobody to answer it stops the run, and names the flag that would decide`() {
        sandbox.save(workflow(nodes = listOf(gate("approve"), shell("ship")), edges = listOf("approve" to "ship")))
        val executor = FakeExecutor()

        val (code, streams) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_STOPPED, code)
        assertTrue(executor.started.isEmpty(), "nothing downstream of a rejected gate may run")
        assertContains(streams.output(), "--on-gate approve")
    }

    @Test
    fun `--on-gate approve lets the rest through`() {
        sandbox.save(workflow(nodes = listOf(gate("approve"), shell("ship")), edges = listOf("approve" to "ship")))
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo", "--on-gate", "approve"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("ship"), executor.started)
    }

    @Test
    fun `--on-gate reject is a decision, not a fault`() {
        sandbox.save(workflow(nodes = listOf(gate("approve"), shell("ship")), edges = listOf("approve" to "ship")))

        val (code, streams) = sandbox.run(listOf("demo", "--on-gate", "reject"))

        assertEquals(EXIT_STOPPED, code)
        assertContains(streams.output(), "Rejected by --on-gate reject")
    }

    @Test
    fun `--answer feeds an input node, and what it says reaches the next one`() {
        sandbox.save(
            workflow(
                nodes = listOf(input("ask"), agent("review").copy(prompt = "review \${ask.result}")),
                edges = listOf("ask" to "review"),
            ),
        )
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo", "--answer", "ask=release-2"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("review"), executor.started)
    }

    @Test
    fun `an input with a default takes it when nothing answers`() {
        sandbox.save(workflow(nodes = listOf(input("ask", default = "main"), shell("build")), edges = listOf("ask" to "build")))
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("build"), executor.started)
    }

    @Test
    fun `an unanswered input with no default stops the run rather than guessing`() {
        sandbox.save(workflow(nodes = listOf(input("ask"), shell("build")), edges = listOf("ask" to "build")))
        val executor = FakeExecutor()

        val (code, streams) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_STOPPED, code)
        assertTrue(executor.started.isEmpty())
        assertContains(streams.output(), "--answer ask=")
    }

    @Test
    fun `an answer the node could never have offered is refused before anything runs`() {
        sandbox.save(workflow(nodes = listOf(input("ask", choices = listOf("staging", "prod")))))
        val executor = FakeExecutor()

        val failure = assertFailsWith<UsageError> { sandbox.run(listOf("demo", "--answer", "ask=moon"), executor) }

        assertContains(failure.message!!, "staging, prod")
        assertTrue(executor.started.isEmpty())
    }

    @Test
    fun `an answer for a node that isn't an input is refused too`() {
        sandbox.save(workflow(nodes = listOf(shell("build"))))

        val failure = assertFailsWith<UsageError> { sandbox.run(listOf("demo", "--answer", "build=yes")) }
        assertContains(failure.message!!, "not an input")

        val missing = assertFailsWith<UsageError> { sandbox.run(listOf("demo", "--answer", "nope=yes")) }
        assertContains(missing.message!!, "no node called")
    }

    @Test
    fun `--repo points a declared repo somewhere else for this run`() {
        val elsewhere = sandbox.dir()
        sandbox.save(
            workflow(nodes = listOf(shell("build").copy(repo = "app")))
                .copy(repos = listOf(RepoRef("app", sandbox.dir().toString()))),
        )
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo", "--repo", "app=$elsewhere"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf<Path?>(elsewhere), executor.directories.toList())
    }

    @Test
    fun `--model is the default for nodes that don't name one, and never overrules one that does`() {
        sandbox.save(
            workflow(
                nodes = listOf(agent("plan"), agent("fix").copy(model = "opus")),
                edges = listOf("plan" to "fix"),
            ),
        )
        val executor = FakeExecutor()

        assertEquals(EXIT_OK, sandbox.run(listOf("demo", "--model", "haiku"), executor).first)

        assertEquals(listOf<String?>("haiku", "opus"), executor.models.toList())
    }

    @Test
    fun `--provider is the same kind of default, and a node that named one keeps it`() {
        sandbox.save(
            workflow(
                nodes = listOf(agent("plan"), agent("fix").copy(provider = AgentProviderId.CLAUDE)),
                edges = listOf("plan" to "fix"),
            ),
        )
        val executor = FakeExecutor()

        assertEquals(EXIT_OK, sandbox.run(listOf("demo", "--provider", "codex"), executor).first)

        assertEquals(listOf(AgentProviderId.CODEX, AgentProviderId.CLAUDE), executor.providers.toList())
    }

    @Test
    fun `a CLI zopf cannot drive is refused before anything starts`() {
        sandbox.save(workflow(nodes = listOf(agent("plan"))))
        val executor = FakeExecutor()

        val failure = runCatching { sandbox.run(listOf("demo", "--provider", "gemini"), executor) }.exceptionOrNull()

        assertTrue(failure is UsageError, failure.toString())
        assertTrue("claude" in failure.message.orEmpty() && "codex" in failure.message.orEmpty(), failure.message.orEmpty())
        assertEquals(emptyList(), executor.started.toList())
    }

    @Test
    fun `a workflow name that isn't there is a usage error, not a run`() {
        sandbox.save(workflow(nodes = listOf(shell("build"))))

        val failure = assertFailsWith<UsageError> { sandbox.run(listOf("nope")) }
        assertContains(failure.message!!, "No workflow called \"nope\"")
    }

    @Test
    fun `--format json prints the archive's own lines and nothing of its own`() {
        sandbox.save(workflow(nodes = listOf(shell("build"))))

        val (code, streams) = sandbox.run(listOf("demo", "--format", "json"), FakeExecutor(output = { """{"a":1}""" }))

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("""{"a":1}"""), streams.output().trim().lines())
    }

    @Test
    fun `--format quiet says only how it ended`() {
        sandbox.save(workflow(nodes = listOf(shell("build"))))

        val (_, streams) = sandbox.run(listOf("demo", "--format", "quiet"), FakeExecutor())

        val lines = streams.output().trim().lines()
        assertEquals(1, lines.size, "quiet printed more than the verdict:\n${streams.output()}")
        assertContains(lines.single(), "demo · Done")
    }

    @Test
    fun `a branch that skips half the graph still exits zero`() {
        val branching =
            workflow(nodes = listOf(shell("build"), WorkflowNode("ok", NodeType.BRANCH, expression = "true"), shell("ship"), shell("report")))
                .copy(
                    edges =
                        listOf(
                            WorkflowEdge("build", "ok"),
                            WorkflowEdge("ok", "ship", condition = true),
                            WorkflowEdge("ok", "report", condition = false),
                        ),
                )
        sandbox.save(branching)
        val executor = FakeExecutor()

        val (code, _) = sandbox.run(listOf("demo"), executor)

        assertEquals(EXIT_OK, code)
        assertEquals(listOf("build", "ship"), executor.started)
    }
}
