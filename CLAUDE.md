# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This is the only design record the repo has. The source carries no comments (see Conventions), so
rationale, layering rules and deliberate non-goals live here and nowhere else. A change that
contradicts something below and leaves it standing is how the next session gets misled — correct the
file in the same commit. `.zopf/workflows/claude-md-audit.yaml` exists to catch what slips through.

## What belongs in this file

An architectural overview: the modules, how the engine, the store and the front ends fit together,
and the constraints that shape them. It stays at that altitude — an implementation detail belongs in
the code, and the reason for one belongs in a test name.

Most changes therefore add nothing here. A bug fix, a screen, a refactor, a field, the wording of a
status line: none of them are architecture. It is not a changelog either — no "changed X to Y", no
dates, no session notes, and no counts of anything, which go stale on the next commit. Write what is
true now, and when a change makes a paragraph here wrong, rewrite that paragraph.

## What zopf is

A macOS app and a CLI that run coding-agent workflows as a directed graph. A workflow is a YAML file
in a workspace; a node is a subprocess on this machine with a working directory. The app and the CLI
are two front ends over one engine and one run archive, so a run started from cron shows up on the
app's Runs screen. README.md is the user-facing description and is accurate; this file is the part
that isn't visible from outside.

## Commands

```bash
./gradlew ktlintCheck                       # lint (ktlintFormat to fix)
./gradlew :core:jvmTest :shared:jvmTest :cli:test   # the whole suite, as CI runs it
./gradlew :core:jvmTest --tests "com.dk.zopf.runtime.WorkflowEngineTest"        # one class
./gradlew :core:jvmTest --tests "*WorkflowEngineTest.a diamond runs both sides and joins once"

./gradlew :desktopApp:run                   # the window, off the Gradle daemon
./gradlew :desktopApp:hotRun --autoReload   # same, with Compose hot reload
./gradlew :desktopApp:runMacApp             # build the .app and open it via LaunchServices
./gradlew :desktopApp:packageDmg -PpackageVersion=1.0.0
./gradlew :desktopApp:suggestRuntimeModules # re-run after touching the runtime layer

./gradlew :cli:installDist                  # cli/build/install/zopf-cli/bin/zopf
cli/build/install/zopf-cli/bin/zopf validate --workspace .zopf   # what CI runs on this repo
```

`:core` and `:shared` are Kotlin Multiplatform with a single `jvm()` target, so their test task is
`jvmTest`; `:cli` and `:desktopApp` are plain Kotlin/JVM, so theirs is `test`. `check` on the root
would also drag in Compose's resource tasks for no gain — name the three test tasks.

CI (`.github/workflows/check.yml`) runs ktlint, those three test tasks, and `zopf validate` against
the committed `.zopf` workspace, on macos-14 — the same runner the release builds on.

## Modules

```
core        model + runtime + store. No Compose UI, no window. The CLI is built on this alone.
shared      every screen, as Compose Multiplatform. api(":core").
desktopApp  main(), the window, the tray, the menu bar. Only macOS-specific AWT lives here.
cli         argument parsing and text rendering over :core.
```

The layering rule is that **`:core` must stay runnable headlessly**. `zopf run` starts an engine
with no composition around it, so anything in `:core` that needs a window is a bug. The one thing
`:core` does take from Compose is `compose-runtime`, as `api()`: `RunRegistry`, `WorkflowRun` and
`NodeRun` hold snapshot state so a composition recomposes as a run streams, and behave as ordinary
observable objects when there is no composition. That is why the run model is in `:core` rather than
`:shared` despite looking like view state.

`:shared` declares `api(project(":core"))` rather than `implementation`, because `:desktopApp`'s tray
and menu bar name `RunRegistry`, `RunStatus`, `NodeRun` and `NodeType` directly; hiding `:core` would
only force a second declaration there.

Version: generated, not checked in. `zopfVersion` in `gradle.properties` (overridden by
`-PpackageVersion` from the release tag) is written to a resource by `:core:writeVersion` and read
back through `store/BuildInfo.kt`, so a tagged build and a local build can never disagree with the
jar they came from. The number is still written down in a handful of other places — the plugin
manifest, the examples here and in `install.sh` — so `scripts/bump-version.sh patch|minor|major` moves all
of them at once and fails rather than skipping one it can no longer find. The major stays at 1 or
above: macOS refuses a bundle whose `CFBundleShortVersionString` starts at 0.

