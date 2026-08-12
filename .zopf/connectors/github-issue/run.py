#!/usr/bin/env python3
"""File a GitHub issue with `gh`, and return its number and URL as addressable fields.

Inputs arrive as one flat JSON object of strings on stdin; one JSON object goes out on stdout.

Not a shell node, for two reasons. The body is normally a whole ${node.result} — a Claude review,
with backticks and quotes in it — which reaches `gh` here as an argv element rather than as
something a shell re-parses. And the useful part of the answer is the issue number, which a shell
node could only hand on as a line of text for the next node to slice up.
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
        # gh's own message says which of the several ways this fails it was — no auth, no such
        # label, no repo — and paraphrasing it would only lose that.
        print(json.dumps({"error": result.stderr.strip() or f"gh exited {result.returncode}"}))
        return 1

    # gh prints the issue URL and nothing else on success. Reported rather than assumed, so a
    # future gh that prints something extra fails visibly instead of handing on a truncated link.
    url = result.stdout.strip().splitlines()[-1] if result.stdout.strip() else ""
    match = ISSUE_URL.search(url)
    if not match:
        print(json.dumps({"error": f"gh succeeded but printed no issue URL: {url!r}"}))
        return 1

    print(json.dumps({"result": url, "url": url, "number": match.group(1)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
