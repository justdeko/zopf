package com.dk.zopf.runtime

import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.SKILL_MANIFEST
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.availableSkills
import com.dk.zopf.store.isSkillDir
import com.dk.zopf.store.skillMustBeNamed
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillPlanTest {
    private fun skill(
        name: String,
        ambient: Boolean = false,
        mustBeNamed: Boolean = false,
    ) = SelectedSkill(name, Paths.get("/skills/$name"), "app", ambient, mustBeNamed)

    private fun plan(
        vararg skills: SelectedSkill,
        unresolved: List<String> = emptyList(),
    ) = SkillPlan(skills.toList(), unresolved)

    @Test
    fun `nothing selected changes nothing about the prompt`() {
        assertEquals("Review the diff.", plan().withInvocation("Review the diff."))
        assertTrue(plan().pluginDirs.isEmpty())
        assertTrue(plan().notices.isEmpty())
    }

    @Test
    fun `one skill is asked for by name`() {
        val prompt = plan(skill("code-review")).withInvocation("Review the diff.")
        assertEquals("Use the code-review skill.\n\nReview the diff.", prompt)
    }

    @Test
    fun `several are asked for in one sentence`() {
        val prompt = plan(skill("a"), skill("b"), skill("c")).withInvocation("Go.")
        assertEquals("Use the a, b and c skills.\n\nGo.", prompt)
    }

    @Test
    fun `a skill the prompt already mentions is left alone`() {
        val prompt =
            plan(skill("code-review"), skill("triage"))
                .withInvocation("Run /code-review on the diff.")
        assertEquals("Use the triage skill.\n\nRun /code-review on the diff.", prompt)

        val untouched = plan(skill("code-review")).withInvocation("apply code-review to this")
        assertEquals("apply code-review to this", untouched)
    }

    @Test
    fun `a longer word that merely starts with a skill's name does not count as naming it`() {
        val prompt = plan(skill("review")).withInvocation("Write a reviewer guide.")
        assertEquals("Use the review skill.\n\nWrite a reviewer guide.", prompt)
    }

    @Test
    fun `a skill that cannot be model-invoked gets the one command slot`() {
        val prompt = plan(skill("release", mustBeNamed = true), skill("triage")).withInvocation("Ship it.")
        assertEquals("/release\nUse the triage skill.\n\nShip it.", prompt)
    }

    @Test
    fun `only one forced skill can fire, and the console says which`() {
        val notices =
            plan(
                skill("release", mustBeNamed = true),
                skill("announce", mustBeNamed = true),
            ).notices

        assertTrue(notices.any { "/release" in it.text })
        assertTrue(notices.any { "announce" in it.text && it.isWarning })
    }

    @Test
    fun `an ambient skill is named but not passed`() {
        val plan = plan(skill("here", ambient = true), skill("elsewhere"))

        assertEquals(listOf(Paths.get("/skills/elsewhere")), plan.pluginDirs)
        assertEquals("Use the here and elsewhere skills.\n\nGo.", plan.withInvocation("Go."))
        assertTrue(plan.notices.any { "here" in it.text && !it.isWarning })
    }

    @Test
    fun `a name nothing resolves is a warning rather than silence`() {
        val notices = plan(unresolved = listOf("ghost")).notices
        assertEquals(1, notices.size)
        assertTrue(notices.single().isWarning)
        assertTrue("ghost" in notices.single().text)
    }

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-plan").also { tempDirs.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun onDisk(
        root: Path,
        name: String,
        frontmatter: String = "",
    ): Path =
        root.resolve(name).also {
            it.createDirectories()
            it.resolve("SKILL.md").writeText("---\nname: $name\n$frontmatter---\n\nDo it.\n")
        }

    @Test
    fun `a skill under the node's own claude directory needs no flag`() {
        val repo = tempDir()
        val own = onDisk(repo.resolve(".claude/skills").also { it.createDirectories() }, "local")
        val shared = onDisk(tempDir(), "external")

        val plan =
            planSkills(
                names = listOf("local", "external"),
                available =
                    listOf(
                        DiscoveredSkill("local", own, "self"),
                        DiscoveredSkill("external", shared, "workflow"),
                    ),
                cwd = repo,
            )

        assertEquals(listOf(shared), plan.pluginDirs)
        assertTrue(plan.selected.first { it.name == "local" }.ambient)
        assertFalse(plan.selected.first { it.name == "external" }.ambient)
    }

    @Test
    fun `resolution reads the frontmatter and reports names it could not find`() {
        val root = tempDir()
        val forced = onDisk(root, "release", frontmatter = "disable-model-invocation: true\n")

        val plan =
            planSkills(
                names = listOf("release", "ghost"),
                available = listOf(DiscoveredSkill("release", forced, "workflow")),
                cwd = tempDir(),
            )

        assertTrue(plan.selected.single().mustBeNamed)
        assertEquals(listOf("ghost"), plan.unresolved)
    }
}

