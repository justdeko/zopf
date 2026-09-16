# CLAUDE.md

The only design record. No comments in source, so pitfalls, layering rules and non-goals live here.
Contradict something below and fix it in the same commit. `.zopf/workflows/claude-md-audit.yaml`
audits this file.

## What belongs here

Module seams, and pitfalls invisible at the point you would break them. Learnable from the code
(which flows a class exposes, what a small file does) → out. Most changes add nothing. Not a
changelog: no dates, no "changed X to Y", no counts. A wrong paragraph gets rewritten, not annotated.
Notes, not prose: no bold, no full sentences where a fragment carries the fact.

## What zopf is

macOS app + CLI running coding-agent workflows as a directed graph. Workflow = a YAML file in a
workspace, node = a subprocess with a working directory. Both front ends over one engine and one run
archive, so a cron run shows up on the Runs screen. README.md is the user-facing description.

## Commands

```bash
./gradlew ktlintCheck                       # lint (ktlintFormat to fix)
./gradlew :core:jvmTest :shared:jvmTest :cli:test   # the whole suite, as CI runs it
./gradlew :core:jvmTest --tests "com.dk.zopf.runtime.WorkflowEngineTest"        # one class
./gradlew :core:jvmTest --tests "*WorkflowEngineTest.a diamond runs both sides and joins once"

./gradlew :desktopApp:run                   # the window, off the Gradle daemon
./gradlew :desktopApp:hotRun --autoReload   # same, with Compose hot reload
./gradlew :desktopApp:runMacApp             # build the .app and open it via LaunchServices
./gradlew :desktopApp:packageDmg -PpackageVersion=1.3.1
./gradlew :desktopApp:suggestRuntimeModules # re-run after touching the runtime layer

./gradlew :cli:installDist                  # cli/build/install/zopf-cli/bin/zopf
cli/build/install/zopf-cli/bin/zopf validate --workspace .zopf   # what CI runs on this repo
```

`:core`/`:shared` are KMP on one `jvm()` target → `jvmTest`. `:cli`/`:desktopApp` are plain
Kotlin/JVM → `test`. Root `check` also drags in Compose's resource tasks; name the three.

CI (`.github/workflows/check.yml`): ktlint, those three, `zopf validate` on the committed `.zopf`,
macos-14 (the release runner). It re-runs `exportLibraryDefinitions` and fails on `git diff`, so a
new dependency needs an `aboutLibraries`-allowed license and a committed `aboutlibraries.json`.

## Modules

```
core        model + runtime + store. No Compose, no window. The CLI is built on this alone.
shared      every screen, as Compose Multiplatform. api(":core").
desktopApp  main(), the window, the tray, the menu bar. Only what owns the window lives here.
cli         argument parsing and text rendering over :core.
```

Inside `:core`, a file goes by concern, not by caller:

- `model` — the workflow document and its rules.
- `store` — disk. `store/workflow` YAML, `store/workspace` the directory around it, machine-local
  state at the root.
- `runtime` — the scheduler. `run` the run model, `agent` providers, `exec` subprocesses, `macos`
  what exists only because this is a Mac.
- `util` — `Formatting.kt`, so neither front end reaches into `runtime` for a duration string;
  `JsonFields.kt`, the lenient JSON every provider parser shares, `internal`; `Strings.kt` for user facing strings.

Layering:

- `:core` stays headless: no window, no UI dependency. The run model lives there, view-state-looking
  or not, because `WorkflowEngine`, `NodeExecutor`, `SessionReconciler` and `RunArchive` mutate it.
- Run model = immutable state behind a `StateFlow` (`collectAsState` one side, `.value`/`collect` the
  other). `NodeRunState` transitions stay pure — no coroutines, engine or process. "Answering a
  permission resumes the node only if it was still waiting" belongs in a transition, said once.
