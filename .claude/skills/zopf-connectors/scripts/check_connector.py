#!/usr/bin/env python3
"""Run a zopf connector the way zopf runs it, and check what came back against its manifest.

This mirrors ConnectorRunner and ConnectorOutput.parse rather than approximating them: the same
stdin object (manifest defaults, then your overrides), the same cwd (the connector's own
directory), the same secret resolution (environment, then keychain), the same timeout, and the
same rule for finding the JSON object on stdout. So a connector that passes here behaves the same
way inside a run.

Usage:
    check_connector.py <connector-dir> [--input name=value ...] [--dry-run] [--expect-error]

    --input      set one input. Repeatable. A value of @path reads the file, which is how you
                 hand it the sort of long multi-line text a ${node.result} really is.
    --dry-run    static checks and the stdin object only; the script is never launched. Use this
                 first on a connector whose side effect you don't want yet.
    --expect-error  a run that returns {"error": ...} is the pass rather than the failure, for
                 checking the unhappy path.

Exit 0 when every check passed, 1 when one failed, 2 when the connector could not be loaded.
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

RESERVED = ("result", "error", "exitCode")

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
if not sys.stdout.isatty():
    GREEN = RED = YELLOW = DIM = RESET = ""

failures = 0


def ok(msg):
    print(f"  {GREEN}ok{RESET}   {msg}")


def bad(msg):
    global failures
    failures += 1
    print(f"  {RED}FAIL{RESET} {msg}")


def note(msg):
    print(f"  {YELLOW}note{RESET} {msg}")


def info(msg):
    print(f"       {DIM}{msg}{RESET}")


def die(msg):
    print(f"{RED}{msg}{RESET}", file=sys.stderr)
    sys.exit(2)


def load_manifest(directory):
    """Everything ConnectorStore.load checks, with the same precedence: the directory name wins."""
    path = directory / "connector.json"
    if not path.exists():
        die(f"No connector.json in {directory}")
    try:
        manifest = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        die(f"connector.json is not valid JSON: {exc}")
    if not isinstance(manifest, dict):
        die("connector.json must hold one JSON object")

    declared = manifest.get("name", "")
    if declared and declared != directory.name:
        note(f'manifest says "{declared}" but the directory is "{directory.name}" — the directory wins')
    manifest["name"] = directory.name
    return manifest


def normalise(entries, kind):
    """`outputs` and `env` accept a bare string as shorthand; `inputs` never does."""
    out = []
    for entry in entries or []:
        if isinstance(entry, str):
            if kind == "inputs":
                bad(f'inputs entry "{entry}" is a bare string; inputs must be objects with a "name"')
                continue
            out.append({"name": entry})
        elif isinstance(entry, dict) and entry.get("name"):
            out.append(entry)
        else:
            bad(f"{kind} has an entry with no name: {entry!r}")
    return out


def as_text(value):
    """ConnectorOutput's rule: null is not a field, a string is itself, anything else keeps its JSON."""
    if value is None:
        return None
    if isinstance(value, str):
        return value
    return json.dumps(value, separators=(",", ":"))


def parse_output(stdout):
    """The last line that is a JSON object wins; failing that, the whole text as one object."""
    lines = stdout.splitlines()
    for line in reversed(lines):
        stripped = line.strip()
        if stripped.startswith("{"):
            try:
                parsed = json.loads(stripped)
            except json.JSONDecodeError:
                continue
            if isinstance(parsed, dict):
                return parsed, True
    text = stdout.strip()
    if text.startswith("{"):
        try:
            parsed = json.loads(text)
            if isinstance(parsed, dict):
                return parsed, True
        except json.JSONDecodeError:
            pass
    return {"result": text}, False


def keychain(service):
    if not shutil.which("security"):
        return None
    result = subprocess.run(
        ["/usr/bin/security", "find-generic-password", "-s", service, "-w"],
        capture_output=True,
        text=True,
    )
    return result.stdout.strip() or None if result.returncode == 0 else None


