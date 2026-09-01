---
name: zopf-workflows
description: Write, edit and validate zopf workflow YAML — the graph-based agent workflows (Claude Code, codex or DeepSeek Harness) that live in a workspace's workflows/ directory. Use this whenever the user mentions a zopf workflow, asks to add or change a node, edge, gate, branch or connector step, is touching a file under .zopf/workflows/ or any workflows/*.yaml sitting beside a zopf.yaml, wants something to happen when a step fails or wants a person asked before continuing, or wants zopf run / zopf validate used on one. Reach for it too when the user describes an automation in zopf's terms — "run the suite then have Claude fix what broke", "notify me when the review is done", "ask me before it commits" — even if they never say the word workflow.
---

# Writing zopf workflows

A zopf workflow is one YAML file describing a directed acyclic graph of steps. zopf runs it headlessly
(`zopf run <name>`) or in its window, and the file is meant to be hand-edited, reviewed and committed — so it is source,
and should read like source somebody else will pick up.

Your job when this skill is active is to produce a workflow that **validates clean and does what the author meant on the
first run**. Most of what goes wrong in this format is not a syntax error; it is a graph that validates, runs, and
quietly skips the step that mattered. The sections below are organised around those failures.

## First decide whether this should be a workflow at all

zopf is for graphs with **judgment** in them — a model reading something and deciding, or a person approving before the
irreversible step. That is what the `agent`, `gate` and `input` nodes are, and they are the reason the format exists.

So a graph made only of `shell` nodes is almost always the wrong shape. Build, test, deploy and cleanup pipelines belong
in CI, a Makefile or a shell script, which already do ordering, caching and scheduling far better and cost nothing to
run. zopf is not a build server and not a scheduler: there is no `--watch`, no schedule flag and no daemon. If the user
asks for a nightly build, a cron-style job, or a chain of commands with no decision anywhere in it, say so plainly and
briefly, offer the simpler tool, and then do what they asked if they still want it — it is their call, not yours.

The honest test: **remove every `agent`, `gate` and `input` node. If what's left still does the job, it was never a
workflow.**

What zopf is genuinely good at is the thing a script cannot do — handing a model some context and acting on what it
concludes, with a person in the loop where it matters.

## Start by reading, not writing

Three things to establish before you touch a file:

1. **Find the workspace.** It is the nearest `.zopf/` directory walking up from where the user is, the way git finds
   `.git`; `~/.zopf` is the fallback. Nothing inside has to declare it — like `.github/`, the directory is the whole
   marker, and a `zopf.yaml` in it is optional and only for workspace-wide `defaults:`. Workflows go in
   `<workspace>/workflows/`, connectors in `<workspace>/connectors/`.
2. **Read a neighbouring workflow.** The existing files in that directory are the house style, and they tell you which
   repo ids and connectors already exist. Do not invent a connector name — list
   `<workspace>/connectors/` and read the `connector.json` of any you plan to call, because its declared `inputs` are
   checked at validation time.
3. **Ask what "done" looks like** only if it is genuinely ambiguous. Usually the user's sentence already names the
   steps; turn it into a graph and show them.

The filename must be the slugified `name:` — `name: review-changes` lives in `review-changes.yaml`.
`zopf run review-changes` resolves through the filename, so a mismatch means the workflow cannot be run at all.

## The shape of a file

```yaml
name: verify
description: |-
  One line that stands alone as a summary.

  Then the detail, in as many paragraphs as it takes.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: tests
    type: shell
    title: The suite
    command: npm test 2>&1
  - id: diagnose
    type: agent
    title: Fix what broke
    permissionMode: acceptEdits
    allowedTools:
      - Read
      - Edit
      - Glob
      - Grep
      - Bash
    prompt: |
      `npm test` just failed. Its output:

      ${tests.result}

      Find the cause and fix it.
edges:
  - from: tests
    to: diagnose
    on: failure
```

