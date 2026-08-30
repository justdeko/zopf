# Five graph shapes

Almost every zopf workflow is one of these, or two of them stacked. Each is complete and would validate as written,
given a workspace with `self` and the connector it names.

The commands and connector names in these examples are placeholders — `npm test`, `pytest`,
`macos-notify`, `github-issue`. Substitute whatever the repo in front of you actually uses, and list
`<workspace>/connectors/` to find out which connectors really exist there. The shapes are the point; the commands are
not.

## 1. Gather, then judge

The most useful shape in the format, and the one to reach for by default. A `shell` node collects context and hands it
to an `agent` node through a reference.

```yaml
name: review-diff
description: |-
  Read the working tree's diff and say whether it should ship.

  The reviewer gets the diff by reference rather than a Bash grant, so it is read-only.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: changes
    type: shell
    title: What changed
    command: git --no-pager diff --stat HEAD && echo && git --no-pager diff HEAD | head -800
  - id: review
    type: agent
    title: Review the diff
    allowedTools:
      - Read
      - Glob
      - Grep
    prompt: |
      Review the uncommitted changes in this repo. Here is the diff, truncated at 800 lines:

      ${changes.result}

      Open the files around the hunks — a diff on its own hides the caller that makes a change
      wrong. Report only what you can point at a line for, and say "nothing worth flagging" if
      that is the answer.
edges:
  - from: changes
    to: review
```

**Why it beats giving the node `Bash`:** the node cannot wander, cannot stop for a permission mid-run, and the exact
input it judged is in the archive. Whenever you are about to grant `Bash` so a model can look something up, ask whether
a `shell` node upstream could just hand it over.

## 2. The recovery edge

Only spend a turn when something goes wrong. This is what `on: failure` is for, and it needs no branch, no exit-code
file and no node that has to succeed in order to report that it didn't.

```yaml
name: verify
description: Run the suite, and only when it goes red spend a turn on it.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: tests
    type: shell
    title: The suite
    command: pytest -q 2>&1
  - id: passed
    type: connector
    title: Say it is green
    connector: macos-notify
    inputs:
      title: verify
      message: All green.
  - id: diagnose
    type: agent
    title: Fix the failing tests
    permissionMode: acceptEdits
    allowedTools:
      - Read
      - Edit
      - Write
      - Glob
      - Grep
      - Bash
    prompt: |
      `pytest` just failed in this repo. Its output:

      ${tests.result}

      Find the cause and fix it. A test that fails because the behaviour it asserts was
      deliberately changed should have the test updated; a test that fails because the behaviour
      broke should have the code fixed. Say which of the two you concluded before you edit.
edges:
  - from: tests
    to: passed
  - from: tests
    to: diagnose
    on: failure
```

Note that both edges leave the same node. Exactly one of them will arrive.

## 3. The diamond

Two things run at once against the same state, and a third joins them. A diamond joins once — the
`verdict` node waits for both and runs a single time.

```yaml
name: review-changes
description: |-
  Read the diff and run the suite at once, then join both into one verdict.

  The suite ends in `echo "exit=$?"` so it succeeds either way and the verdict sees both results.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: changes
    type: shell
    title: What changed
    command: git --no-pager diff HEAD | head -800
  - id: review
    type: agent
    title: Review the diff
    allowedTools:
      - Read
      - Glob
      - Grep
    prompt: |
      Review these changes:

      ${changes.result}
  - id: tests
    type: shell
    title: The suite
    command: npm test 2>&1; echo "exit=$?"
  - id: verdict
    type: agent
    title: Ship or hold
    allowedTools:
      - Read
      - Glob
      - Grep
    prompt: |
      Two things ran against the same working tree. Turn them into one answer.

      The review said:

      ${review.result}

      The suite said this, ending in the exit code it reported:

      ${tests.result}

      A green suite does not clear a correctness finding, and a red suite over an unrelated flake
      does not sink a clean diff. Answer in at most eight lines, and make the last line exactly one
      of:

      SHIP: <the reason, in one clause>
      HOLD: <what has to happen first, in one clause>
edges:
  - from: changes
    to: review
  - from: changes
    to: tests
  - from: review
    to: verdict
  - from: tests
    to: verdict
```

Two things worth copying here. The `; echo "exit=$?"` trick makes a node that **reports** a failure rather than
**being** one, which is what a joining node needs — a plain failing `tests` node would skip `verdict` entirely. And the
last prompt pins the output format, because `${verdict.result}` is only the final message and a downstream step needs to
know what shape it will be in. If the downstream step needs a *value* rather than a shape it can read — a branch, a
connector input — declare a `schema:` on that node instead of asking for the format in prose.

## 4. The approval gate

Nothing irreversible happens until a person says yes. The analysis half is safe to run whenever; only what comes after
the gate writes anything.

