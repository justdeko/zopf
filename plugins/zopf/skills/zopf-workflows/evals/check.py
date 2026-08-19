#!/usr/bin/env python3
"""Structural checks for zopf workflow eval outputs.

Answers the mechanically-decidable assertions for each eval so grading is
reproducible across iterations. Prose assertions ("the write-up identifies X")
are left to a reader.

Usage: check.py <eval_id> <outputs_dir> [--zopf <path>] [--repo <path>]
"""
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

ZOPF = os.environ.get("ZOPF_BIN") or shutil.which("zopf") or "zopf"

NON_INTERPOLATED = ("title", "choices", "default", "allowedTools")
REF = re.compile(r"\$\{([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\}")


def normalise(wf):
    """PyYAML is YAML 1.1, where a bare `on:` key parses as the boolean True.

    zopf reads YAML 1.2 (kaml), where it stays the string "on" — so this is an
    artifact of the checker, not of the file. Put the key back.
    """
    for e in wf.get("edges", []) or []:
        if True in e:
            e["on"] = e.pop(True)
    return wf


def load(outputs, stem):
    for p in sorted(Path(outputs).rglob("*.y*ml")):
        if p.stem == stem:
            return p, normalise(yaml.safe_load(p.read_text())), p.read_text()
    return None, None, None


def nodes_by_id(wf):
    return {n["id"]: n for n in wf.get("nodes", [])}


def out_edges(wf, nid):
    return [e for e in wf.get("edges", []) if e.get("from") == nid]


def in_edges(wf, nid):
    return [e for e in wf.get("edges", []) if e.get("to") == nid]


def reaches(wf, src, dst):
    stack, seen = [src], set()
    while stack:
        cur = stack.pop()
        if cur == dst:
            return True
        if cur in seen:
            continue
        seen.add(cur)
        stack.extend(e["to"] for e in out_edges(wf, cur))
    return False


def ancestors(wf, nid, seen=None):
    seen = seen or set()
    for e in in_edges(wf, nid):
        if e["from"] not in seen:
            seen.add(e["from"])
            ancestors(wf, e["from"], seen)
    return seen


def type_of(wf, nid):
    return nodes_by_id(wf).get(nid, {}).get("type")


def nodes_of(wf, t):
    return [n for n in wf.get("nodes", []) if n.get("type") == t]


def is_agent(t):
    """`claude` is the retired spelling zopf still reads, so both count here."""
    return t in ("agent", "claude")


def agent_nodes(wf):
    return [n for n in wf.get("nodes", []) if is_agent(n.get("type"))]


def writes_files(n):
    tools = n.get("allowedTools") or []
    return is_agent(n.get("type")) and any(t in tools for t in ("Edit", "Write", "NotebookEdit"))


def validate(repo, name):
    try:
        r = subprocess.run([ZOPF, "validate", name], cwd=repo, capture_output=True, text=True, timeout=90)
        return r.returncode, (r.stdout + r.stderr)
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def no_comments(text):
    return not any(ln.lstrip().startswith("#") for ln in text.splitlines())


def bad_interpolation(wf):
    bad = []
    for n in wf.get("nodes", []):
        for f in NON_INTERPOLATED:
            v = n.get(f)
            for s in (v if isinstance(v, list) else [v]):
                if isinstance(s, str) and REF.search(s):
                    bad.append(f"{n.get('id')}.{f}")
    return bad


def dangling_refs(wf, repo="."):
    bad = []
    for n in wf.get("nodes", []):
        anc = ancestors(wf, n["id"])
        fields = [n.get("prompt", ""), n.get("command", ""), n.get("expression", "")]
        fields += list((n.get("inputs") or {}).values())
        for f in fields:
            if isinstance(f, str):
                for m in REF.finditer(f):
                    if m.group(1) not in anc:
                        bad.append(f"{n['id']} -> {m.group(0)}")
    return bad


def node_texts(n, repo):
    """Every string on a node that zopf interpolates, prompt files included."""
    fields = [n.get("prompt", ""), n.get("command", ""), n.get("expression", "")]
    fields += list((n.get("inputs") or {}).values())
    pf = n.get("promptFile")
    if pf:
        p = Path(repo) / ".zopf" / pf
        if p.is_file():
            fields.append(p.read_text())
    return [f for f in fields if isinstance(f, str)]


