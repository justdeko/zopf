# Security

zopf is a personal project. **Use it at your own risk** - no warranty, no liability.

Things that are by design:

- Every node is a subprocess under your account, with your files and logins. There is no sandbox.
- Subprocesses inherit your login shell's environment, `.zshrc` included.
- **Shell nodes can take input from agents.** `${review.result}` in a shell node's command is the whole data-passing
  mechanism and isn't sanitized. Put a gate in front of any step you wouldn't run unread.
- Secrets resolve from the environment or the keychain into a connector's environment, never to a file or the log. A
  connector script you write can still print one.
- The run archive under `~/Library/Application Support/zopf/runs/` keeps full transcripts.
- The app checks GitHub daily for a release; `ZOPF_NO_UPDATE_CHECK=1` or `DO_NOT_TRACK=1` stops it. Nothing else leaves
  your machine, unless you write a script or connector that does that.

A workflow file is executable content. Read it before you run it, same goes for connectors or shell scripts within the workflow.

To report something, please open an issue.