- State changes only through `NodeRun.update`: under the node's lock, result handed to the run, so
  `WorkflowRunState.nodeStates` is the node's own state and not a second copy. Cross-node facts come
  from `WorkflowRunState.of(node)`, never from walking nodes.
- Composables take snapshots, never live getters. `NodeRun.status` reads `_state.value` and records
  no Compose state → paints once, then deaf, silently. Collect `run.state` once and pass
  `NodeRunState`/`WorkflowRunState` down; `RunRegistry.live` for the tray and menu bar. Live getters
  are for the engine, the CLI, and menus built on click. `shared/compose-stability.conf` declares the
  holders stable, since a `:core` class cannot carry `@Stable`, so a child handed a holder is skipped
  and a collection in its parent never reaches it.
- Transcript deliberately outside that state: thousands of appends, quadratic if folded in. Plain
  list, published as a count, read by index. The `entries` snapshot copies → keep it off the delta
  path, the archive reads `state` and `output()`s its own buffer. Mutable parts of an entry carry
  their own flow → one row repaints. Its own lock, not the node's monitor: a screen reads per visible
  row while an event holds the node a whole turn.
- `:shared` uses `api(":core")`, not `implementation` — `:desktopApp` names `RunRegistry`,
  `RunStatus`, `NodeRun` and `NodeType` directly.

Version generated, not committed: `zopfVersion` in `gradle.properties` (or `-PpackageVersion` from
the tag) → `:core:writeVersion` → `store/BuildInfo.kt`. `scripts/bump-version.sh patch|minor|major`
moves the hand-written copies and fails on one it can't find. Not `install.sh` — its `--version`
example is a placeholder that would 404 between tag and published release. Major ≥ 1, since macOS
refuses a `CFBundleShortVersionString` starting at 0.

## The run model

`runtime/WorkflowEngine.kt`, the scheduler:

- `settle()` walks the workflow's own edges, never kuiver's `getTopologicalOrder()` — a viewer, blind
  to `on: failure` edges, branch conditions and dead arms. Each pass marks ready, skipped-failed-dep
  or skipped-other-path until nothing changes; anything left `QUEUED` was unreachable, which is how a
  cycle gets reported instead of hanging.
- Verdict ≠ worst node status. A failure is handled when an `on: failure` edge leaves it and that
  node ran → `SUCCEEDED` with a `FAILED` node in it. Nothing overrides it from outside the graph.
- One `Semaphore` sized from settings. Gate, input and branch run outside the permit, process nodes
  inside `permits.withPermit` — a gate parked over lunch must not hold a slot. Permission prompts are
  the opposite: mid-turn, permit already held, and kept.

Data passing is `${node.field}` only: one regex in `model/NodeRefs.kt`, resolved by
`RunContext.interpolate` (`runtime/Interpolation.kt`). Unresolved → `Interpolated.unresolved` rather
than a throw, so the editor can show them mid-typing; `validate` still rejects them, so none reach a
tag message or a shell command. Fields come from `outputFields()` — one place, read by that check and
by autocomplete. Connector fields come from the manifest, so the check is skipped without a lookup.

Both front ends gate on that: error → `zopf run` exits 3, Run button says what to fix, no node
started. `workflowLookups()` (`runtime/WorkflowIssues.kt`) is the only builder of what `validate`
reads from disk. Skills and prompt texts in it are a snapshot, since the editor asks for `issues` on
every recomposition. Front ends choose when to rebuild it, never what goes in: `AppState` before the
Run button gates, the editor's poll over its prompt files. Connectors are the app's one contribution,
resolved against the listing the Connectors screen refreshes.

`NodeRun.onEntrySettled` is what the CLI prints its transcript from — on the node's own coroutine, in
step with `onProgress`. Print from anywhere else and the verdict overtakes the lines.

`store/RunArchive.kt`: NDJSON per run under `~/Library/Application Support/zopf/runs/`, shared by app
and CLI, the same bytes as `--format json`. Replayability has to reach the archive, not just the
composition.

