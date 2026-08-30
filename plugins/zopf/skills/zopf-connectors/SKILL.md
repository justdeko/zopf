---
name: zopf-connectors
description: Write and debug zopf connectors — a manifest plus a script (and whatever else it needs) that is a workflow's seam to the world outside it, living in a workspace's connectors/ directory. Use this whenever the user asks for a new connector, is editing anything under .zopf/connectors/ or a connector.json anywhere beside a zopf.yaml, wants to add a helper module or template to an existing one, or hits a connector that misbehaves — no JSON on stdout, a declared output that never comes back, a secret that won't resolve, a node that fails without saying why. Reach for it too when the user describes something a workflow needs to reach outside itself — "post the result to Slack", "file an issue with the review in it", "page me when it goes red", "have it hit our API", "look it up in the database" — even if they never say the word connector, because the choice between a connector and a plain shell node is the first thing this covers.
---

# Writing zopf connectors

A connector is a directory holding `connector.json`, which declares what it takes and what it gives back, and the
entry-point script that manifest names — plus whatever else that script needs beside it. zopf runs the entry point with
a JSON object on stdin and reads one JSON object off stdout. That is the whole mechanism.

**A connector is a workflow's seam to the world outside it.** Everything inside a run is nodes handing text to each
other; a connector is where that stops and something real happens — an API call, a notification, a ticket filed, a
database queried, a file written somewhere the workflow doesn't own, another program called. What it reaches is not
zopf's business. What comes back is: a *named answer* the nodes after it can quote.

