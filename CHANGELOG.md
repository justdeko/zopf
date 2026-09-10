# Changelog

## 1.3.0

- Watch a run move across the canvas: the edge being taken thickens and flows, arms the run passed over fade back, and every node carries a badge that spins while it works and settles into a tick, a warning or a stop.
- A node with nothing to print now reads as running instead of sitting on starting, so a long quiet step no longer looks stuck.
- Open a workflow that is already running and the canvas picks the run up, whether or not the run panel is showing.
- A failed node whose `on: failure` arm ran no longer fails the whole run: the node still shows as failed, and `zopf run` exits 0.
- Agent nodes name their provider and model on the card, and the run console header shows them too, including for runs replayed from the archive.
- The Runs list says when each run started, as a time for today and a date for anything older.
- Settings calls the keep-everything history option "All" rather than "None", and pruning only speaks up when it actually deleted runs.
- A gate, an input or a permission prompt now shows up under a coloured rule in the run console instead of a filled bar.

## 1.2.0

- Runs started from the CLI now show up on the Runs screen while they run, update as they go, and notify you when they finish.
- A run another process is driving is left alone in the app, so it can't be stopped, cleared, deleted or taken over by mistake.
- Settings has a Notifications choice: everything, only failures and prompts, or nothing.
- Gates waiting for an answer sit in the menu bar, so you can approve or reject one without opening the window.
- codex nodes can pick a model, return a declared output schema, and be taken over in a terminal.
- The model field on an agent node takes any name its CLI accepts, with the known ones offered as suggestions.
- An optional field in an output schema comes back as null instead of going missing.
- The Window menu adds Minimize (⌘M) and Zoom.

## 1.1.0

- Drag from one node onto another to connect them, or onto empty canvas to pick a node type and get the edge with it.
- Edit a workflow as YAML in the editor, alongside the canvas view of the same file.
- Start a new workflow from a template: fix failing tests, review and fix, or release notes.
- With no node selected, the editor now says whether the workflow is ready to run and lists what would stop it. Click an issue to jump to that node.
- The Run button and `zopf run` refuse to start a workflow that has errors, and say what to fix first.
- Gates take a `prompt:`, so the run can quote what you are approving. Long results collapse until you click.
- An open workflow picks up edits made to the file outside the app, and asks first if you have unsaved changes.
- A `${node.field}` that names a field the node doesn't produce is now caught before anything runs, and a connector node's own `timeout:` overrides its manifest.

## 1.0.0

The first release.