## The run model

`runtime/WorkflowEngine.kt` is the scheduler. `settle()` walks the **workflow model's own edges** —
not any graph-library ordering, and specifically not kuiver's `getTopologicalOrder()`, because the
canvas library is a viewer and knows nothing about `on: failure` edges, branch conditions or dead
arms. Each pass marks nodes ready, skipped-because-a-dependency-failed, or skipped-because-the-run-
took-another-path, and loops until nothing changes. Anything still `QUEUED` when the loop drains was
unreachable — that is how a dependency cycle is reported instead of hanging.

Concurrency is one `Semaphore` sized from settings. **Gate, input and branch nodes run outside the
permit; process nodes run inside `permits.withPermit`.** A gate parked over lunch must not hold a
slot the rest of the graph could use. A permission prompt is the opposite case: it happens mid-turn
inside a process that already holds its permit, and it keeps it.

Data passing is `${node.field}` and nothing else — one regex in `model/NodeRefs.kt`, resolved by
`RunContext.interpolate` in `runtime/Interpolation.kt`. There is no expression language, and a
`branch` expression is a string comparison rather than an evaluator. Unresolved references come back
in `Interpolated.unresolved` instead of throwing, so the editor can show them while you are still
typing. Which fields a node offers is `outputFields()`, which is also what the inspector's
autocomplete and validation read — add a field in one place.

`store/RunArchive.kt` writes NDJSON per run under `~/Library/Application Support/zopf/runs/`. It is
the shared surface between the app and the CLI, and `--format json` is that same NDJSON on stdout.
Both front ends read it, so anything a run needs to be replayable afterwards has to reach the archive
and not just the composition.

`runtime/PermissionBridge.kt` is a loopback `com.sun.net.httpserver` server plus a generated settings
file, hooking Claude Code's tool calls back into the app for inline approval. The three deadlines are
nested on purpose and must stay ordered: the UI has 540s to answer, the curl in the hook gives up at
570s, and the hook itself times out at 600s — each layer must fail before the one outside it, or the
outer layer reports a timeout for a decision that was actually made. This is also why
`jdk.httpserver` is in `desktopApp`'s jlink module list; without it every agent node fails in the
packaged `.app` while working perfectly under `:desktopApp:run`.

## Providers

An agent node is a provider behind `runtime/AgentProvider.kt`: `ClaudeProvider`, `CodexProvider` and
`DshProvider` build an argv, turn a line of the CLI's output into an `AgentEvent`, and say how to
reopen a session in a terminal. Everything the CLIs disagree about is declared once, as data, in
`model/AgentCapabilities.kt` — follow-ups, inline approval, take-over, cost reporting, skills, tool
permissions, extra directories, output schema, sandbox, model selection. The UI reads capabilities to
decide what to offer, and `ignoredFields()` reads the same table to warn about a field set on a node
whose provider will ignore it. Adding a provider means a new `AgentProvider` and a new row in that
table; it should not mean an `if (codex)` anywhere in `:shared`.

A provider's `AgentProviderId` serial name is the executable it runs — `claude`, `codex`, `dsh` — so
`provider:` in a workflow, `--provider` on the CLI and the binary on your PATH are all one word.

Streamed JSON is Claude Code's and codex's shape, not a requirement. `dsh --profile headless` prints
plain text, so `DshEvents` turns each stdout line into a `TextDelta`; the message `NodeRun.finish`
closes is the node's result, the way a shell node's stdout is. No `Result` event means no cost and no
token count.

Subprocesses get the **login shell's** environment and PATH, resolved once in
`runtime/CommandLookup.kt` via `zsh -lic`, because a `.app` launched from Finder inherits almost
nothing and `claude` would not be found. The `-i` is load-bearing: a non-interactive login shell
never sources `.zshrc`, which is where a PATH usually gets built. That makes the probe untrusted
output, hence the sentinel and the watchdog. Shell nodes run `zsh -lc <command>` with stdin at
`/dev/null`, so a command that prompts gets EOF rather than parking the run.

## Workspaces and where state goes

