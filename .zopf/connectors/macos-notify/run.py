#!/usr/bin/env python3
"""Post a macOS notification, under zopf's own icon where the machine allows it.

Inputs arrive as one flat JSON object of strings on stdin; one JSON object goes out on stdout. The
text travels as argv on both delivery paths, never spliced into a shell or an AppleScript source.

A notification wears the icon of the bundle credited with posting it, and osascript posts as Script
Editor, so zopf's icon needs a zopf bundle: notifier/ is built into one under Application Support on
first use. A machine without swiftc still notifies, through osascript, wearing Script Editor's icon.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile

MAX_BODY = 240
EMPTY_BODY = "(no output)"

NOTIFIER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "notifier")
SOURCES = [os.path.join(NOTIFIER, name) for name in ("main.swift", "Info.plist", "icon.icns")]

INSTALL_ROOT = os.path.expanduser("~/Library/Application Support/zopf")
APP = os.path.join(INSTALL_ROOT, "zopf-notify.app")
EXECUTABLE = os.path.join(APP, "Contents", "MacOS", "zopf-notify")
LSREGISTER = (
    "/System/Library/Frameworks/CoreServices.framework/Frameworks"
    "/LaunchServices.framework/Support/lsregister"
)


def collapse(text):
    return " ".join(text.split())


def run(argv, timeout):
    return subprocess.run(argv, capture_output=True, text=True, timeout=timeout)


def is_current():
    if not os.path.isfile(EXECUTABLE):
        return False
    built = os.path.getmtime(EXECUTABLE)
    return all(os.path.getmtime(path) <= built for path in SOURCES)


def build():
    swiftc = shutil.which("swiftc")
    if swiftc is None or not all(os.path.isfile(path) for path in SOURCES):
        return None
    main_swift, info_plist, icns = SOURCES

    os.makedirs(INSTALL_ROOT, exist_ok=True)
    staging = tempfile.mkdtemp(prefix=".zopf-notify-", dir=INSTALL_ROOT)
    app = os.path.join(staging, "zopf-notify.app")
    binary = os.path.join(app, "Contents", "MacOS", "zopf-notify")
    try:
        os.makedirs(os.path.dirname(binary))
        os.makedirs(os.path.join(app, "Contents", "Resources"))
        shutil.copy2(info_plist, os.path.join(app, "Contents", "Info.plist"))
        shutil.copy2(icns, os.path.join(app, "Contents", "Resources", "icon.icns"))
        if run([swiftc, "-O", "-o", binary, main_swift], 45).returncode != 0:
            return None
        codesign = shutil.which("codesign")
        if codesign is not None:
            run([codesign, "--force", "--sign", "-", app], 15)
        if os.path.exists(APP):
            shutil.rmtree(APP)
        # Renaming into place, never copying over the installed bundle, is what evicts the cached
        # icon; without a moved mtime a changed icon.icns never reaches the screen.
        os.rename(app, APP)
        if os.path.isfile(LSREGISTER):
            run([LSREGISTER, "-f", APP], 15)
    except (OSError, subprocess.SubprocessError):
        return None
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    return EXECUTABLE if os.path.isfile(EXECUTABLE) else None


def post_bundled(body, title, subtitle, sound):
    executable = EXECUTABLE if is_current() else build()
    if executable is None:
        return "the notifier bundle would not build"
    try:
        result = run([executable, body, title, subtitle, sound], 30)
    except (OSError, subprocess.SubprocessError) as exc:
        return f"zopf-notify would not run: {exc}"
    if result.returncode != 0:
        return result.stderr.strip() or f"zopf-notify exited {result.returncode}"
    return ""


def post_osascript(body, title, subtitle, sound):
    # Built as text because AppleScript has no way to say "this clause is absent" — but every value
    # still travels in argv, so nothing user-supplied is ever part of the script itself.
    clauses = ["display notification (item 1 of argv) with title (item 2 of argv)"]
    if subtitle:
        clauses.append("subtitle (item 3 of argv)")
    if sound:
        clauses.append("sound name (item 4 of argv)")
    script = "on run argv\n  " + " ".join(clauses) + "\nend run"

    try:
        result = run(["osascript", "-e", script, body, title, subtitle, sound], 10)
    except FileNotFoundError:
        return "osascript is not on PATH; this connector only runs on macOS"
    except subprocess.TimeoutExpired:
        return "osascript did not return within 10s"

    if result.returncode != 0:
        detail = result.stderr.strip() or f"osascript exited {result.returncode}"
        if "-1743" in detail:
            detail += (
                " — this machine has not been allowed to send notifications. "
                "System Settings > Notifications > Script Editor."
            )
        return detail
    return ""


def main():
    raw = sys.stdin.read()
    try:
        inputs = json.loads(raw) if raw.strip() else {}
    except json.JSONDecodeError as exc:
        print(json.dumps({"error": f"inputs were not JSON: {exc}"}))
        return 1

    body = collapse(inputs.get("message", "")) or EMPTY_BODY
    truncated = len(body) > MAX_BODY
    if truncated:
        body = body[: MAX_BODY - 1].rstrip() + "…"

    title = collapse(inputs.get("title", "")) or "zopf"
    subtitle = collapse(inputs.get("subtitle", ""))
    sound = collapse(inputs.get("sound", ""))

    failure = post_bundled(body, title, subtitle, sound)
    if failure:
        sys.stderr.write(f"posting through osascript, under Script Editor's icon: {failure}\n")
        failure = post_osascript(body, title, subtitle, sound)
    if failure:
        print(json.dumps({"error": failure}))
        return 1

    print(json.dumps({"result": body, "shown": body, "truncated": "true" if truncated else "false"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
