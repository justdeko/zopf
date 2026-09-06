package com.dk.zopf.store

import com.dk.zopf.model.NodeDefaults
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowEdge
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.runtime.ProcessNodeExecutor
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.WorkflowEngine
import com.dk.zopf.runtime.WorkflowRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkspaceTest {
    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-test").also { tempDirs.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir ->
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a path resolves against home, the workspace root, or itself`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val home = Paths.get(System.getProperty("user.home"))
        val root = workspace.root

        listOf(
            "/usr/local/bin" to Paths.get("/usr/local/bin"),
            "~/dev/kuiver" to home.resolve("dev/kuiver"),
            "~" to home,
            "sub/repo" to root.resolve("sub/repo").normalize(),
            ".." to root.parent.normalize(),
        ).forEach { (raw, expected) -> assertEquals(expected, workspace.resolvePath(raw), raw) }
    }

    @Test
    fun `a workspace inside a git repo exposes it as self`() {
        val repo = tempDir().resolve("myrepo")
        repo.resolve(".git").createDirectories()
        val workspace = Workspace.create(repo.resolve(".zopf"))

        assertEquals(repo.normalize(), workspace.selfRepo?.normalize())

        val workflow = Workflow(name = "w")
        assertEquals(repo.normalize(), workspace.resolveRepo(workflow, SELF_REPO_ID)?.normalize())
        assertTrue(SELF_REPO_ID in workspace.availableRepoIds(workflow))
    }

    @Test
    fun `a workspace outside a git repo has no self repo`() {
        val workspace = Workspace.create(tempDir().resolve("standalone"))
        assertNull(workspace.selfRepo)
        assertNull(workspace.resolveRepo(Workflow(name = "w"), SELF_REPO_ID))
    }

    @Test
    fun `an explicit self entry overrides the enclosing repo`() {
        val repo = tempDir().resolve("myrepo")
        repo.resolve(".git").createDirectories()
        val workspace = Workspace.create(repo.resolve(".zopf"))
        val workflow =
            Workflow(
                name = "w",
                repos = listOf(RepoRef(SELF_REPO_ID, "/somewhere/else")),
            )
        assertEquals(Paths.get("/somewhere/else"), workspace.resolveRepo(workflow, SELF_REPO_ID))
    }

    @Test
    fun `resolveRepo falls back to the default and returns null for unknown ids`() {
        val workspace = Workspace.create(tempDir().resolve("ws"))
        val workflow =
            Workflow(
                name = "w",
                repos = listOf(RepoRef("app", "~/dev/zopf")),
                defaults = NodeDefaults(repo = "app"),
            )
        val home = Paths.get(System.getProperty("user.home"))

        assertEquals(home.resolve("dev/zopf"), workspace.resolveRepo(workflow, null))
        assertEquals(home.resolve("dev/zopf"), workspace.resolveRepo(workflow, "app"))
        assertNull(workspace.resolveRepo(workflow, "nope"))
    }

    @Test
    fun `opening a directory finds a dot-zopf inside it`() {
        val repo = tempDir().resolve("myrepo")
        Workspace.create(repo.resolve(".zopf"))

        val opened = Workspace.open(repo)
        assertNotNull(opened)
        assertEquals(".zopf", opened.root.name)

        assertEquals("myrepo", opened.name)
    }

    @Test
    fun `opening a plain directory returns null`() {
        assertNull(Workspace.open(tempDir().resolve("nothing-here")))
    }

    @Test
    fun `a dot-zopf directory is a workspace`() {
        val repo = tempDir().resolve("myrepo")
        repo.resolve(".zopf/workflows").createDirectories()

        val opened = Workspace.open(repo)
        assertNotNull(opened)
        assertEquals(".zopf", opened.root.name)
        assertEquals("myrepo", opened.name)
        assertFalse(opened.root.resolve(WORKSPACE_FILE).exists())
    }

    @Test
    fun `a zopf yaml alone does not make a workspace`() {
        val dir = tempDir().resolve("looks-the-part")
        dir.resolve("workflows").createDirectories()
        dir.resolve(WORKSPACE_FILE).writeText("name: nope\n")

        assertNull(Workspace.open(dir))
    }

    @Test
    fun `creating a workspace makes dot-zopf and writes no file`() {
        val repo = tempDir().resolve("myrepo")
        repo.createDirectories()

        val created = Workspace.create(repo)

        assertEquals(repo.resolve(".zopf").normalize(), created.root)
        assertFalse(created.root.resolve(WORKSPACE_FILE).exists())
        assertFalse(repo.resolve(WORKSPACE_FILE).exists())
        assertTrue(created.workflowsDir.isDirectory())
    }

    @Test
    fun `creating over an existing workspace returns it untouched`() {
        val repo = tempDir().resolve("myrepo")
        val existing = repo.resolve(".zopf/workflows").createDirectories().parent
        existing.resolve(WORKSPACE_FILE).writeText("name: My workflows\n")

        val created = Workspace.create(repo)

        assertEquals(existing.normalize(), created.root)
        assertEquals("My workflows", created.name)
    }

    @Test
    fun `creating inside dot-zopf does not nest another`() {
        val created = Workspace.create(tempDir().resolve("myrepo/.zopf"))

        assertEquals(".zopf", created.root.name)
        assertFalse(created.root.resolve(WORKSPACE_DIR).exists())
    }

    @Test
    fun `the default workspace in home is called zopf`() {
        assertEquals(".zopf", AppPaths.defaultWorkspace.name)
        assertEquals(
            "zopf",
            Workspace(AppPaths.defaultWorkspace, WorkspaceConfig()).name,
        )
    }
}

class WorkspacePortabilityTest {
    private val dirs = mutableListOf<Path>()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-portability").also { dirs.add(it) }

    @AfterTest
    fun cleanup() {
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `a workflow in a committed workspace runs in the checkout`() {
        val repo = gitRepo()
        val workspace = Workspace.create(repo.resolve(".zopf"))

        val run = runWorkflow(workspace, selfWorkflow("pwd"))

        assertEquals(RunStatus.SUCCEEDED, run.status)
        val cwd =
            Path.of(
                run.nodes
                    .first()
                    .output()
                    .result,
            )
        assertEquals(repo.toRealPath(), cwd.toRealPath())
    }

    @Test
    fun `running a workflow leaves git status clean`() {
        val repo = gitRepo()
        val workspace = Workspace.create(repo.resolve(".zopf"))
        val workflow = selfWorkflow("echo one", second = "echo two")
        WorkflowStore(workspace).save(workflow)

        val run = runWorkflow(workspace, workflow)
        assertEquals(RunStatus.SUCCEEDED, run.status)

        val dirty = git(repo, "status", "--porcelain").lines().filter { it.isNotBlank() }
        assertTrue(
            dirty.all { it.substringAfter(' ').trim().startsWith(".zopf/") },
            "a run dirtied something outside the workspace: $dirty",
        )
        assertTrue(dirty.isNotEmpty(), "the workspace itself should be showing up as untracked")
    }

    @Test
    fun `machine-local state lives under Application Support`() {
        listOf(AppPaths.runsDir, AppPaths.settingsFile, AppPaths.workspacesFile).forEach {
            assertTrue(it.startsWith(AppPaths.appSupport), "$it escaped Application Support")
        }
    }

    private fun selfWorkflow(
        first: String,
        second: String? = null,
    ): Workflow {
        val nodes =
            listOfNotNull(
                WorkflowNode(id = "a", type = NodeType.SHELL, command = first, repo = SELF_REPO_ID),
                second?.let { WorkflowNode(id = "b", type = NodeType.SHELL, command = it, repo = SELF_REPO_ID) },
            )
        return Workflow(
            name = "portable",
            nodes = nodes,
            edges = if (nodes.size > 1) listOf(WorkflowEdge(from = "a", to = "b")) else emptyList(),
        )
    }

    private fun runWorkflow(
        workspace: Workspace,
        workflow: Workflow,
    ): WorkflowRun =
        runBlocking {
            val scope = CoroutineScope(Job() + Dispatchers.Default).also { scopes.add(it) }

            val engine = WorkflowEngine(scope, ProcessNodeExecutor(), tempDir())
            val run = engine.start(workspace, workflow).getOrThrow()
            withTimeout(30.seconds) { run.job?.join() }
            run
        }

    private fun gitRepo(): Path {
        val repo = tempDir()
        repo.resolve("README.md").writeText("a repo that happens to hold a workspace\n")
        git(repo, "init", "--quiet")
        git(repo, "add", "-A")
        git(repo, "commit", "--quiet", "-m", "init")
        return repo
    }

    private fun git(
        repo: Path,
        vararg args: String,
    ): String {
        val identity =
            listOf(
                "-c",
                "user.name=zopf test",
                "-c",
                "user.email=test@example.invalid",
                "-c",
                "commit.gpgsign=false",
            )
        val process =
            ProcessBuilder(listOf("git") + identity + args)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
        return output
    }
}