A workspace is **a directory named `.zopf`** — that is the whole of `Workspace.isWorkspace`, and
discovery walks up for it the way git does for `.git`. `AppPaths.defaultWorkspace` is `~/.zopf`, so
even the default is the same shape and `create` never has to guess where a new one goes: already a
workspace, return it; otherwise make `.zopf/` inside. An earlier design let a `zopf.yaml` claim any
directory, so that `~/zopf` could be one; that meant two rules, and a `create` that had to sniff for
`.git` to decide which you meant. Moving the default under `.zopf` removed the reason for both.

`zopf.yaml` is therefore config, never a marker — optional, and `create` writes one only when given
a `name`. It carries `version` (read through `isFromTheFuture`), that optional `name`, and
`defaults` — a `NodeDefaults` sitting **between the workflow and the machine**: node → workflow
`defaults` → workspace `defaults` → `settings.json`. The committed file beats the personal
preference on purpose, and the Settings screen says so, or a control that looks authoritative
silently isn't. `defaultName` reads the parent directory, falling back to "zopf" when that parent is
home, so `~/.zopf` doesn't display as your username.

`WorkflowEngine.start` folds the two with `withDefaultsFrom` and passes the result to
`startResolved`, so placement, the archive and both resolvers see one settled `defaults`. Never fold
in `WorkflowStore` — the editor loads through it and would write workspace defaults into every
workflow file. The editor folds for display only, in `EditorState.resolvedWorkflow`.

The split is deliberate: **a workspace holds only things worth committing.** Workflow YAML, connector
manifests and scripts — that's it. Everything machine-local goes to `store/AppPaths.kt`:
`settings.json`, `workspaces.json`, `update.json` and the run archive under Application Support, logs
under `~/Library/Logs/zopf`. Nothing writes a machine-specific file into a workspace, so `git status`
stays clean in a repo that has a `.zopf/` in it. Node positions are the edge case and they do go in
the YAML — a canvas layout is part of the document, not of this machine.

YAML round-trips through kotaml with `encodeDefaults = false` (`store/Serialization.kt`), so a
hand-written file that omits everything default comes back byte-identical after the editor saves it.
kotaml is the maintained fork of kaml, which is archived; it keeps the `com.charleskorn.kaml` package
and continues the same version line, so the imports are not a leftover and nothing but the coordinate
in `libs.versions.toml` changed.
`strictMode = false` keeps an unknown key from making a file unopenable; `store/UnknownKey.kt` then
reports those keys as warnings against the serializer descriptors. Files are written through
`store/AtomicWrite.kt`.

## The workflow format version

A workflow's optional `version:` says which format it was written in. Three cases:

- **absent** — read as the current format. This is the normal case.
- **older** — `store/WorkflowMigrations.kt` carries the file forward to current before it is parsed.
- **newer** — `isFromTheFuture`, which is a validation error *and* a refusal from
  `WorkflowEngine.start`. It has to be in both: the front ends' gate doesn't cover `startNode` or
  `retry`.

`WORKFLOW_VERSION` comes from `workflowVersion` in `gradle.properties`, through `:core:writeVersion`
and `store/BuildInfo.kt`, like the app's version. Bump it in the same commit as the migration.

Migrations rewrite **the parsed YAML, not the decoded model**. By the time a `Workflow` exists,
`strictMode = false` has already dropped any key the current model lacks, so a rename or removal
could never see it. `store/WorkflowStore.kt` clears `version:` after migrating, so the editor keeps
writing current, unversioned files.

Prefer a legacy alias in the serializer — the way `NodeTypeSerializer` reads `claude` as `agent` —
and bump the version only when a change alters what an existing file *means*. An alias fixes
unversioned files too; a migration only reaches files that pin themselves.

`store/Discovery.kt` finds skills across the workflow's repos, the enclosing git repo, the workspace
and `~/.claude/skills`, first name wins. `runtime/SkillPlan.kt` passes each selected skill as its own
`--plugin-dir`, except ones already ambient in the session.

Secrets (`runtime/Secrets.kt`) resolve from the environment first, then the macOS keychain, into the
connector process's environment — never into a file and never into the log.

## The editor canvas

kuiver (`io.github.justdeko:kuiver`) is a **viewer, not an editor**. zopf owns what it doesn't do:

- Selection is `SelectionMode.NONE` in kuiver and driven from `ui/editor/EditorState.kt`, because
  selection also moves on add, rename and inspector actions kuiver cannot see.
- There is no drag-to-connect gesture, and dragging a node already means "move it", so edge creation
  is a click-based connect mode in `EditorState`.
