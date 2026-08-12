# Prompts

Every word zopf itself says to Claude. A workflow author's own prompts are not here — those live in
their workflow's YAML or a `promptFile:`; these are the ones zopf writes on their behalf, and the
reason they're files is so they can be edited without reading Kotlin.

This README is the only file in here that is never sent to a model. Everything else is verbatim: no
comments, no front matter, nothing but the text.

| File | Sent when | Filled in by |
| --- | --- | --- |
| `connector-create.md` | New connector, once the folder exists | `ConnectorScaffold.createPrompt` |
| `connector-fix.md` | Fix in Claude, on a connector zopf can't read | `ConnectorScaffold.fixPrompt` |
| `connector-contract.md` | Pasted into both of the above as `{{contract}}` | `ConnectorScaffold` |
| `skill-use-one.md` | A node with one skill zopf had to name | `SkillPlan.withInvocation` |
| `skill-use-many.md` | Same, with more than one | `SkillPlan.withInvocation` |

## Editing

- `{{name}}` is a hole zopf fills. The table above says which are supplied for each file; one that
  isn't supplied is sent through **literally**, which is a bug you'd read in the transcript, and
  `PromptsTest` fails the build before you can ship it.
- `${...}` is *not* a hole — that is the workflow interpolation syntax (`${node.result}`), and it
  has to reach the model unchanged. Only `{{...}}` is substituted here.
- Trailing whitespace and the final newline are stripped on load, so a prompt joined with something
  else doesn't arrive with a blank line in the middle.
- Prompts are read from the classpath at session start, not cached. An edit takes effect the next
  time you launch the app — no Kotlin recompiles, but Gradle does have to copy the resource, so
  `./gradlew :desktopApp:run` rather than reaching for a running window.
- `connector-contract.md` is a **specification, not a request**: it restates `ConnectorManifest`
  and the stdin/stdout contract that `ConnectorRunner` enforces. Those two and this file have to
  agree — changing one means changing the others.
