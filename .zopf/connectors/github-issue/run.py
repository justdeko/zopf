#!/usr/bin/env python3
"""File a GitHub issue with `gh`, and return its number and URL as addressable fields.

Inputs arrive as one flat JSON object of strings on stdin; one JSON object goes out on stdout.

Not a shell node. The body is normally a whole ${node.result} and reaches `gh` as an argv element
that no shell re-parses. The issue number comes back as its own field instead of a line of text
for the next node to slice up.
"""

import json
import re
import subprocess
import sys

ISSUE_URL = re.compile(r"https://\S+/issues/(\d+)")


def main():
    raw = sys.stdin.read()
    try:
        inputs = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError as exc:
        print(json.dumps({"error": f"inputs were not JSON: {exc}"}))
        return 1

    title = inputs.get("title", "").strip()
    if not title:
        print(json.dumps({"error": "title is required"}))
        return 1

    argv = ["gh", "issue", "create", "--title", title, "--body", inputs.get("body", "")]
    if inputs.get("repo", "").strip():
        argv += ["--repo", inputs["repo"].strip()]
    for label in (l.strip() for l in inputs.get("labels", "").split(",")):
        if label:
            argv += ["--label", label]

    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=45)
    except FileNotFoundError:
        print(json.dumps({"error": "gh is not on PATH — brew install gh, then gh auth login"}))
        return 1
    except subprocess.TimeoutExpired:
        print(json.dumps({"error": "gh did not return within 45s"}))
        return 1

    if result.returncode != 0:
        # gh's own message names the failure — no auth, no label, no repo
        print(json.dumps({"error": result.stderr.strip() or f"gh exited {result.returncode}"}))
        return 1

    # match so extra output fails visibly
    url = result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
    match = ISSUE_URL.search(url)
    if not match:
        print(json.dumps({"error": f"gh succeeded but printed no issue URL: {url!r}"}))
        return 1

    print(json.dumps({"result": url, "url": url, "number": match.group(1)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