- Saved node positions are seeded through `manualPositions`/`moveNode()` with the default
  `RelayoutPolicy.KEEP_MANUAL`, which reapplies them after every layout pass.
- kuiver depends on Compose foundation only and themes through `LocalKuiverColors`, not
  `MaterialTheme` — its defaults are black-on-white and look broken in dark mode without
  `ui/theme/KuiverBridge.kt`.

When touching the canvas, read kuiver's sources rather than its README:

```bash
unzip ~/.gradle/caches/modules-2/files-2.1/io.github.justdeko/kuiver-jvm/*/*/core-jvm-*-sources.jar
```

Material 3's expressive APIs are opted into once for the whole `:shared` source set in its
`build.gradle.kts`, not per file — the theme is app-wide.

`.mcp.json` wires up `:desktopApp:hotMcpServer`, which drives a running hot-reload instance —
screenshots, the semantic tree, clicks. That is how to check a UI change actually looks right rather
than asking the user to run it. Compose Multiplatform's own hot-reload plugin version is pinned in
the root `build.gradle.kts` (CMP ships an older `prefer` constraint with no `hotMcpServer` task), so
subprojects apply it without a version.

## Conventions

**No comments. Anywhere.** Not in main source, not in tests, no KDoc. Every Kotlin source set is at
zero and stays there; the only exceptions are the license header in `ui/theme/ZopfIcons.kt` and the
Gradle build scripts, which are not Kotlin source sets. Explanation belongs in this file, or in a
test name long enough to say what it asserts (`everyWorkflowInTheSkillDocsIsWrittenTheWayTheEditorWouldWriteIt`).
A comment restating what's here is a second copy that drifts.

User-facing strings are full sentences that say what to do next, not error codes —
`"Repo \"app\" isn't at ~/dev/app any more"`, not `"invalid repo"`. Validation messages in
`model/WorkflowValidation.kt` are the house style; match them.

ktlint runs on every module. `.editorconfig` disables three rules on purpose: PascalCase Composables,
PascalCase constants (`ArrowSize`, `TitleBarHeight`), and the filename rule on `main.kt`.

## Tests worth knowing about

These guard things a normal unit test wouldn't:

- `store/DogfoodWorkspaceTest.kt` parses and validates this repo's own `.zopf/` workspace, so a
  model change that breaks the committed workflows fails the build. CI runs `zopf validate` on it too.
- `store/SkillExamplesTest.kt` re-encodes every fenced YAML workflow in `plugins/zopf/skills/**` and
  asserts it is byte-identical to what the editor would write — the skill docs cannot drift from the
  serializer.
- `store/EditorRoundTripTest.kt` (in `:shared`) drives editor commands and asserts the file on disk.
- `core/src/testFixtures/` holds `exampleWorkflow` and is wired into **both** `:core`'s and
  `:shared`'s test source sets, so the two suites assert against one fixture.

`:shared` tests use `compose-uiTest`, which needs `compose.desktop.currentOs` — declared in
`jvmTest`, not `commonTest`.

## Deliberate non-goals

These are decisions, not gaps. If a change would undo one, that is a real decision to make:

- **No triggers, no scheduler, no daemon, no `--watch`.** A run starts when someone clicks Run or
  something calls the CLI. cron, CI and git hooks already do scheduling better than a desktop app.
- **No expression language.** `${node.field}` is the entire data-passing mechanism, and a `branch`
  compares strings. The moment this grows an evaluator, workflows stop being reviewable YAML.
- **A graph of only shell nodes is the wrong shape** and the workflow skill says so to the user's
  face. zopf is for graphs with judgment in them — agent, gate, input. The rest belongs in a script.
- **macOS on Apple Silicon only.** `AppPaths` resolves `~/Library`, the runners spawn `zsh`, and
  `:cli`'s `startScripts` deletes the Windows launcher rather than shipping something that can't work.
- **zopf redistributes none of the CLIs and installs nothing.** It hands you the release page.
  `zopf run` never touches the network on its own account; `ZOPF_NO_UPDATE_CHECK=1` or
  `DO_NOT_TRACK=1` silences the app's daily check.
- **Content the agent read can reach a shell.** `${analyze.result}` interpolating into a `shell`
  node's command is the design, not an oversight — gates and inline approval are the answer to it.
  Don't "fix" it by sanitising interpolation.