- Archive follows the run: a conflated collector on `records()`, writing on `Dispatchers.IO` (a record
  is a whole snapshot). The engine also writes twice, in order — at creation, so it is on disk before
  anything reads it, and in `finalize` after the collector is cancelled, so nothing stale overwrites
  the terminal record. Cancel that collector in `NonCancellable`: `stop` cancels `run.job`, and a
  suspending call in a cancelled `finally` skips `finalize`, leaving a run with no outcome.
- `RunRegistry.watchArchive` polls so CLI runs appear and notify while the app is open. Reads only,
  starts nothing.
- Active record = running elsewhere, or dead with its writer. `RunRecord.pid` decides, only in
  `restoreAs()`. Rewritten as state moves; `isElsewhere` = not ours to stop, clear, take over, delete
  or close out.

`runtime/agent/PermissionBridge.kt`: loopback `com.sun.net.httpserver` plus a generated settings
file, turning Claude Code tool calls into inline approval. Nested deadlines, keep the order — UI 540s
< hook curl 570s < hook 600s, or an outer layer reports a timeout for a decision already made. The
prompt banner uses the UI's 540s, so it withdraws exactly when the question expires. Needs
`jdk.httpserver` in `desktopApp`'s jlink modules: without it every agent node fails in the packaged
`.app` and works fine under `:desktopApp:run`.

Notifications go through one generated bundle, `zopf-notify.app` (`runtime/macos/MacNotifier.kt`),
because `UNUserNotificationCenter` refuses a process with no bundle id — which `:desktopApp:run` is.
`Notifier.kt` is the seam, so headless runs and tests get `SilentNotifier`.

## Providers

`runtime/agent/AgentProvider.kt`: build an argv, parse a line into an `AgentEvent`, reopen a session
in a terminal. Everything the CLIs disagree about is data in `model/AgentCapabilities.kt` — the UI
offers from it, `ignoredFields()` warns from it. New provider = a new `AgentProvider` and a row,
never `if (codex)` in `:shared`.

`AgentProviderId`'s serial name is the executable, so `provider:`, `--provider` and the binary on
PATH are one word.

Streamed JSON isn't a requirement: `dsh --profile headless` is plain text, `DshEvents` makes each
line a `TextDelta`, and the message `NodeRun.finish` closes is the result. No `Result` event → no
cost, no token count.

Subprocesses get the login shell's env and PATH, probed once in `runtime/exec/CommandLookup.kt` via
`zsh -lic` — a Finder-launched `.app` inherits almost nothing. `-i` is load-bearing (`.zshrc` is
where PATH gets built), which makes the probe untrusted output, hence the sentinel and watchdog.
Shell nodes: `zsh -lc`, stdin at `/dev/null`, so a prompting command gets EOF instead of parking.

## Updating itself

`runtime/macos/AppUpdate.kt` for the app, `cli/Upgrade.kt` for the CLI. Trust is who signed it, with
the team id read off the running bundle — notarization alone is not the check, and unsigned builds
fail closed. Never replace the bundle you run from: the swap is a script waiting on the pid, fired
from `quit()`.

## Workspaces and where state goes

Workspace = a directory named `.zopf` (all of `Workspace.isWorkspace`), found by walking up like
`.git`. `AppPaths.defaultWorkspace` is `~/.zopf`, same shape, so `create` never guesses: already one,
return it, else make `.zopf/` inside. An older design let `zopf.yaml` claim a directory too — two
rules and an ambiguous `create`.

`zopf.yaml` is config, not a marker: optional, written by `create` only with a `name`. Holds
`version` (via `isFromTheFuture`), `name`, `defaults`. Precedence: node → workflow `defaults` →
workspace `defaults` → `settings.json`. Committed beats personal, and Settings says so, or a control
that looks authoritative silently isn't. `defaultName` is the parent directory, "zopf" when that is
home.