class DiscoveryTest {
    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(): Path = Files.createTempDirectory("zopf-skills").also { tempDirs.add(it) }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun skill(
        root: Path,
        name: String,
        frontmatter: String = "",
    ): Path =
        root.resolve(name).also {
            it.createDirectories()
            it.resolve(SKILL_MANIFEST).writeText("---\nname: $name\n$frontmatter---\n\nDo the thing.\n")
        }

    @Test
    fun `a directory without a SKILL_md is not a skill`() {
        val root = tempDir()
        val real = skill(root, "review")
        val notASkill = root.resolve("notes").also { it.createDirectories() }

        assertTrue(isSkillDir(real))
        assertFalse(isSkillDir(notASkill))
        assertFalse(isSkillDir(root.resolve("nothing-here")))
    }

    @Test
    fun `the workspace's own skills are found, and a repo's shadow them`() {
        val root = tempDir()
        val workspace = Workspace.create(root.resolve("ws"))
        val repo = root.resolve("app").also { it.resolve(".claude/skills").createDirectories() }
        skill(workspace.root.resolve("skills").also { it.createDirectories() }, "review")
        skill(workspace.root.resolve("skills"), "triage")
        val repoVersion = skill(repo.resolve(".claude/skills"), "review")

        val found = availableSkills(workspace, Workflow(name = "w", repos = declaring(repo)))

        assertEquals(listOf("review", "triage"), found.map { it.name })

        assertEquals(repoVersion, found.first { it.name == "review" }.path)
        assertEquals("app", found.first { it.name == "review" }.source)
    }

    @Test
    fun `a directory the workflow declares by path wins over a discovered one`() {
        val root = tempDir()
        val workspace = Workspace.create(root.resolve("ws"))
        skill(workspace.root.resolve("skills").also { it.createDirectories() }, "review")
        val elsewhere = skill(root.resolve("elsewhere").also { it.createDirectories() }, "review")

        val found =
            availableSkills(
                workspace,
                Workflow(name = "w", skills = listOf(elsewhere.toString())),
            )

        assertEquals(1, found.size)
        assertEquals(elsewhere, found.single().path)
        assertEquals("workflow", found.single().source)
    }

    @Test
    fun `disable-model-invocation is read out of the frontmatter`() {
        val root = tempDir()
        val ordinary = skill(root, "review")
        val forced = skill(root, "release", frontmatter = "disable-model-invocation: true\n")
        val explicitlyFalse = skill(root, "triage", frontmatter = "disable-model-invocation: false\n")

        assertTrue(skillMustBeNamed(forced))
        assertFalse(skillMustBeNamed(ordinary))
        assertFalse(skillMustBeNamed(explicitlyFalse))
        assertFalse(skillMustBeNamed(root.resolve("not-a-skill")))
    }

    private fun declaring(path: Path) = listOf(RepoRef(id = "app", path = path.toString()))
}