def consumed_results(wf, repo):
    """Node ids whose .result is read by some other node."""
    seen = set()
    for n in wf.get("nodes", []):
        for f in node_texts(n, repo):
            for m in REF.finditer(f):
                if m.group(2) == "result" and m.group(1) != n["id"]:
                    seen.add(m.group(1))
    return seen


# Tools that write their diagnostics — the part you actually want — to stderr.
# git log / git show / echo are stdout-native and are not the hazard.
DIAGNOSES_ON_STDERR = re.compile(
    r"\b(npm run (lint|test|build)|npx|eslint|tsc|jest|vitest|"
    r"go (build|test|vet)|cargo|pytest|python -m|mypy|ruff|flake8|"
    r"mvn|gradlew?|make|cmake|rustc|javac|dotnet)\b")


def leaky_shell_nodes(wf, repo):
    """Shell nodes running a stderr-diagnosing tool whose result is consumed
    downstream, but which route neither stream deliberately.

    Any explicit redirection counts as handled: `2>&1` to pull diagnostics into
    the result, or `>&2` to deliberately keep detail out of it and leave the
    result to one summary line. The defect is never thinking about streams,
    which hands the consuming node an empty string.
    """
    bad = []
    for nid in consumed_results(wf, repo):
        n = nodes_by_id(wf).get(nid)
        if not n or n.get("type") != "shell":
            continue
        cmd = n.get("command") or ""
        routed = "2>&1" in cmd or ">&2" in cmd
        if DIAGNOSES_ON_STDERR.search(cmd) and not routed:
            bad.append(nid)
    return sorted(bad)


def result(text, passed, evidence):
    return {"text": text, "passed": bool(passed), "evidence": evidence}


def check_common(wf, raw, repo, stem, out):
    code, log = validate(repo, stem)
    line = next((ln for ln in log.splitlines() if ln.startswith(stem)), log.strip()[:200])
    out.append(result(f"zopf validate reports the workflow as ok (exit 0, no errors)",
                      code == 0, f"exit={code}; {line}"))
    warn = [ln for ln in log.splitlines() if "warning" in ln.lower()]
    out.append(result("zopf validate reports no warnings for this workflow either",
                      not warn, "; ".join(warn) if warn else "no warnings printed"))
    bad = bad_interpolation(wf)
    out.append(result("No ${node.field} reference appears in a title:, choices:, default: or allowedTools: field",
                      not bad, f"offenders: {bad}" if bad else "none"))
    out.append(result("The YAML file contains no comment lines", no_comments(raw),
                      "clean" if no_comments(raw) else "contains # lines"))
    d = dangling_refs(wf)
    out.append(result("Every ${node.field} reference names an ancestor of the node reading it",
                      not d, f"dangling: {d}" if d else "all resolve"))

    desc = (wf.get("description") or "").strip()
    paras = [p for p in desc.split("\n") if p.strip()]
    short = len(paras) <= 2 and len(desc) <= 400
    out.append(result(
        "The description is short — at most two paragraphs and under 400 characters",
        short, f"{len(paras)} paragraph(s), {len(desc)} chars"))

    leaky = leaky_shell_nodes(wf, repo)
    out.append(result(
        "A shell node running a build/test/lint tool whose result is read downstream redirects stderr into stdout",
        not leaky, f"stdout-only but consumed: {leaky}" if leaky else "no stderr-diagnosing tool loses its output"))


