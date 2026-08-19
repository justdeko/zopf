# Debugging a connector

Run `scripts/check_connector.py <dir>` first. It reproduces zopf's side of the contract exactly, so most of what follows
it will name outright. This file is for reading the symptom when you only have the transcript, and for the cases the
checker can't reach.

## Symptom → cause

**"X printed no JSON object, so its plain output is the result"**
Nothing on stdout parsed as a JSON object. Usually the script printed a human sentence, or wrote its JSON to stderr, or
crashed before the print with a traceback. The node still *succeeds* if the exit code was 0, and `${node.result}` is
whatever text was printed — so this is a warning that reads like a success and is worth treating as a failure.

**The node fails and the transcript shows the error text**
The script returned `{"error": …}`. That fails the node **even on exit 0** — the error object alone is enough. Which is
also the trap in reverse: a script that catches an exception, prints an error object and then returns 0 looks fine to
the shell and fails in zopf. That is intended; just don't be surprised by it.

**The node fails with nothing said about why**
Non-zero exit and no `{"error": …}`. The exit code is in the transcript and nothing else is. Always print the error
object as well as exiting non-zero.

**`${node.field}` comes out as the literal text `${node.field}`**
An unresolved reference is left standing verbatim rather than blanked. Either the connector never returned that key, or
it returned it as `null` (dropped, so not a field at all), or the node it names isn't an ancestor of the one reading it.
The run's transcript carries the "declares … but didn't return" warning for the first two.

**"X declares a, b but didn't return them"**
The manifest promises outputs the script didn't produce on this run. Common when a field is only produced on one code
path — return `""` on the others rather than omitting the key, since an absent field and an empty one look different to
the node reading it.

**`${node.result}` is the whole JSON object as a string**
The object had no `result` key, so zopf used the object's own text. Add `result`, and make it the one answer somebody
would want alone.

**"Gave up after Ns — the connector never finished"**
`timeoutSeconds` elapsed and the process was killed; the node fails with exit 124. Either the work genuinely takes
longer, or a network call has no timeout of its own, or — the one to check first — the script is **blocked reading
stdin**. zopf writes the input object and closes the pipe, so a
`sys.stdin.read()` returns; but a script that reads stdin twice, or waits for a second line, waits forever.

**"Couldn't find TOKEN — X reads TOKEN (environment, or keychain "svc")"**
The node failed before launching. The variable is unset in zopf's environment *and* the keychain service holds nothing.
Check the service name is the `-s` value you created it with:
`security find-generic-password -s svc -w` should print the secret.

**It resolves in my terminal but not in the app**
An app launched from Finder inherits a bare environment — a `.zshrc` `export` is invisible to it. zopf asks the login
shell for its environment once at startup to compensate, so this usually works, but it is the first thing to suspect
when a packaged run differs from a `./gradlew :desktopApp:run`
one. The keychain is the reliable path; prefer it for anything that must work from the menu bar or from cron.

**"sometool: command not found", or the script runs by hand and not in zopf**
Same root cause, one step over: PATH. Prefer an absolute path for anything not in `/usr/bin`, or check with
`shutil.which` and return a real error naming the install command.

**Works alone, wrong under zopf: paths**
The script's cwd is the **connector's own directory**, not the repo. A relative path that worked when you ran the script
from the repo root resolves somewhere else. Take paths into the repo as declared inputs.

**Works under zopf, wrong by hand: paths, the other way round**
A multi-file connector reading `templates/note.md` relies on that cwd, so it works in a run and fails the moment you
invoke the script from anywhere else — including while debugging it. Resolve sibling files against
`Path(__file__).resolve().parent` instead, and both work.

**`ModuleNotFoundError` on a helper next to the entry point**
Python puts the *script's* directory on `sys.path`, not the cwd, so a sibling import works when the entry point is
executed directly. It stops working if the helper is reached some other way — a
`python3 -c`, a shell wrapper that `cd`s first, or a helper importing a helper from a subdirectory with no
`__init__.py`. Keep the import graph flat and rooted at the entry point.

**A second JSON object wins**
zopf takes the *last* line that parses as an object. A debug `print(json.dumps(...))` after the real answer replaces it
silently. Debug output goes to stderr.

**The result is `"true"` and the branch still doesn't take**
Everything crossing this boundary is a string. A JSON `true`, a number, a list — each keeps its JSON form as text
(`true`, `3`, `["a"]`). Compare as strings, and prefer returning plain strings so a downstream `${x.field} == ready` is
worth writing.

## The notices zopf writes around a connector node

They appear in the transcript in this order, and the archive keeps them, so a run from cron can be read the next
morning:

| Notice                                      | Means                                                     |
|---------------------------------------------|-----------------------------------------------------------|
| `<name> needs a, b — set them on this node` | required inputs unset; failed before launching            |
| `Couldn't find X — <name> reads X (…)`      | required secret unresolved; failed before launching       |
| `Secrets: TOKEN (keychain)`                 | which secrets resolved, and from where — never the values |
| `Not set, and optional: OTHER`              | an optional secret was left unset                         |
| `<name> ← {"message": …}`                   | the exact stdin object, as sent                           |
| `Output ended: …`                           | the stream broke mid-run                                  |
| `Gave up after Ns …`                        | timeout; killed                                           |
| `<name> printed no JSON object …`           | fell back to plain text                                   |
| `<the error text>`                          | the script's own `{"error": …}`                           |
| `<name> declares x but didn't return it`    | manifest and script disagree                              |

## Reproducing what zopf sends, by hand

The checker does this, but the one-liner is worth knowing:

```bash
cd <connector-dir> && echo '{"message":"hi","title":"zopf","subtitle":"","sound":""}' | ./run.py
```

Note the `cd` — the cwd is part of the contract — and that **every declared input is present**, since that is what zopf
sends. A script tested with only the keys you remembered to type is a script that hasn't been tested with the defaults.