That is the whole of the definition, and nothing about size is in it. A connector is not "a small script" — it is the
boundary. One that needs a helper module, a template, a lookup table or a vendored client is still one connector, and
belongs in one directory; see
[More than one file](#more-than-one-file) below.

The contract is small enough to hold in your head, and almost everything that goes wrong is one of a handful of ways of
getting it subtly wrong, each of which fails quietly. This skill is organised around those.

## First: should this be a connector at all?

A `shell` node crosses that boundary too — it runs any command you like, costs nothing to write and needs no manifest.
So the question is never "does this touch the outside world", it is which form the crossing should take. Reaching for a
connector when a shell node would do is the most common mistake here, so make this call deliberately.

A connector earns its keep when **at least one** of these is true:

- **Something untrusted is being passed through.** This is the strongest reason and the one behind both connectors in
  this repo. A message body is normally a whole `${review.result}` — a Claude verdict full of backticks, quotes and
  `$(...)` — and in a `shell` node that text is spliced into a command line and re-parsed by the shell. A connector
  receives it as a JSON string and hands it on as one argv element, so it is data the whole way.
- **A later node needs part of the answer by name.** A shell node's `${id.result}` is one blob of stdout for the next
  node to slice up. A connector returns `{"result": …, "number": "412"}` and the node after it writes `${file.number}`.
- **It needs a secret.** zopf resolves declared `env` from the environment or the macOS keychain and puts it in the
  child's environment. A shell node has no such mechanism, so the token ends up somewhere worse.
- **More than one workflow wants it.** A connector is the reusable unit; a shell command is copied.

If none of those hold, say so and write the shell node — one line in a workflow beats a directory somebody has to
maintain. `gh pr list`, `git push`, `curl` against something with no secret and no interesting answer: all shell nodes.

## Where it goes, and what it is called

Connectors live in `<workspace>/connectors/<name>/`, where the workspace is the nearest `.zopf/` directory walking up
from where the user is. There is a shared fallback at `~/.zopf/connectors` for
connectors used from several workspaces; prefer the workspace, because that directory gets committed and the connector
travels with the workflows that call it.

**The directory name is the connector's name.** A `"name"` in the manifest that disagrees is overruled and silently
corrected, so keep them the same and let the directory decide. That name is what a workflow node writes as
`connector: <name>`, so it wants to be a slug: `github-issue`,
`macos-notify`.

Before writing anything, **read the connectors already in that directory**. They are the house style, and one of them
may already do the job.

## The contract

Inputs arrive as one flat JSON object on stdin, **every value a string**. One JSON object goes out on stdout. Around
that are half a dozen rules that decide whether a connector works or merely appears to:

**On the way in**

- Every input the manifest declares is *always* sent, filled from its `default` when the node set nothing. So a script
  reads `inputs.get("repo", "")` and gets an empty string, never a missing key.
- Values are strings and only strings. A number, a flag, a list — all arrive as text. Design for that:
  `"labels": "bug,urgent"` split on commas, `"dry_run": "true"` compared as a string.
- The script's working directory is **the connector's own directory**, not the repo the workflow runs in. From
  `.zopf/connectors/foo/` the repo root is two levels up. That is what puts a connector's own helper files within reach,
  and it is why a path into the repo must never be assumed — if the script needs one, take it as a declared input.
- `${node.result}` has already been substituted before stdin is written. A connector never sees that syntax and never
  interpolates anything.

**On the way out**

- Print **exactly one JSON object** on stdout, as the last thing. zopf takes the last line that parses as a JSON
  object — so a progress line printed before it is harmless, but a second object after it wins instead. Diagnostics and
  progress belong on **stderr**, which is shown and archived but is not part of the result.
- `{"result": "…"}` is what `${node.result}` reads. Make it the one answer somebody would want on its own — a URL, a
  verdict, the text as sent. An object with **no** `result` key makes the whole object the result as text, which is
  nobody's idea of a useful string.
- **Every other key becomes `${node.<key>}`.** Declare each in `outputs` — an undeclared field still works, but the node
  editor offers no reference chip for it and nobody discovers it. Declaring one and then not returning it produces a
  warning on every run.
- **A null is not a field.** It is dropped, and then reads as an output you declared and failed to return. For an output
  that has no value this time, return `""`.
- `result`, `error` and `exitCode` are zopf's. Returning `"exitCode"` doesn't shadow the real one.

**When it goes wrong**

- Print `{"error": "what went wrong"}` **and** exit non-zero. Either one alone fails the node; doing both means the
  message is there whichever way a reader looks. A failed node skips everything downstream of its success edges, which
  is the point.
- Say what actually happened. If a CLI you shelled out to printed a reason, pass its message through rather than
  paraphrasing it — "no such label" and "not logged in" want different responses from the person reading the transcript,
  and a generic "gh failed" loses that.
- The manifest's `timeoutSeconds` (default 120) is enforced by killing the process. Set it to something honest for the
  work, and give any network call inside the script a shorter timeout of its own so it can report a clean error instead
  of being killed.

## The manifest

```json
{
  "description": "One line, shown wherever the connector is listed",
  "run": "run.py",
  "timeoutSeconds": 60,
  "env": [
    { "name": "SOME_API_TOKEN", "description": "what it is for", "keychain": "some-service" }
  ],
  "inputs": [
    { "name": "message", "description": "what it is", "required": true },
    { "name": "channel", "description": "what it changes", "required": false, "default": "#eng" }
  ],
  "outputs": [
    { "name": "permalink", "description": "what a downstream node can do with it" }
  ]
}
```

`description` on every input and output is not decoration — the node editor renders inputs as labelled fields and
outputs as reference chips, so these strings *are* the connector's UI. Write them for someone wiring up a node who has
never opened the script. Say what a realistic value looks like:
"OWNER/REPO. Empty uses whatever repo the connector directory sits in" tells them more than "the repo".

Declare an input for **everything the script reads**, or the editor gives the author no way to set it. Give an input a
`default` whenever there is a sensible one, and mark `required` only when there is genuinely nothing to fall back on — a
required input with no value fails the node before the script is launched, and `zopf validate` flags it on the workflow.

`references/manifest.md` has every field, both shorthand forms, and what validation says.

## Secrets

Declare them in `env`; zopf resolves each one before the script starts — the environment first, then the keychain
service named by `keychain` — and fails the node with a clear message if a required one is missing. So the script can
just read `os.environ["SOME_API_TOKEN"]` and assume it is there.

Four rules, and they matter because **the console is the run archive**: anything a connector prints is written to disk
and shown in the window, so a token that reaches it is a token to rotate.

- Read secrets from the environment and nowhere else. Never from an input, never from a file the script writes, never
  hard-coded.
- Never print one, and never let a subprocess print one — put a token in a header or an argv element, not in a URL you
  also echo, and keep `set -x` out of shell connectors.
- Give every secret a `keychain` service name unless there is a reason not to. The bare string form,
  `"env": ["HTTP_PROXY"]`, means environment only.
- **Never create the keychain entry yourself.** Tell the user the command at the end:
  `security add-generic-password -a "$USER" -s some-service -w`

A secret the script can do without gets `"required": false`; keep its default in the script, and a missing value is
reported as optional and left unset instead of failing the node.

## The script

Any language — the shebang decides, and zopf `chmod +x`'s the file for you if you forget (do it anyway). Prefer what is
already on a Mac: `python3`, `curl`, `jq`. Python is the house choice for anything that parses JSON, which is everything
with more than one output.

The skeleton, which is mostly the error paths:

```python
#!/usr/bin/env python3
"""One line on what this does.

Inputs arrive as one flat JSON object of strings on stdin; one JSON object goes out on stdout.

Then a paragraph on why this is a connector and not a shell node — usually that some input is a
whole ${node.result} and reaches the tool as argv rather than as something a shell re-parses.
"""

import json
import subprocess
import sys


def main():
    raw = sys.stdin.read()
    try:
        inputs = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError as exc:
        print(json.dumps({"error": f"inputs were not JSON: {exc}"}))
        return 1

    message = inputs.get("message", "").strip()
    if not message:
        print(json.dumps({"error": "message is required"}))
        return 1

    try:
        result = subprocess.run([...], capture_output=True, text=True, timeout=45)
    except FileNotFoundError:
        print(json.dumps({"error": "sometool is not on PATH — brew install sometool"}))
        return 1
    except subprocess.TimeoutExpired:
        print(json.dumps({"error": "sometool did not return within 45s"}))
        return 1

    if result.returncode != 0:
        print(json.dumps({"error": result.stderr.strip() or f"sometool exited {result.returncode}"}))
        return 1

    print(json.dumps({"result": ..., "permalink": ...}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

Note `text=True` and `timeout=` on every subprocess call, and a named error for each way the call can fail — a missing
binary and a hung network are different problems for the person reading the transcript, and "sometool is not on PATH" is
the one message that saves them a debugging session.

**Connector scripts carry comments, whatever the surrounding project's convention.** Write a module docstring saying
what it does and why it is a connector, and a comment at any line whose reason isn't on its face: a magic number, a
workaround, a message deliberately passed through unedited. Keep them about *why*.

## More than one file

zopf requires exactly two things of the directory: a `connector.json` it can parse, and the file
`run` names. Everything else beside them is yours. A connector that has grown a helper module, a request template, a
lookup table, a vendored client or a directory of fixtures is still one connector — the manifest is its contract with
zopf, and the contract says nothing about how the code behind it is arranged.

```
connectors/deploy-note/
├── connector.json        the contract, and the only file zopf reads
├── run.py                the entry point, named by "run"
├── render.py             imported by run.py
├── templates/note.md     read at runtime
└── README.md             for whoever maintains it, never read by zopf
```

Four things make this work:

- **The entry point owns the contract.** Reading stdin, printing the one JSON object and choosing the exit code stay in
  the file `run` names. Helpers should raise or return values, not print — a helper that prints its own JSON object
  silently becomes the answer, because zopf takes the last object on stdout.
- **Resolve sibling paths against the script, not the cwd.** zopf does run the entry point with the connector directory
  as its cwd, so bare relative paths happen to work — but they break the moment anyone runs the script by hand from
  elsewhere, which is exactly what debugging looks like. Use
  `Path(__file__).resolve().parent / "templates" / "note.md"`. A Python helper next to the entry point imports normally,
  since a script's own directory is what Python puts on `sys.path`.
- **Only the entry point needs the executable bit** (and zopf sets it if you forget). A helper invoked as
  `python3 render.py` or imported doesn't; one you exec directly does.
- **Keep it inside the directory.** A connector is copied, committed and shared as a directory, so a path reaching out
  to a sibling connector or up into the repo is what breaks when someone moves it. Anything from outside should arrive
  as a declared input.

A connector this size is usually one that earns a `README.md` beside the manifest. zopf never reads it, and it is the
right home for anything longer than a `description` — the API it talks to, how to get the token, what a maintainer
should test after changing it.

## Test it before you say it works

The checker runs the connector exactly as zopf does — same stdin object, same cwd, same secret resolution, same timeout,
same rule for finding the JSON on stdout — and checks what came back against the manifest:

```bash
python3 .claude/skills/zopf-connectors/scripts/check_connector.py <connector-dir> \
    --input message="hello" --input channel="#eng"
```

It exits 0 when everything passed, and it catches the failures that are invisible by reading: no JSON object on stdout,
a missing `result`, a declared output that never came back, a null field, an undeclared extra, an unresolvable secret —
and a secret value appearing in the output, which is the one it is worth running for on its own.

Useful flags: `--dry-run` does the static checks and prints the stdin object without launching anything, which is what
to use **first on any connector with a real side effect**; `--input
body=@file.md` reads a value from a file, which is how you feed it the sort of long multi-line text a real
`${node.result}` is; `--expect-error` inverts the verdict so you can check the unhappy path deliberately.

Test both paths, and test the interesting input rather than `"hi"` — text with quotes, backticks and newlines in it,
because that is the case the connector exists for. Where the side effect is real (filing an issue, paging someone), test
against something harmless — a scratch repo, your own machine — and say in your reply what was actually invoked.

## Wiring it into a workflow

That is the `zopf-workflows` skill's job, not this one. Once the connector checks out, a node is:

```yaml
- id: file
  type: connector
  title: File the issue
  connector: github-issue
  inputs:
    title: Release notes needed
    body: ${review.result}
```

`zopf validate <workflow>` then checks the connector exists, that every required input is set, and warns about an input
the manifest doesn't declare. Hand off to that skill for anything about edges,
`on: failure` or the graph.

## Debugging one that misbehaves

Run the checker first — it names most of these outright. `references/debugging.md` is the symptom-to-cause table for the
rest: what "printed no JSON object" really means, why a node fails on exit 0, why a secret resolves in the terminal and
not in the app, and why a connector that works by hand behaves differently under zopf.

## More detail

- `references/manifest.md` — every manifest field, the shorthand forms, and what `zopf validate`
  reports.
- `references/debugging.md` — symptom → cause, and the console notices zopf emits around a connector node.
- `scripts/check_connector.py` — the checker above; `--help` lists the flags.
- The two connectors in this repo's `.zopf/connectors/` are worked examples: `macos-notify` for passing untrusted text
  safely, `github-issue` for named outputs and an optional secret.