def eval0(outputs, repo):
    out = []
    p, wf, raw = load(outputs, "lint-fix")
    out.append(result("A workflow file exists at lint-fix.yaml and its name: field is lint-fix",
                      wf is not None and wf.get("name") == "lint-fix",
                      f"found {p}" if p else "no lint-fix.yaml in outputs"))
    if not wf:
        return out
    check_common(wf, raw, repo, "lint-fix", out)

    lint = next((n for n in nodes_of(wf, "shell") if "lint" in (n.get("command") or "")), None)
    fail_edges = [e for e in wf.get("edges", []) if e.get("on") == "failure"]
    ok = lint and any(e["from"] == lint["id"] for e in fail_edges)
    out.append(result("The lint-fixing path is reached by an `on: failure` edge, not a branch on ${...exitCode}",
                      ok, f"failure edges: {[(e['from'], e['to']) for e in fail_edges]}"))

    exitcode_branch = [n for n in nodes_of(wf, "branch") if "exitCode" in (n.get("expression") or "")]
    out.append(result("No branch node keys off ${...exitCode}, which can only be 0 when the branch runs",
                      not exitcode_branch, f"offenders: {[n['id'] for n in exitcode_branch]}" if exitcode_branch else "none"))

    gates = nodes_of(wf, "gate")
    writers = [n for n in wf.get("nodes", []) if writes_files(n)]
    gated = writers and all(any(reaches(wf, g["id"], w["id"]) for g in gates) for w in writers)
    out.append(result("A gate sits between the lint run and every node that edits files",
                      gated, f"gates={[g['id'] for g in gates]} writers={[w['id'] for w in writers]}"))

    notifiers = nodes_of(wf, "connector")
    both = False
    if lint and notifiers:
        clean = [e["to"] for e in out_edges(wf, lint["id"]) if e.get("on", "success") == "success"]
        broke = [e["to"] for e in out_edges(wf, lint["id"]) if e.get("on") == "failure"]
        both = (any(reaches(wf, c, n["id"]) for c in clean for n in notifiers)
                and any(reaches(wf, b, n["id"]) for b in broke for n in notifiers))
    out.append(result("A notification connector is reachable on both the clean path and the fixed path",
                      both, f"connectors={[n['id'] for n in notifiers]}"))
    return out


def eval1(outputs, repo):
    out = []
    p, wf, raw = load(outputs, "triage-commit")
    out.append(result("A workflow file exists at triage-commit.yaml and its name: field is triage-commit",
                      wf is not None and wf.get("name") == "triage-commit",
                      f"found {p}" if p else "no triage-commit.yaml in outputs"))
    if not wf:
        return out
    check_common(wf, raw, repo, "triage-commit", out)

    branches = nodes_of(wf, "branch")
    direct = []
    for b in branches:
        for m in REF.finditer(b.get("expression") or ""):
            if (is_agent(type_of(wf, m.group(1))) and m.group(2) == "result"
                    and ("==" in b["expression"] or "!=" in b["expression"])):
                direct.append(f"{b['id']}: {b['expression']}")
    out.append(result("The branch does not compare == directly against a claude node's ${...result}",
                      not direct, f"offenders: {direct}" if direct else "none"))

    # Either shape is correct: a schema field on the claude node (preferred), or a shell
    # node reducing its prose to a token (what you write when the producer can't declare one).
    comparable = []
    for b in branches:
        for m in REF.finditer(b.get("expression") or ""):
            src = nodes_by_id(wf).get(m.group(1))
            if not src:
                continue
            if is_agent(type_of(wf, m.group(1))):
                declared = [f.get("name") if isinstance(f, dict) else f for f in (src.get("schema") or [])]
                if m.group(2) in declared:
                    comparable.append(f"{src['id']} declares schema field {m.group(2)} for {b['id']}")
            elif type_of(wf, m.group(1)) == "shell":
                if any(is_agent(type_of(wf, r.group(1))) for r in REF.finditer(src.get("command") or "")):
                    comparable.append(f"{src['id']} reduces a claude result for {b['id']}")
    out.append(result("The branch reads a comparable value: a schema field on the claude node, or a shell node that "
                      "reduced its prose to a token",
                      bool(comparable), "; ".join(comparable) if comparable else "branch reads something else"))

    arms_ok = bool(branches) and all(
        sum(1 for e in out_edges(wf, b["id"]) if e.get("when") is True) == 1
        and sum(1 for e in out_edges(wf, b["id"]) if e.get("when") is False) == 1
        and all("when" in e for e in out_edges(wf, b["id"]))
        for b in branches)
    out.append(result("The branch has exactly one when: true and one when: false outgoing edge",
                      arms_ok, f"edges: {[(b['id'], [(e['to'], e.get('when')) for e in out_edges(wf, b['id'])]) for b in branches]}"))

    gh = next((n for n in nodes_of(wf, "connector") if n.get("connector") == "github-issue"), None)
    out.append(result("The github-issue node supplies the `title` input its manifest declares required",
                      bool(gh and (gh.get("inputs") or {}).get("title")),
                      f"inputs={list((gh.get('inputs') or {}).keys())}" if gh else "no github-issue node"))
    mn = next((n for n in nodes_of(wf, "connector") if n.get("connector") == "macos-notify"), None)
    out.append(result("The macos-notify node supplies the `message` input its manifest declares required",
                      bool(mn and (mn.get("inputs") or {}).get("message")),
                      f"inputs={list((mn.get('inputs') or {}).keys())}" if mn else "no macos-notify node"))

    claudes = agent_nodes(wf)
    fed = any(any(type_of(wf, m.group(1)) == "shell" for m in REF.finditer(c.get("prompt") or "")) for c in claudes)
    bashy = [c["id"] for c in claudes if "Bash" in (c.get("allowedTools") or [])]
    out.append(result("A shell node feeds the claude node the diff by reference rather than granting it Bash",
                      fed and not bashy, f"fed_by_shell={fed} bash_grants={bashy}"))
    return out


