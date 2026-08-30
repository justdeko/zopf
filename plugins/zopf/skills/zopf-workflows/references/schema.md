# The workflow schema, field by field

Everything here is what the serializer and `Workflow.validate` actually accept. Unknown keys are **ignored, not
rejected** — the parser is deliberately lenient so a half-written file still opens — so a misspelled key silently does
nothing until `zopf validate` reports it as a stray key.

## Contents

- [Top level](#top-level)
- [repos](#repos)
- [defaults](#defaults)
- [nodes](#nodes)
- [Per type: what is required and what is ignored](#per-type-what-is-required-and-what-is-ignored)
- [edges](#edges)
- [Output fields](#output-fields)
- [schema](#schema)
- [Connector manifests](#connector-manifests)
- [Every validation message](#every-validation-message)

## Top level

| key           | type           | default      | notes                                                                                                           |
|---------------|----------------|--------------|-----------------------------------------------------------------------------------------------------------------|
| `version`     | int            | *this one*   | The workflow format the file is written in. **Don't write it** — see below.                                     |
| `name`        | string         | **required** | Slugified, and must match the filename stem. `zopf run` resolves through the filename.                          |
| `description` | string         | `""`         | Summary line, blank line, detail. First paragraph is what the workflow list shows.                              |
| `repos`       | list of repo   | `[]`         | Every directory a node may run in.                                                                              |
| `skills`      | list of string | `[]`         | Paths to skill directories outside the normal search, declared once here and then referenced by name on a node. |
| `defaults`    | object         | `{}`         | Fallbacks for every node.                                                                                       |
| `nodes`       | list of node   | `[]`         |                                                                                                                 |
| `edges`       | list of edge   | `[]`         |                                                                                                                 |

### version

Leave it out. Absent means the current format, and the editor never adds the key. An older number is
carried forward on load; a newer one is an error and the run is refused.

## repos

```yaml
repos:
  - id: self
    path: ..
  - id: docs
    path: ~/src/handbook
```

`id` and `path` are both required. Paths resolve against the workspace root; `~` and absolute paths pass through.
Declare `self` explicitly even though zopf can infer it from the enclosing git repo —
`validate` only reads `repos:`, so an implicit `self` shows up as an error in the editor.

## defaults

| key              | applies to                    | notes                                                               |
|------------------|-------------------------------|---------------------------------------------------------------------|
| `repo`           | every node                    | The repo id nodes run in unless they name their own. Set this once. |
| `provider`       | `agent` nodes                 | `claude` (default), `codex` or `dsh`. A node naming its own wins.   |
| `model`          | `agent` nodes                 | e.g. `opus`, `sonnet`. A node naming its own wins.                  |
| `permissionMode` | `agent` nodes                 | Claude Code only. See the node table.                               |
| `sandbox`        | `agent` nodes                 | codex only. See the node table.                                     |
| `timeoutSeconds` | `agent`, `shell`, `connector` | Watchdog: the process is killed and the node fails with exit 124.   |

The workspace's `zopf.yaml` takes this same block under `defaults:`, covering every workflow beside it. Precedence runs
node → workflow `defaults` → workspace `defaults` → the app's Settings.

## nodes

| key              | type              | applies to                    | notes                                                                                                                               |
|------------------|-------------------|-------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `id`             | string            | all                           | **Required.** Unique. Must match `[A-Za-z0-9_-]+` to be referenceable as `${id.field}`.                                             |
| `type`           | enum              | all                           | **Required.** `agent` \| `shell` \| `connector` \| `gate` \| `branch` \| `input`.                                                   |
| `title`          | string            | all                           | Display name. Falls back to `id`. **Not interpolated.**                                                                             |
| `provider`       | enum              | `agent`                       | `claude` \| `codex` \| `dsh`. Which agent CLI runs the node. Absent means `defaults.provider`, then the app's default, then claude. |
| `repo`           | string            | `agent`, `shell`              | Repo id to run in. Must be declared.                                                                                                |
| `timeoutSeconds` | int               | `agent`, `shell`, `connector` | Overrides `defaults`.                                                                                                               |
| `position`       | `{x, y}`          | all                           | Canvas coordinate. **Do not hand-write** — one manual position turns off auto-layout for the whole graph.                           |
| `prompt`         | string            | `agent`, `input`, `gate`      | The prompt, the question, or what a gate shows you before you approve. **Interpolated.**                                            |
| `promptFile`     | string            | `agent`                       | Path to a `.md`, resolved against the workspace. Its contents are interpolated. Wins over `prompt`.                                 |
| `alsoRead`       | list of string    | `agent`                       | Extra declared repo ids the node may access (`--add-dir`). Grants write access too, not just read.                                  |
| `allowedTools`   | list of string    | `agent`                       | What runs without being asked: `Read`, `Grep`, `Bash(git push *)`. **Grants, never restricts**, see below. **Not interpolated.**    |
| `skills`         | list of string    | `agent`                       | Skill **names**, not paths. Resolved against the workflow's repos, the workspace `skills/`, and `~/.claude/skills`.                 |
| `model`          | string            | `agent`                       | Overrides `defaults.model`. Not read by `dsh`, which has no flag for one.                                                           |
| `permissionMode` | enum              | `agent` (claude)              | `acceptEdits` \| `auto` \| `bypassPermissions` \| `manual` \| `dontAsk` \| `plan`.                                                  |
| `sandbox`        | enum              | `agent` (codex)               | `read-only` \| `workspace-write` \| `danger-full-access`. Chosen before launch; codex cannot be asked mid-turn.                     |
| `schema`         | list of field     | `agent`                       | Declares the answer's shape. Each name becomes `${id.name}`. Absent means prose, as before. See [schema](#schema).                  |
| `command`        | string            | `shell`                       | Run through `zsh -lc` in the node's repo. **Interpolated.**                                                                         |
| `connector`      | string            | `connector`                   | The connector's directory name.                                                                                                     |
| `inputs`         | map string→string | `connector`                   | Values are **interpolated**; keys are checked against the manifest.                                                                 |
| `expression`     | string            | `branch`                      | **Interpolated**, then evaluated.                                                                                                   |
| `choices`        | list of string    | `input`                       | Makes the question answerable from the menu bar. **Not interpolated.**                                                              |
| `default`        | string            | `input`                       | Pre-filled answer, and what `zopf run` uses when nobody is there. Should be one of `choices` if choices are set.                    |

### `allowedTools` grants, it does not restrict

It lists what runs **without stopping to ask**. A node with
`allowedTools: [Read, Glob, Grep]` still has `Bash`, `Write` and `Edit`, and will use them.

What it doesn't name is left to the CLI: read-only commands (`grep`, `find`, `git log`) run, the rest needs an
answer. Inline approval answers in the app; `zopf run` has no approver, so headless the answer is no. Name what the
node needs, patterns included, or it works in the window and fails from cron. `Bash(git push *)` is a real entry.

### Model names belong to a CLI

`opus`, `sonnet` and `haiku` are Claude Code's. codex takes its own names, and zopf ships no list of them — write one
into the node's `model:` or leave it out and let codex use whatever it is configured for. `dsh` takes none at all: its
headless profile has no `--model`, so a `model:` on a dsh node is a validation warning. This is enforced rather than
merely documented: `defaults.model` is read only by nodes running the *workflow's* default CLI, and the app's default
model only by nodes running the *machine's* default CLI, so `model: opus` in a workflow's defaults never reaches
`codex --model opus`. A node's own `model:` is always honoured, because it was written next to its `provider:`.

### Which CLI, and what it can do

`provider: claude` is the default and has everything: follow-ups, take-over in Terminal, inline approval, `skills:`,
`allowedTools:`, `alsoRead:`, `schema:` and a dollar cost. `provider: codex` runs `codex exec`, which is **one turn**:
no follow-up, no take-over, no inline approval, `sandbox:` in place of `permissionMode:`, and tokens instead of dollars.
`provider: dsh` runs DeepSeek Harness's headless profile, which is one turn and nothing else: `prompt:`, `repo:` and
`timeoutSeconds:` are the only node keys it reads, and its whole stdout becomes `${id.result}`.

A field the chosen CLI has no version of is left out of the command and `zopf validate` warns, naming it — so `skills:`
on a codex node is a warning rather than a silent no-op.

### permissionMode, in practice

`acceptEdits` is the usual choice for a node that edits files unattended. `plan` makes the node produce a plan without
acting. `bypassPermissions` skips every check — reach for it only when the user asks. Leaving it unset means the node
inherits `defaults.permissionMode`, and failing that the CLI's own behaviour, which will stop and ask.

## Per type: what is required and what is ignored

Fields that don't apply to a type are not errors — they are silently ignored. That is a trap: a
`command:` on an `agent` node does nothing at all and nothing warns.

| type        | required                    | also reads                                                                             |
|-------------|-----------------------------|----------------------------------------------------------------------------------------|
| `agent`     | `prompt` or `promptFile`    | `provider`, `repo`, `model`, `timeoutSeconds`, and whatever the chosen CLI has (below) |
| `shell`     | `command`                   | `repo`, `timeoutSeconds`                                                               |
| `connector` | `connector`                 | `inputs`, `timeoutSeconds`                                                             |
| `gate`      | — (`title` is the question) | `prompt`, what to show you before you answer it                                        |
| `branch`    | `expression`                | —                                                                                      |
| `input`     | `prompt`                    | `choices`, `default`                                                                   |

A `shell` node runs in its repo. A `connector` node runs in the **connector's own directory**, not in a repo — so its
script's relative paths are relative to itself.

## edges

| key    | type   | default      | notes                                                                                 |
|--------|--------|--------------|---------------------------------------------------------------------------------------|
| `from` | string | **required** | A node id.                                                                            |
| `to`   | string | **required** | A node id.                                                                            |
| `on`   | enum   | `success`    | `success` \| `failure`.                                                               |
| `when` | bool   | absent       | **Only on an edge out of a `branch`.** Exactly one `true` and one `false` per branch. |

The scheduler settles a node when every incoming edge has settled and at least one arrived. An edge arrives when its
source ended the way `on:` asked and, out of a branch, the branch went that way.

`STOPPED` — a rejected gate, a cancelled input, a run the user stopped — matches neither `success`
nor `failure`, so everything downstream is skipped including `on: failure` edges.

Cycles, self-edges, duplicate node ids and edges naming a node that doesn't exist are all errors.

## Output fields

| type        | fields                                                            |
|-------------|-------------------------------------------------------------------|
| `agent`     | `result`, `sessionId`, `costUsd`, plus every name in `schema`     |
| `shell`     | `result` (**stdout only**, trimmed), `exitCode`                   |
| `connector` | `result`, `exitCode`, plus every name in the manifest's `outputs` |
| `gate`      | `result` — `approved` or `rejected`                               |
| `branch`    | `result`                                                          |
| `input`     | `result` — the answer                                             |

Interpolation is `${nodeId.field}` and nothing else. An unresolved reference is left in the text verbatim rather than
blanked, and the node gets a warning in its transcript.

Without a `schema:`, an `agent` node's `result` is its **final message only** — everything said mid-turn is gone. With
one, `result` is the whole object as JSON text and each declared name is its own field.

## schema

Only on an `agent` node, and entirely optional — leaving it out is what every workflow did before it existed, and the
node still answers in prose. Declaring it makes the node fill a shape instead:

```yaml
  - id: review
    type: agent
    prompt: Does this diff belong in the release notes?
    schema:
      - name: audience
        description: user-facing or internal
      - name: headline
        required: false
      - name: findings
        type: array
```

| key           | type   | default      | notes                                                                                                  |
|---------------|--------|--------------|--------------------------------------------------------------------------------------------------------|
| `name`        | string | **required** | Becomes `${id.name}`. Must match `[A-Za-z0-9_-]+`, and must not be `result`, `sessionId` or `costUsd`. |
| `type`        | enum   | `string`     | `string` \| `number` \| `boolean` \| `object` \| `array`.                                              |
| `description` | string | `""`         | Sent to the model as the field's description. This is your only steering.                              |
| `required`    | bool   | `true`       | A non-required field may come back absent, and then `${id.name}` stays verbatim.                       |

**What you get back.** Each name is a field you can compare — `expression: ${review.audience} == user-facing` works
directly, which is the whole point of declaring one. `${review.result}` is still there and is the entire object as JSON
text, which is what you hand to a `shell` node running `jq`.

**`object` and `array` are blobs, not values.** They have no inner shape — the model chooses the keys and there is no
ceiling on the size, and the value arrives whole because `${id.field}` reaches exactly one level. Never branch on one.
Declare plain fields for anything a later node compares, and keep the nested type for something you pass to `jq` or
quote into another prompt.

**It costs a turn and does not stop the prose.** The model answers twice — once in prose, once by filling the shape — so
a schema'd node's transcript is roughly double and its cost slightly higher. If you want the prose gone, say so in the
prompt; zopf will not edit a prompt for you.

## Connector manifests

A connector is a directory under `<workspace>/connectors/` (falling back to `~/.zopf/connectors`)
holding a `connector.json` and a script. Read the manifest before writing a node that calls it — the validator checks
your `inputs` against it.

```json
{
  "name": "macos-notify",
  "description": "Post a macOS notification",
  "run": "run.py",
  "timeoutSeconds": 20,
  "inputs": [
    {
      "name": "message",
      "description": "The body",
      "required": true
    },
    {
      "name": "title",
      "required": false,
      "default": "zopf"
    }
  ],
  "env": [
    {
      "name": "GH_TOKEN",
      "keychain": "gh-token",
      "required": false
    }
  ],
  "outputs": [
    {
      "name": "shown",
      "description": "The body as displayed"
    }
  ]
}
```

The runtime contract: zopf writes one flat JSON object of strings to the script's stdin, and expects one JSON object on
stdout — `{"result": ...}` plus any declared output fields, or `{"error": ...}`. A connector that prints no JSON has its
plain output used as `result`, with a warning.

`env` entries are secrets zopf resolves from the environment or the keychain and puts in the child's environment. The
connector never reads them itself, and their values must never reach stdout — the console is the run archive, so a
leaked value is a token to rotate.

## Every validation message

**Errors** (exit 3):

| when                          | message                                                                                |
|-------------------------------|----------------------------------------------------------------------------------------|
| `version:` is newer than zopf | `This workflow needs workflow format vN. This zopf reads vM, so update zopf`            |
| two nodes share an id         | `There are N nodes called "x" — ids have to be unique`                                 |
| an edge feeds its own source  | `The edge on "x" feeds itself, so it could never run`                                  |
| an edge names a missing node  | `An edge connects "x", which isn't a node in <workflow>`                               |
| a cycle                       | `... can never run: the edges into them make a loop`                                   |
| a repo path is gone           | `Repo "x" isn't at <path> any more`                                                    |
| an undeclared repo            | `<node> runs in "x", which isn't declared`                                             |
| an undeclared `alsoRead`      | `<node> also reads "x", which isn't declared`                                          |
| a reference to a non-node     | `<node> reads ${x…}, which is no longer a node`                                        |
| a reference to a non-ancestor | `<node> reads ${x…}, but nothing connects x to it`                                     |
| a required field is empty     | `<node> needs a prompt` / `a command` / `a connector` / `an expression` / `a question` |
| `promptFile` doesn't exist    | `<node> reads its prompt from <path>, which isn't there`                               |
| a branch edge with no `when`  | `<node> has an edge that is neither true nor false`                                    |
| two branch edges the same way | `<node> has N "true" edges; it can only take one`                                      |
| `when:` on a non-branch edge  | `<node> isn't a branch, so the "when" on its edge to <y> can never match`              |
| an unknown connector          | `<node> calls "x", which isn't a connector in this workspace`                          |
| a missing required input      | `<node> needs an input for "x"`                                                        |
| `schema` on a non-agent node  | `<node> declares an output schema, which only an agent node can use`                   |
| a schema field with no name   | `<node> declares an output field with no name`                                         |
| a duplicated schema field     | `<node> declares the output field "x" more than once`                                  |
| a schema field named `result` | `<node> declares an output field named "x", which ${id.x} already means`               |

**Warnings** (exit 0, but read them):

| when                                     | message                                                                                              |
|------------------------------------------|------------------------------------------------------------------------------------------------------|
| an unknown key                           | named, with the node it is on                                                                        |
| an unconnected node                      | `<node> isn't connected to anything`                                                                 |
| `on: failure` from a gate/branch/input   | `<node> is a gate, which never fails, so the edge to <y> can never be taken`                         |
| both `prompt` and `promptFile`           | `<node> sends <file>, so its inline prompt is ignored`                                               |
| a `default` outside `choices`            | `<node> offers a, b, so its default "c" can't be picked`                                             |
| a field the node's CLI has no version of | `<node> runs codex, which has no permissionMode, skills`                                             |
| a CLI that isn't installed               | `<node> runs codex, which isn't on your PATH. The node will fail when it starts`                     |
| an unknown skill name                    | `<node> uses the "x" skill, which isn't in this workflow's repos, the workspace or ~/.claude/skills` |
| a schema field `${...}` can't name       | `<node> declares the output field "x", which ${id.…} can't name`                                     |
| an input the connector doesn't declare   | `<node> sets "x", which <connector> doesn't declare`                                                 |
| a declared output not returned           | `<connector> declares x but didn't return it` (at runtime)                                           |
