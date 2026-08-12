A connector is this directory. Two files in it are zopf's:

1. `{{manifest}}` — the manifest zopf reads:

{
  "name": "the directory name, which wins over this field anyway",
  "description": "one line, shown wherever the connector is listed",
  "run": "run.sh",
  "timeoutSeconds": 120,
  "env": [
    { "name": "SOME_API_TOKEN", "description": "what it is for", "keychain": "some-service" },
    { "name": "SOME_SETTING", "description": "what it changes", "required": false }
  ],
  "inputs": [
    { "name": "channel", "description": "what it is", "required": true, "default": "" }
  ],
  "outputs": [
    { "name": "messageId", "description": "what downstream nodes can do with it" }
  ]
}

2. The script named by "run" — the entry point, any language, chosen by its shebang. `chmod +x` it.

Everything else the script needs sits beside them and is yours: a helper module, a template, a lookup table, a README.
zopf reads none of it. Two rules keep a multi-file connector working — the entry point is the only thing that touches
stdin and stdout, since a helper printing its own JSON object would silently become the answer; and sibling files are
resolved against the script's own location rather than the working directory, so the script still runs when invoked by
hand.

The contract zopf enforces:

- Inputs arrive as one flat JSON object on stdin, every value a string:
  {"channel": "#eng", "text": "hello"}. Every input the script reads must be declared in
  "inputs", because that is what the node editor renders as fields.
- Print exactly one JSON object on stdout when done: {"result": "..."} — `result` is what
  downstream nodes read as ${node.result}. On failure print {"error": "what went wrong"}
  and exit non-zero.
- Return anything else worth having beside it, in the same object: every other key becomes
  ${node.<key>} for the nodes that follow. Declare each one in "outputs" — that list is
  what the node editor offers as a reference chip, so an undeclared field works but nobody
  finds it. Keep "result" the answer somebody would want on its own.
- Anything else on stdout is treated as a log line; progress and diagnostics belong on stderr.
- Secrets: read them from environment variables and nowhere else — never from the inputs,
  never from a file you write, and never hard-coded. Declare each one in "env". zopf resolves
  it before the script starts (the environment first, then the keychain service named by
  "keychain") and fails the node with a clear message if it can't, so the script can assume its
  required variables are set. Give every secret a "keychain" service name unless there is a
  reason not to; the plain string form, "env": ["HTTP_PROXY"], means environment only. Every
  entry is required unless it says "required": false — declare a variable the script can do
  without that way, keep its default in the script, and a missing value is reported as optional
  and left unset instead of failing the node. Do not try to create the keychain entry yourself
  — say at the end which command the user should run, e.g.
  `security add-generic-password -a "$USER" -s some-service -w`.
- Fail loudly: a non-zero exit skips everything downstream of this node in a workflow.
