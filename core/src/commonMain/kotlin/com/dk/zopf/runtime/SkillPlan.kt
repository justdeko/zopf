package com.dk.zopf.runtime

import com.dk.zopf.store.DiscoveredSkill
import com.dk.zopf.store.Prompts
import com.dk.zopf.store.homeDir
import com.dk.zopf.store.skillMustBeNamed
import java.nio.file.Path

data class SelectedSkill(
    val name: String,
    val dir: Path,
    val source: String,
    val ambient: Boolean,
    val mustBeNamed: Boolean,
)

data class SkillNotice(
    val text: String,
    val isWarning: Boolean,
)

class SkillPlan(
    val selected: List<SelectedSkill>,
    val unresolved: List<String>,
) {
    val pluginDirs: List<Path> = selected.filterNot { it.ambient }.map { it.dir }

    fun withInvocation(prompt: String): String {
        val preamble = preamble(prompt) ?: return prompt
        return if (prompt.isBlank()) preamble else "$preamble\n\n$prompt"
    }

    val notices: List<SkillNotice>
        get() =
            buildList {
                selected.filterNot { it.ambient }.takeIf { it.isNotEmpty() }?.let { passed ->
                    add(
                        SkillNotice(
                            "Skills: " + passed.joinToString { "${it.name} (${it.source})" } +
                                ", one --plugin-dir each",
                            isWarning = false,
                        ),
                    )
                }
                selected.filter { it.ambient }.takeIf { it.isNotEmpty() }?.let { ambient ->
                    add(
                        SkillNotice(
                            "Already loaded by this session, so passed no flag: " +
                                ambient.joinToString { it.name },
                            isWarning = false,
                        ),
                    )
                }
                if (unresolved.isNotEmpty()) {
                    add(
                        SkillNotice(
                            "No skill directory found for ${unresolved.joinToString()}. " +
                                "Declare it in the workflow's settings, or the name goes nowhere.",
                            isWarning = true,
                        ),
                    )
                }

                val forced = selected.filter { it.mustBeNamed }
                forced.firstOrNull()?.let {
                    add(
                        SkillNotice(
                            "${it.name} sets disable-model-invocation, so it is invoked as /${it.name} " +
                                "and the prompt goes to it as arguments",
                            isWarning = false,
                        ),
                    )
                }
                forced.drop(1).takeIf { it.isNotEmpty() }?.let { rest ->
                    add(
                        SkillNotice(
                            "Only one command is expanded per message, so " +
                                rest.joinToString { it.name } + " won't fire. " +
                                "Give each one its own node.",
                            isWarning = true,
                        ),
                    )
                }
            }

    private fun preamble(prompt: String): String? {
        val unnamed = selected.filterNot { it.isNamedIn(prompt) }
        if (unnamed.isEmpty()) return null

        val forced = unnamed.firstOrNull { it.mustBeNamed }
        val asked = unnamed.filter { it != forced }
        return buildList {
            forced?.let { add("/${it.name}") }
            if (asked.isNotEmpty()) add(useSentence(asked.map { it.name }))
        }.joinToString("\n")
    }
}

fun planSkills(
    names: List<String>,
    available: List<DiscoveredSkill>,
    cwd: Path,
): SkillPlan {
    val byName = available.associateBy { it.name }
    val selected =
        names.mapNotNull { name ->
            byName[name]?.let {
                SelectedSkill(
                    name = it.name,
                    dir = it.path,
                    source = it.source,
                    ambient = it.path.startsWith(claudeSkillsIn(cwd)) || it.path.startsWith(personalSkills),
                    mustBeNamed = skillMustBeNamed(it.path),
                )
            }
        }
    return SkillPlan(selected, names.filterNot { it in byName })
}

private fun claudeSkillsIn(cwd: Path): Path = cwd.resolve(".claude").resolve("skills")

private val personalSkills: Path get() = homeDir().resolve(".claude/skills")

private fun SelectedSkill.isNamedIn(prompt: String): Boolean = Regex("(^|[^A-Za-z0-9_-])${Regex.escape(name)}([^A-Za-z0-9_-]|$)").containsMatchIn(prompt)

private fun useSentence(names: List<String>): String =
    when (names.size) {
        1 -> Prompts.render("skill-use-one", "skill" to names.single())
        else ->
            Prompts.render(
                "skill-use-many",
                "skills" to names.dropLast(1).joinToString(", ") + " and " + names.last(),
            )
    }
