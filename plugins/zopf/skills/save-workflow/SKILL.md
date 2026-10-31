---
name: save-workflow
description: 'Turn the current Claude Code conversation into a zopf workflow that reruns the same job: the commands that mattered become shell nodes, the judgment becomes agent nodes, the places the user stepped in become gates and inputs. Use this when the user asks to save, capture or replay what was just done as a zopf workflow, says "make this a workflow", "turn this session into a workflow", "I want to rerun this", or invokes /zopf:save-workflow.'
argument-hint: "[name]"
allowed-tools: Read, Glob, Bash(bash *save-workflow/scripts/digest.sh*), Bash(zopf validate *), Bash(zopf run * --dry-run)
---

# Save this session as a zopf workflow

The user wants the job this conversation did, captured as a zopf workflow they can rerun next time without
talking it through again. Not a log of the session: a graph that does the same work on a fresh day, with the
detours removed.

## What happened in this session

This is a digest of the transcript on disk, so it includes steps that were compacted out of your context. `USER:`
is what the user typed, `CLAUDE:` the start of what you said, indented lines are tool calls, and `FAILED:` follows a
call that errored.

!`bash "${CLAUDE_SKILL_DIR}/scripts/digest.sh" "${CLAUDE_SESSION_ID}"`

## Before you write

Read `${CLAUDE_PLUGIN_ROOT}/skills/zopf-workflows/SKILL.md` and follow it: it is the format, the canonical form the
editor writes, and the rules for finding the workspace. Everything below is only about turning a session into a
graph.

The workflow's name is `$ARGUMENTS` if one was given. Otherwise pick a short slug for the job itself
(`bump-deps`, `triage-flaky-test`), never for this one occurrence of it (`fix-issue-412`).

## From session to graph

Find the job first. Say to yourself in one sentence what the user would ask for next time ("update the
dependencies, fix what breaks, and let me look before it commits"). Every node has to serve that sentence. Then map
what the session did:

- **A command that checked the work** (tests, lint, type check, build) and was rerun until it passed becomes a
  `shell` node with an `on: failure` edge to an `agent` node that fixes what it reported. Use the exact command from
  the session; it is the one that actually works in this repo.
- **A command that gathered context** (`git diff`, `git log`, `gh pr view`, a curl) becomes a `shell` node feeding an
  agent's prompt through `${id.result}`, rather than a `Bash` grant on the agent.
- **Your own reasoning and edits** become `agent` nodes. Write each prompt from what the session learned: name the
  files, the conventions and the pitfalls you hit, so next time's agent starts where this one ended. One agent node
  per distinct piece of judgment, not one per turn.
- **Where the user stopped you, corrected you, or said "yes, go ahead"** before something mattered, put a `gate`
  in front of that step next time. Quote what they will be approving into its `prompt:`.
- **Values that belonged to this occasion** (an issue number, a branch, a version, a file the user named) become
  `input` nodes, referenced as `${id.result}`, so the workflow is reusable. Use `choices:` when the session shows a
  small fixed set.
- **Work that didn't depend on each other**, such as independent subagents or the same change in several repos,
  becomes parallel nodes with no edge between them, joined by whatever consumes their results.
- **Leave out** dead ends, exploration that only taught you something (put the lesson in a prompt instead), commands
  that failed for reasons unrelated to the job, and anything the user undid.
- **Repos**: the directory the session started in is `self` when the workspace is the `.zopf/` inside it. Any other
  directory the session worked in gets its own `repos:` entry with an absolute path.

If the session was a single command with no judgment in it, the zopf-workflows skill's rule applies: say it is
better as a script, and only write the workflow if the user still wants it.

## Finish

1. Write the file to `<workspace>/workflows/<name>.yaml`. If a workflow with that name exists, pick another name or
   ask; never overwrite one.
2. Run `zopf validate <name>` and fix every error, then `zopf run <name> --dry-run` to check the waves match the
   order the session worked in. Don't run it for real.
3. Reply with the path, then a short list of what you turned into what (which step became a gate and why, which
   values are now inputs), and what you dropped. Mention that it opens in the zopf app's editor, or runs with
   `zopf run <name>`.
