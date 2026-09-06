# Changelog

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