```yaml
name: claude-md-audit
description: |-
  Read the recent commits against CLAUDE.md, show the drift, and correct the file once you agree.

  Nothing is written until the gate is approved, so the audit itself is safe to run whenever.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: survey
    type: shell
    title: Recent commits
    command: git --no-pager log --oneline -40
  - id: audit
    type: agent
    title: Where has the doc drifted?
    allowedTools:
      - Read
      - Glob
      - Grep
    prompt: |
      These commits landed recently:

      ${survey.result}

      Read CLAUDE.md and list, precisely, where it no longer describes the code.
  - id: approve
    type: gate
    title: Apply these corrections to CLAUDE.md?
    prompt: ${audit.result}
  - id: apply
    type: agent
    title: Correct CLAUDE.md
    permissionMode: acceptEdits
    allowedTools:
      - Read
      - Edit
      - Glob
      - Grep
    prompt: |
      An audit found the following, and a human has approved acting on it:

      ${audit.result}

      Apply the corrections. Confirm each one against the code first — the audit was a separate
      session and may have misread something. Skip anything you cannot confirm and say which.
edges:
  - from: survey
    to: audit
  - from: audit
    to: approve
  - from: approve
    to: apply
```

A gate's `title:` **is** the question, so write it as one. Its `prompt:` is what you read before answering, and it is
interpolated, so quote the output you are approving into it. A gate without one shows nothing but its title.

A whole result is fine there: the run shows the first lines and expands on a click. To keep the gate shorter than what
the next node needs, give the node before it a `schema:` and split the two. The gate quotes `${audit.headline}` while
`${audit.result}` goes on to the node that does the work.

Rejecting stops the run rather than skipping the step, so never put a cleanup node behind a gate expecting it to run
either way.

Headless, `zopf run` refuses a gate by default (exit 2) — `--on-gate approve` is how cron gets past one, which is a
decision the person setting up the cron job makes explicitly.

## 5. The branch

Two paths, one taken. Use it when both arms are real work; use an `on: failure` edge when one arm is just "it broke".

```yaml
name: release-check
description: |-
  Ask which environment, then take the path that environment needs.

  choices: makes it answerable from the menu bar, and gives the branch an exact string to match.
repos:
  - id: self
    path: ..
defaults:
  repo: self
nodes:
  - id: ask
    type: input
    title: Which environment?
    default: staging
    choices:
      - staging
      - prod
    prompt: Which environment is this release going to?
  - id: which
    type: branch
    title: Production?
    expression: ${ask.result} == prod
  - id: full
    type: shell
    title: Full suite
    command: make check 2>&1
  - id: quick
    type: shell
    title: Unit tests only
    command: make test 2>&1
edges:
  - from: ask
    to: which
  - from: which
    to: full
    when: true
  - from: which
    to: quick
    when: false
```

Because `choices:` makes the answer one of a fixed set, `${ask.result} == prod` is worth writing — the string is exactly
what the person picked. That is the case branching is good at. Branching on a
`agent` node's prose needs a `shell` node in between to reduce it to a token first (see the SKILL.md section on
expressions).

## Anti-patterns

| looks like                                                   | why it fails                                                            | write instead                                                                                           |
|--------------------------------------------------------------|-------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `expression: ${build.exitCode} == 0`                         | a non-zero exit already failed the node, so the branch never runs       | an `on: failure` edge                                                                                   |
| `expression: ${review.result} == SHIP`                       | `result` is a whole message; `==` is exact                              | a `schema:` field on the agent node, branched on directly; or a `shell` node that reduces it to a token |
| `expression: ${review.findings} != ""` on an `array` field   | a nested field arrives as JSON text, never a value                      | declare plain fields for whatever the branch compares                                                   |
| a cleanup node behind a gate                                 | a rejected gate is STOPPED, which skips everything downstream           | put cleanup on a path that doesn't cross the gate                                                       |
| `title: Fix ${plan.result}`                                  | `title` is not interpolated                                             | put the reference in `prompt`                                                                           |
| `command: make build` for a node whose output feeds a prompt | `result` is stdout only                                                 | `make build 2>&1`                                                                                       |
| `allowedTools: [Bash]` so the model can run `git diff`       | costs permissions and turns                                             | a `shell` node upstream, handed over by reference                                                       |
| `allowedTools` without `Bash`, to keep a node read-only      | the list grants, never takes a tool away                                | feed it from a `shell` node                                                                             |
| a hand-written `position:`                                   | one manual position freezes auto-layout for the whole graph             | leave it out                                                                                            |
| `# a comment explaining the graph`                           | the editor rewrites the file on save and eats it                        | `description:` and `title:`                                                                             |
| a graph of nothing but `shell` nodes                         | no judgment anywhere, so zopf is buying you nothing over CI or a script | say so, and offer the simpler tool                                                                      |
| a four-paragraph `description:`                              | only the first paragraph is ever shown in the list                      | one line, and a second only if the shape is surprising                                                  |