`WorkflowEngine.start` folds both with `withDefaultsFrom` → `startResolved`, so placement, archive
and resolvers see one settled `defaults`. Never fold in `WorkflowStore`: the editor loads through it
and would write workspace defaults into every file. `EditorState.resolvedWorkflow` folds for display.

A workspace holds only committable things: workflow YAML, connector manifests and scripts, prompt
files, `skills/`. Machine-local lives in `store/AppPaths.kt` — `settings.json`, `workspaces.json`,
`update.json`, the generated `zopf-notify.app`, the run archive (Application Support), update
downloads (`~/Library/Caches/zopf`), logs (`~/Library/Logs/zopf`). `git status` stays clean in a repo
with a `.zopf/`. Exception: node positions go in the YAML, part of the document.

YAML through kotaml with `encodeDefaults = false` (`store/Serialization.kt`), so a hand-written file
survives an editor save byte-identical. kotaml is the maintained kaml fork and keeps the
`com.charleskorn.kaml` package. `strictMode = false` keeps unknown keys from making a file
unopenable; `store/workflow/UnknownKey.kt` reports them as warnings. Writes go through
`store/AtomicWrite.kt`.

## The workflow format version

`version:` absent = current, the normal case. Older = `store/workflow/WorkflowMigrations.kt` before
parsing. Newer = `isFromTheFuture`, both a validation error and a `WorkflowEngine.start` refusal —
both, because the front-end gate misses `startNode` and `retry`.

`WORKFLOW_VERSION` comes from `workflowVersion` in `gradle.properties` via `:core:writeVersion` and
`store/BuildInfo.kt`. Bump it with the migration.

Migrations rewrite parsed YAML, not the decoded model: by the time a `Workflow` exists,
`strictMode = false` has dropped whatever the model lacks. `decodeWorkflow`
(`store/workflow/WorkflowText.kt`) clears `version:` on the way in, unless it is from the future and
has to keep the number that makes it a refusal, so the editor keeps writing unversioned files.

Prefer a serializer alias (`NodeTypeSerializer` reads `claude` as `agent`) and bump only when a
change alters what an existing file means. Aliases fix unversioned files, migrations only pinned
ones.

Skills: `store/workspace/Discovery.kt` searches the workflow's repos, the enclosing git repo, the
workspace, `~/.claude/skills`; first name wins. `runtime/agent/SkillPlan.kt` passes each as its own
`--plugin-dir` unless already ambient in the session.

Secrets (`runtime/exec/Secrets.kt`): environment, then macOS keychain, into the connector process's
environment. Never a file, never the log.

## The editor canvas

kuiver (`io.github.justdeko:kuiver`) is a viewer. zopf owns:

- Selection — `SelectionMode.NONE` in kuiver, driven from `ui/editor/EditorState.kt`, since it also
  moves on add, rename and inspector actions.
- Edge dragging — kuiver's node drag sits on the box around `nodeContent`, so a gesture inside it
  consuming at the touch slop stops kuiver's from starting, and `ui/editor/ConnectDrag.kt` needs no
  fork. Node moving is the mode that gives way: off by default, nothing connects while on. Every edge
  goes through `startConnecting`/`completeConnection`.
- Positions — `manualPositions`/`moveNode()`, default `RelayoutPolicy.KEEP_MANUAL`, reapplied after
  each layout pass.
- Theme — kuiver reads `LocalKuiverColors`, not `MaterialTheme`; black-on-white and broken in dark
  mode without `ui/theme/KuiverBridge.kt`.

`ui/editor/SourcePane.kt` edits the same workflow as YAML through `store/workflow/WorkflowText.kt`,
the serializer path `WorkflowStore` itself uses. It replaces canvas, palette and inspector — a draft
can't be reconciled with something else editing the model. The editor polls its own file so an
outside edit isn't overwritten.

Read kuiver's sources, not its README:

```bash
unzip ~/.gradle/caches/modules-2/files-2.1/io.github.justdeko/kuiver-jvm/*/*/kuiver-jvm-*-sources.jar
```

M3 experimental and expressive APIs are opted into once per `:shared` source set in its
`build.gradle.kts`, not per file.

`.mcp.json` wires `:desktopApp:hotMcpServer`: screenshots, semantic tree and clicks against a
hot-reload instance. Use it instead of asking the user to run the app. CMP's hot-reload plugin
version is pinned in the root build, its own `prefer` constraint predating `hotMcpServer`.

## Conventions

- No comments aside from this file, the license header in `ui/theme/ZopfIcons.kt`, and short labels
  in non-Kotlin files (Gradle scripts, connector scripts, `main.swift`, shell). Those labels:
  lowercase, a few words, naming the thing — `// include license in cli tarball`. Never a
  restatement of the line, never a paragraph copied from here.
- Test names: backticked, lowercase, one clause, subject first — `a cycle fails the nodes in it`,
  `run --format quiet prints only the verdict`. No reasons, no second fact after a comma.
- User-facing strings: in `util/Strings.kt`, nested by surface. Only what a user reads and acts on: labels,
  hints, validation, usage errors. Can't-happen guards, tool and network failures, diagnostics: an exception
  or inline message, English, never in `Strings.kt`.
- ktlint everywhere. `.editorconfig` disables three rules on purpose: PascalCase composables,
  PascalCase constants (`ArrowSize`, `TitleBarHeight`), the filename rule on `main.kt`.

## How tests are written

- kotlin.test on JUnit 4, nothing else. No `useJUnitPlatform()`, so no `@Nested`,
  `@ParameterizedTest`, no Jupiter assertions.
- Varying only by input → a `listOf` table and a `forEach` in one `@Test`, input passed as the
  assertion message, one name covering the rows. Own setup or a different assertion → its own test;
  an `if` in the table means it was two.
- A file is a subject, not a scenario: one class's tests in one file, extra fixtures as extra classes
  (`EditorStateTest.kt`). One-test files get merged.
- `:shared` uses `compose-uiTest`, which needs `compose.desktop.currentOs` in `jvmTest`, not
  `commonTest`.
- `EditorState.executableExists` defaults to a real PATH probe — pass `{ true }`.

## Tests worth knowing about

- `store/workspace/DogfoodWorkspaceTest.kt` — parses and validates this repo's `.zopf/`, and
  re-encodes every fenced workflow in `plugins/zopf/skills/**` for byte-identity with editor output.
- `ui/editor/EditorRoundTripTest.kt` (`:shared`) — editor commands against the file on disk.
- `RunConsoleTest`, `RunsScreenTest` — real compositions asserting a repaint; the only guard against
  a screen rendering a run it never subscribed to.
- `core/src/testFixtures/` — `exampleWorkflow`, wired into `:core` and `:shared` so both suites share
  one fixture.

## Deliberate non-goals

- Nothing starts a run by itself: no triggers, scheduler, daemon or `--watch`. cron, CI and git hooks
  do it better. Background work is fine — editor polling, `watchArchive`.
- No expression language. `${node.field}` and string comparison in `branch`; an evaluator ends
  reviewable YAML.
- Shell-only graphs are the wrong shape, and the workflow skill says so. zopf is for graphs with
  judgment in them: agent, gate, input.
- No notification controls beyond `AppSettings.notify`. Style, sound and Focus are System Settings'
  job. Nothing posts while the window is focused.
- macOS on Apple Silicon only: `~/Library`, `zsh`, and `:cli` deleting the Windows launcher.
- Ships no agent CLI, installs nothing but itself. `zopf run` never touches the network;
  `ZOPF_NO_UPDATE_CHECK=1` / `DO_NOT_TRACK=1` silence the weekly check.
- Interpolation like `${analyze.result}` reaching a `shell` node is intentional, don't sanitize it.
  Gates and inline approval are the answer.