def eval2(outputs, repo):
    out = []
    p, wf, raw = load(outputs, "nightly")
    out.append(result("A corrected nightly.yaml was produced", wf is not None,
                      f"found {p}" if p else "no nightly.yaml in outputs"))
    if not wf:
        return out
    check_common(wf, raw, repo, "nightly", out)

    exitcode = [n for n in nodes_of(wf, "branch") if "exitCode" in (n.get("expression") or "")]
    out.append(result("The unreachable ${build.exitCode} == 0 branch is gone",
                      not exitcode, f"still present: {[n['id'] for n in exitcode]}" if exitcode else "removed"))

    arms_ok = all(
        sum(1 for e in out_edges(wf, b["id"]) if e.get("when") is True) == 1
        and sum(1 for e in out_edges(wf, b["id"]) if e.get("when") is False) == 1
        for b in nodes_of(wf, "branch"))
    out.append(result("Any remaining branch has both a when: true and a when: false arm",
                      arms_ok, f"branches={[b['id'] for b in nodes_of(wf, 'branch')]}"))

    typo = [n["id"] for n in wf.get("nodes", []) if "promtFile" in n]
    pf = [n for n in wf.get("nodes", []) if n.get("promptFile")]
    exists = all((Path(repo) / ".zopf" / n["promptFile"]).exists() for n in pf) if pf else False
    out.append(result("promptFile is spelled correctly and names a file that exists",
                      not typo and pf and exists,
                      f"typo_nodes={typo} promptFile={[n['promptFile'] for n in pf]} exists={exists}"))

    build = next((n for n in wf.get("nodes", []) if n["id"] == "build"), None) or next(iter(nodes_of(wf, "shell")), None)
    summary = next((n for n in agent_nodes(wf)), None)
    via_failure = bool(build and summary and any(
        e.get("on") == "failure" and reaches(wf, e["to"], summary["id"]) for e in out_edges(wf, build["id"])))
    out.append(result("The summary node is reached via an `on: failure` edge rather than a branch",
                      via_failure, f"build={build and build['id']} summary={summary and summary['id']}"))

    cleanup = next((n for n in nodes_of(wf, "shell") if "rm " in (n.get("command") or "")), None)
    both = False
    if build and cleanup:
        succ = [e["to"] for e in out_edges(wf, build["id"]) if e.get("on", "success") == "success"]
        fail = [e["to"] for e in out_edges(wf, build["id"]) if e.get("on") == "failure"]
        both = any(reaches(wf, s, cleanup["id"]) for s in succ) and any(reaches(wf, f, cleanup["id"]) for f in fail)
    out.append(result("Cleanup is reachable on both the build-succeeded and build-failed paths",
                      both, f"cleanup={cleanup and cleanup['id']}"))
    return out


def main():
    eid, outputs = int(sys.argv[1]), sys.argv[2]
    repo = sys.argv[4] if len(sys.argv) > 4 else str(Path(outputs).parent / "repo")
    res = {0: eval0, 1: eval1, 2: eval2}[eid](outputs, repo)
    print(json.dumps({"expectations": res}, indent=2))


if __name__ == "__main__":
    main()