That is not one house style among several — it is **byte for byte what zopf's editor writes**, and the
[canonical form](#the-canonical-form) below is the rest of the rules. Match it, so that the first person to open your
workflow in the window and press Save gets no diff.

`repos:` declares every directory a node may run in, by id. **Declare `self` explicitly** — zopf can infer it at runtime
from the enclosing git repo, but `validate` only looks at `repos:`, so relying on the implicit id paints the editor with
errors on a workflow that runs fine. From a `.zopf/`
workspace, `self` is `path: ..`.

`defaults:` sets `repo`, `model`, `permissionMode` and `timeoutSeconds` for every node that doesn't name its own.
Setting `defaults.repo` once is almost always right. The workspace's own `zopf.yaml` takes the same block, applying to
every workflow beside it; a workflow's `defaults:` overrides it, and both override the app's Settings. Put a default
there rather than in each file when it is true of the whole workspace.

`position:` on a node is the canvas coordinate. Never write one by hand — the moment any node has one, zopf stops
auto-laying-out the graph and the author's arrangement is frozen. Leave it out and let the editor place things.

## The six node types

| `type:`     | needs                              | `${id.result}` is                              | can fail? |
|-------------|------------------------------------|------------------------------------------------|-----------|
| `agent`     | `prompt:` or `promptFile:`         | the model's **final message**, nothing earlier | yes       |
| `shell`     | `command:`                         | **stdout only**, trimmed                       | yes       |
| `connector` | `connector:`                       | what the connector's JSON returned as `result` | yes       |
| `gate`      | `title:` is the question           | `approved` or `rejected`                       | no        |
| `branch`    | `expression:`                      | the expression as evaluated                    | no        |
| `input`     | `prompt:` (the question)           | whatever was answered                          | no        |

Reach for them like this:

- **`shell`** for anything a command can do. It is free, it is fast, and it never needs a permission. A `shell` node
  that gathers context for an `agent` node is the single most useful shape in this format: `git diff` into a prompt
  leaves the reviewing node nothing it needs to shell out for, where a `Bash` grant would have it stopping for
  permissions and burning turns.
- **`agent`** for judgment: a headless session of the `claude` CLI, or of `codex` or `dsh` when the node names one in
  `provider:`. `allowedTools:` is
  [what runs without being asked](references/schema.md#allowedtools-grants-it-does-not-restrict). It grants and never
  restricts, so leaving `Bash` off a list does not take `Bash` away. Name what the node actually needs. Leave
  `provider:` out unless the user asks for another CLI by name; a codex or dsh node takes one turn and has no skills,
  no tool allowlist and no inline approval, and a dsh node has no model either.
- **`connector`** for a side effect with a contract: filing an issue, sending a notification. Call one that exists. A
  `shell` node running the same CLI (`gh issue create`, `curl`) is a fine choice too and often the simpler one — the
  connector earns its place when the call needs a secret, has outputs a later node reads by name, or is used by more
  than one workflow. If the workspace already has a connector for the job, use it rather than reinventing it in shell.
- **`gate`** to make a person say yes before something irreversible. Its `title:` is the question, and its
  `prompt:` is what they read before answering. Quote the output being approved into it.
- **`input`** to get a value from a person. `choices:` makes it answerable from the menu bar with the window closed,
  which free text is not.
- **`branch`** to take one of two paths. See the grammar below before you write an expression.

## Edges, and the one scheduling rule

```yaml
edges:
  - from: a
    to: b            # on: success is the default
  - from: a
    to: rescue
    on: failure
  - from: check
    to: deploy
    when: true       # only ever on an edge out of a branch
```

**A node runs once every edge into it has settled, and at least one of them arrived.** An edge arrives if its source
ended the way the edge asked for. That single rule gives you everything:

- A predecessor that failed skips whatever hangs off its *success* edges, so "apply these fixes"
  never runs on an analysis that died.
- `on: failure` is not a special case — the edge asked for the failure, so it arrived, so the node behind it runs
  normally. This is how you write a recovery path.
- A diamond joins once. A rejoin after a branch still runs.

Only `success` and `failure` exist. **A stopped node satisfies neither** — so a rejected gate or a cancelled input ends
everything downstream of it, including any `on: failure` edge you hung there as a safety net. That is deliberate: a
person said no, and setting the recovery path running would be the opposite of what they asked for. If you want "the
person declined" to be handled, use an `input`
with `choices:` and branch on the answer, not a gate.

`gate`, `branch` and `input` never fail, so an `on: failure` edge out of one can never be taken — validation warns about
it.

## Passing data: `${nodeId.field}`

This is the entire data-passing mechanism. There is no expression language, no default value syntax, no nesting.

Fields by type: every node has `result`. A `agent` node also has `sessionId` and `costUsd`; `shell`
and `connector` also have `exitCode`; a connector adds whatever its manifest declares as outputs, and an `agent` node
adds whatever its `schema:` declares.

A reference works **only in these places**: a node's `prompt`, `command`, `expression`, the values of a connector's
`inputs`, and inside the file named by `promptFile`. It does **not** work in `title`,
`choices`, `default`, or `allowedTools` — a `${...}` there is passed through as literal text and nobody will tell you.

**The node you reference has to be an ancestor**, not merely present in the file. If nothing connects
`a` to `b`, then `b` cannot read `${a.result}` — validation calls this out, because at runtime the reference would be
left standing verbatim in the prompt and the model would see the raw `${a.result}`
characters.

Two facts about what actually lands in `result` decide how you write the producing node:

- **`shell` gives you stdout only.** stderr is shown in the console and archived, but it is not in
  `${id.result}`. A command whose interesting output goes to stderr — many compilers, many test runners — needs `2>&1`
  in the command, or the consuming node gets an empty string.
- **`agent` gives you the final message only**, unless you declare a `schema:`. Everything the model said mid-turn is
  gone either way. If a later node consumes the answer, either declare the fields it needs (below) or say so in the
  prompt: end with a verdict line, a list, a path — whatever the next step needs.

## Declaring an agent node's answer: `schema:`

Optional, and off unless you write it — a node without one answers in prose exactly as before. With one, the node fills
a named shape and each name becomes a field:

```yaml
  - id: classify
    type: agent
    prompt: Read the diff and decide who the change is for.
    schema:
      - name: audience
        description: user-facing or internal
```

`${classify.audience}` is then `user-facing` or `internal` — a value a branch can compare, which prose never is.
`${classify.result}` is still there and holds the whole object as JSON text.

Reach for it when a later node needs **a value rather than a paragraph**: a branch expression, a connector input, an id
or path quoted into a command. Don't reach for it when the answer is genuinely prose that a person or another model
reads — a summary, a review, a plan. Declaring a shape there just costs a turn and gets you the same text in a wrapper.

Three things to know before you use it:

- **Keep fields flat.** `object` and `array` fields are blobs the model shapes as it likes, and `${id.field}` reaches
  one level only, so you get JSON text you can't compare. Fine to hand to `jq`; never branch on one.
- **It costs an extra turn**, and the model still writes its prose answer alongside the structured one, so the
  transcript roughly doubles.
- **`required: false`** means the field may come back absent, and then `${id.name}` is left standing verbatim.

## Branch expressions are smaller than they look

The whole grammar, in evaluation order:

1. If the text contains `!=`, split at the first one and compare the two sides as strings.
2. Otherwise if it contains `==`, split at the first one and compare as strings.
3. Otherwise, it is truthy unless it trims to one of `""`, `false`, `0`, `no`, `off`, `null`
   (case-insensitive).

Both sides are trimmed, and a matching pair of surrounding quotes is stripped. There is no `&&`, no
`||`, no `<`, no regex, no arithmetic. Comparison is string equality, always.

A branch needs **exactly one `when: true` edge and one `when: false` edge** out of it, and no unconditional edge.
`when:` on an edge out of anything else is an error.

Two expressions that look right and are not:

- **`${build.exitCode} == 0`** can only be asked when the answer is already yes. A non-zero exit fails the node, so the
  branch never runs. Use an `on: failure` edge instead — that is the shape this format has for it.
- **`${review.result} == SHIP`** against an `agent` node almost never matches, because `result` is a whole final message
  and `==` is exact string equality. Declare the field you want to compare instead:

  ```yaml
  - id: review
    type: agent
    prompt: Should this ship?
    schema:
      - name: verdict
        description: ship or hold
  - id: which
    type: branch
    expression: ${review.verdict} == ship
  ```

  Where a schema doesn't fit — the producing node is a `shell` node, or its answer has to stay prose — reduce it to a
  token first:

  ```yaml
  - id: verdict
    type: shell
    command: |
      case "${review.result}" in *SHIP*) echo ship ;; *) echo hold ;; esac
  - id: which
    type: branch
    expression: ${verdict.result} == ship
  ```

  The `case` always exits 0, so the shell node succeeds either way and the branch gets to decide — which is the point.

## Validate, then dry-run

Always finish by checking your work. Both are free and neither spends a token:

```bash
zopf validate <name>     # or bare, for every workflow in the workspace
zopf run <name> --dry-run
```

If `zopf` is not on PATH, say so and check the file by eye against `references/schema.md` rather than claiming it
validates.

`validate` exits 3 on an error and 0 on warnings alone, and an error is not only a report: `zopf run` refuses to start
a workflow that has one, and so does the Run button. Read the warnings too, because the two that matter most are
warnings: an unknown key (the file parses with unknown keys silently ignored, so a typo'd `promtFile:` is otherwise
invisible) and a node connected to nothing.

`--dry-run` prints the waves the nodes would run in and starts nothing. It is the cheapest way to see that the graph is
shaped the way you think — if a node you expected in wave 3 shows up in wave 1, an edge is missing.

Do not run the workflow for real to check your work unless the user asks. A `agent` node costs money and a `connector`
node has side effects.

## Writing the file itself

**Never put comments in the YAML.** zopf's editor rewrites the whole file from the model on save, and a comment is not
in the model — so the first time the user opens the workflow in the window and saves, every comment you wrote silently
disappears. Anything you want to say goes in `description:`
and in each node's `title:`, both of which survive.

**Keep `description:` short.** One line saying what the workflow does, and stop. Only add a second short paragraph when
the graph's shape would genuinely puzzle the next reader — a branch where a failure edge looks more natural, a node fed
by reference instead of a tool grant. Two or three sentences is the ceiling; the workflow list shows only the first
paragraph anyway, and a description that runs to four paragraphs is a design memo nobody asked for. The reasoning that
doesn't fit belongs in your reply to the user, not in their file.

`title:` carries the rest: one short line per node saying what that step is.

### The canonical form

zopf's editor rewrites the whole file from the model on save. It cannot preserve anything that isn't in the model, so
**anything you write that the editor wouldn't is a whole-file diff waiting for the next person who saves.** Write the
file the way the editor writes it:

- **Sequences indent two spaces under their key** — `nodes:` then `  - id: …`, never a `-` flush against the key.
- **Block sequences everywhere.** `allowedTools:` then one `  - Read` per line, not the flow form
  `allowedTools: [Read, Glob]`.
- **The only blank lines in the file are inside a block scalar.** A node starts on the line after the last line of the
  one before it; a blank line you put between two nodes to space them out is gone on the first save.
- **Literal block scalars**: `prompt: |` for prompts, `description: |-` for descriptions. Never folded (`>-`).
- **`${node.result}` goes unquoted.** `$` starts nothing in YAML, so the quotes are dropped on save.
- **One trailing newline**, and only keys whose value differs from the default — no `on: success`, no `required: true`.
- **No `version:`.** Absent means the current format, which is what you want, and the editor strips it on save.
- **A node's keys come out in model order**, which is every short key first and every block scalar last:

  ```
  id  type  title  repo  model  permissionMode  timeoutSeconds  connector  expression  default  choices
  alsoRead  allowedTools  skills  schema  inputs  command  promptFile  prompt
  ```

  The tail matters more than the rest: put `allowedTools:` after a twenty-line prompt and the editor moves it back,
  because everything below a block scalar reads as detached from the node it belongs to.

One of these repays a second look. **`|-` keeps your line breaks exactly**, and that is what makes a description
round-trip untouched: what the file shows is what the string holds. So hard-wrap the detail paragraphs yourself at the
width the rest of the file uses, keep the summary on **one** line (the workflow list shows the first line, and a wrap
would cut it in half), and separate paragraphs with a blank line. A folded `>-` looks tidier and is not stable — saving
rewrites it as `|-`, and the whole paragraph lands on one very long line.

## More detail

- `references/schema.md` — every field on every type, what validation checks, and the exact error and warning messages
  you might see.
- `references/patterns.md` — the five graph shapes worth knowing (gather-then-judge, the diamond, the recovery edge, the
  approval gate, the branch), each as a complete runnable file.