def resolve_secrets(declared):
    """SecretResolver's order: the environment first, then the keychain service, then missing."""
    resolved = []
    for secret in declared:
        name = secret["name"]
        value = os.environ.get(name) or None
        source = "environment"
        if value is None and secret.get("keychain"):
            value = keychain(secret["keychain"])
            source = f'keychain "{secret["keychain"]}"'
        if value is None:
            source = "not set"
        resolved.append((secret, value, source))
    return resolved


def build_stdin(inputs, overrides):
    """resolveInputs: every declared input is always sent, defaulted, then the node's values on top."""
    payload = {entry["name"]: entry.get("default", "") for entry in inputs}
    payload.update(overrides)
    return payload


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--input", action="append", default=[], metavar="NAME=VALUE")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--expect-error", action="store_true")
    args = parser.parse_args()

    directory = args.directory.resolve()
    if not directory.is_dir():
        die(f"{directory} is not a directory")

    print(f"{directory.name}")
    manifest = load_manifest(directory)
    inputs = normalise(manifest.get("inputs"), "inputs")
    outputs = normalise(manifest.get("outputs"), "outputs")
    env = normalise(manifest.get("env"), "env")

    if not manifest.get("description", "").strip():
        note('no "description" — the connectors screen and the node editor both show it')

    script = directory / manifest.get("run", "run.sh")
    if not script.exists():
        bad(f'"run": "{manifest.get("run", "run.sh")}" names {script.name}, which is not in this directory')
        print(f"\n{RED}{failures} check(s) failed{RESET}")
        return 1
    ok(f"connector.json parses, {script.name} is present")

    if not os.access(script, os.X_OK):
        # zopf chmod +x's the entry point before launching it, so do the same rather than failing
        # on something that would have run — but say so, since the committed file is still wrong.
        note(f"{script.name} is not executable — zopf sets the bit for you, but commit it set: chmod +x {script.name}")
        script.chmod(script.stat().st_mode | 0o111)
    first = script.read_text(errors="replace").splitlines()[:1]
    if not (first and first[0].startswith("#!")):
        bad(f"{script.name} has no shebang, so the kernel has no way to know what runs it")

    for entry in outputs:
        if entry["name"] in ("result", "error", "exitCode"):
            note(f'outputs declares "{entry["name"]}", which is zopf\'s own field and is not addressable as one')

    overrides = {}
    for pair in args.input:
        if "=" not in pair:
            die(f"--input wants NAME=VALUE, got {pair!r}")
        name, value = pair.split("=", 1)
        if value.startswith("@"):
            value = Path(value[1:]).read_text()
        overrides[name] = value

    undeclared = [name for name in overrides if not any(e["name"] == name for e in inputs)]
    for name in undeclared:
        note(f'you set "{name}", which the manifest does not declare — zopf passes it but warns, and the node editor offers no field for it')

    missing = [
        entry["name"]
        for entry in inputs
        if entry.get("required") and not (overrides.get(entry["name"]) or entry.get("default", ""))
    ]
    if missing:
        bad(f"required input(s) not supplied: {', '.join(missing)} — pass --input, or give them a default")
        print(f"\n{RED}{failures} check(s) failed{RESET}")
        return 1

    payload = build_stdin(inputs, overrides)
    print(f"  stdin {json.dumps(payload)[:400]}")

    non_strings = [k for k, v in payload.items() if not isinstance(v, str)]
    if non_strings:
        bad(f"every input value must be a string; these are not: {', '.join(non_strings)}")

    secrets = resolve_secrets(env)
    for secret, value, source in secrets:
        if value is None and secret.get("required", True):
            bad(
                f'{secret["name"]} is required and is not set — zopf fails the node before launching. '
                + (
                    f'security add-generic-password -a "$USER" -s {secret["keychain"]} -w'
                    if secret.get("keychain")
                    else "export it, or give it a \"keychain\" service name"
                )
            )
        elif value is None:
            info(f'{secret["name"]}: not set, and optional')
        else:
            info(f'{secret["name"]}: {source}')

    if args.dry_run:
        print(f"\n{DIM}--dry-run: the script was not launched{RESET}")
        return 1 if failures else 0
    if failures:
        print(f"\n{RED}{failures} check(s) failed before launching{RESET}")
        return 1

    timeout = manifest.get("timeoutSeconds", 120)
    child_env = dict(os.environ)
    child_env.update({s["name"]: v for s, v, _ in secrets if v is not None})
    try:
        result = subprocess.run(
            [str(script)],
            cwd=directory,
            input=json.dumps(payload) + "\n",
            capture_output=True,
            text=True,
            timeout=timeout,
            env=child_env,
        )
    except PermissionError:
        bad(f"{script.name} is not executable and could not be run: chmod +x {script.name}")
        return 1
    except OSError as exc:
        bad(f"could not launch {script.name}: {exc} — check the shebang names something installed")
        return 1
    except subprocess.TimeoutExpired:
        bad(f"no exit within timeoutSeconds ({timeout}s) — zopf kills it here and fails the node with exit 124")
        return 1

    def redact(line):
        for secret, value, _ in secrets:
            if value and len(value) >= 6:
                line = line.replace(value, f'${{{secret["name"]}}}')
        return line

    if result.stderr.strip():
        for line in result.stderr.strip().splitlines()[:10]:
            info(f"stderr: {redact(line)}")

    parsed, saw_json = parse_output(result.stdout)
    if not saw_json:
        bad("no JSON object on stdout — zopf warns and hands the plain text on as the result")
        info(f"stdout was: {result.stdout.strip()[:200]!r}")
        print(f"\n{RED}{failures} check(s) failed{RESET}")
        return 1
    ok("exactly one JSON object was found on stdout")

    error = as_text(parsed.get("error"))
    error = error if error and error.strip() else None

    if args.expect_error:
        if error is None:
            bad('--expect-error, but nothing came back under "error"')
        else:
            ok(f"error path reported: {error[:160]}")
        if result.returncode == 0:
            note('an {"error": ...} fails the node on its own, but exit non-zero as well — the two are the same message to a shell')
    elif error is not None:
        bad(f"returned an error, so the node fails and everything downstream is skipped: {error[:200]}")
    elif result.returncode != 0:
        bad(f"exit {result.returncode} with no \"error\" field — the node fails and the transcript never says why")
    else:
        ok(f"exit 0, no error")

    if error is not None:
        pass
    elif "result" in parsed:
        text = as_text(parsed["result"])
        if text is None:
            bad('"result" is null, so ${node.result} is empty')
        else:
            ok(f"result: {text[:200]!r}")
    else:
        bad('no "result" key, so ${node.result} is the whole object as text — give the one answer somebody wants on its own')

    fields = {k: as_text(v) for k, v in parsed.items() if k not in ("result", "error")}
    dropped = [k for k, v in fields.items() if v is None]
    fields = {k: v for k, v in fields.items() if v is not None}

    for key in dropped:
        bad(f'"{key}" came back null, which is not a field at all — return "" for an output that has no value this time')
    for key in fields:
        if key == "exitCode":
            note('"exitCode" is zopf\'s own and is not shadowed — the process\'s real exit code is what a later node reads')

    if not args.expect_error:
        declared_names = [entry["name"] for entry in outputs]
        absent = [name for name in declared_names if name not in fields and name != "result"]
        unmentioned = [name for name in absent if name not in dropped]
        if unmentioned:
            bad(
                f"declares {', '.join(unmentioned)} but did not return "
                f"{'it' if len(unmentioned) == 1 else 'them'} — zopf warns on every run"
            )
        elif declared_names and not absent:
            ok(f"every declared output came back: {', '.join(declared_names)}")

        extra = [k for k in fields if k not in declared_names and k not in RESERVED]
        if extra:
            note(f"returns {', '.join(extra)} without declaring {'it' if len(extra) == 1 else 'them'} in outputs — addressable, but the node editor offers no chip and nobody finds {'it' if len(extra) == 1 else 'them'}")

    haystack = result.stdout + result.stderr
    leaked = [s["name"] for s, v, _ in secrets if v and len(v) >= 6 and v in haystack]
    if leaked:
        bad(f"the value of {', '.join(leaked)} appears in the output — the console is the run archive, so that is a token to rotate now")
    elif any(v for _, v, _ in secrets):
        ok("no secret value appeared in stdout or stderr")

    if failures:
        print(f"\n{RED}{failures} check(s) failed{RESET}")
        return 1
    print(f"\n{GREEN}all checks passed{RESET}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
