# Changelog

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
