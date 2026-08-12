# connector.json, field by field

Every field has a default, and an unknown key is ignored rather than refused — so a manifest that omits everything still
parses, and a typo'd key is silently nothing. That is the same tolerance workflow YAML has, and it cuts the same way:
nothing tells you `"input"` should have been
`"inputs"`. The checker (`scripts/check_connector.py`) is what catches it, by showing you the stdin object it built.

## Top level

| Field            | Type   | Default    | What it does                                                                                                                                                                                                                                                 |
|------------------|--------|------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `name`           | string | `""`       | Overruled by the directory name, always. Keep them equal or leave it out.                                                                                                                                                                                    |
| `description`    | string | `""`       | One line, shown on the connectors screen and in the node editor. Falls back to the input names when blank, which reads like a bug.                                                                                                                           |
| `run`            | string | `"run.sh"` | The **entry point**, relative to the connector directory. The shebang decides the language. The directory may hold as many other files as the script needs; this is the only one zopf launches, and with `connector.json` the only one it requires to exist. |
| `timeoutSeconds` | int    | `120`      | The process is killed at this point and the node fails with exit 124.                                                                                                                                                                                        |
| `inputs`         | array  | `[]`       | Objects only. Everything the script reads.                                                                                                                                                                                                                   |
| `env`            | array  | `[]`       | Secrets. Objects, or a bare string for the environment-only form.                                                                                                                                                                                            |
| `outputs`        | array  | `[]`       | Objects, or a bare string. The fields beside `result` that a later node can name.                                                                                                                                                                            |

## `inputs`

```json
{ "name": "channel", "description": "what it is, and what a real value looks like", "required": true, "default": "" }
```

| Field         | Default | Notes                                                                                                                          |
|---------------|---------|--------------------------------------------------------------------------------------------------------------------------------|
| `name`        | —       | The stdin key, and the field label in the node editor.                                                                         |
| `description` | `""`    | The field's help text. This is the connector's documentation; write it for someone wiring a node who will not open the script. |
| `required`    | `false` | With no value and no default, the node fails **before the script launches**, and `zopf validate` reports it on the workflow.   |
| `default`     | `""`    | Sent when the node sets nothing. A `required` input with a default never actually blocks anything.                             |

There is no shorthand for an input — a bare string in this array is an error, not a name.

Every declared input is present on stdin on every run, defaulted when unset. There is no way to tell
"not set" from "set to empty", so if that distinction matters, use a sentinel default and say so in the description.

A node may set an input the manifest doesn't declare: it is passed through, and validation warns. That is a typo nine
times in ten.

## `outputs`

```json
[ "permalink", { "name": "number", "description": "what a downstream node can do with it" } ]
```

A bare string is shorthand for `{"name": "…", "description": ""}`. Prefer the object form — the description is what the
node editor shows as a reference chip, so an undescribed output is one nobody picks.

Declaring an output does not create it; the script returning the key does. The two are checked against each other at
runtime and disagreeing either way is reported:

- Declared, not returned → a warning on every run, naming the field.
- Returned, not declared → works, `${node.field}` resolves, but no chip and no discoverability.

Do not declare `result` (it is always there), or `error` and `exitCode` (zopf's own; a returned
`exitCode` is ignored in favour of the process's real one).

## `env`

```json
[ "HTTP_PROXY", { "name": "SOME_API_TOKEN", "description": "what it is for", "keychain": "some-service", "required": false } ]
```

| Field         | Default | Notes                                                                      |
|---------------|---------|----------------------------------------------------------------------------|
| `name`        | —       | The environment variable the script reads.                                 |
| `description` | `""`    | What it is for. Shown where the connector is inspected.                    |
| `keychain`    | `null`  | A macOS keychain **service** name, tried when the environment has nothing. |
| `required`    | `true`  | Note the default: a secret is required unless it says otherwise.           |

A bare string is shorthand for `{"name": "…", "required": true}` with no keychain — environment only.

Resolution order per secret: the environment (empty counts as unset), then `security
find-generic-password -s <keychain> -w`, then missing. A missing **required** secret fails the node before the script
launches. A missing optional one is noted and left unset.

## What `zopf validate` says about a connector node

Run against the workflow, not the connector — these are checks on the node that calls it:

| Message                                                                       | Severity |
|-------------------------------------------------------------------------------|----------|
| `… calls "x", which isn't a connector in this workspace or ~/.zopf/connectors` | error    |
| `… needs an input for "x"`                                                    | error    |
| `… sets "x", which <connector> doesn't declare`                               | warning  |

A connector that cannot be loaded at all doesn't appear in that lookup, so it reports as the first message — "isn't a
connector" covers a missing `connector.json`, a manifest that isn't valid JSON, and a `run:` naming a file that isn't
there. The connectors screen shows those separately with the real reason.
