#!/bin/bash
# condense a claude code transcript to what a workflow is built from

session="$1"
limit="${2:-60000}"
projects="${CLAUDE_CONFIG_DIR:-$HOME/.claude}/projects"

if ! command -v jq >/dev/null 2>&1; then
  echo "(no digest: jq is not on PATH, so work from the conversation itself)"
  exit 0
fi

transcript="$(find "$projects" -maxdepth 2 -name "$session.jsonl" 2>/dev/null | head -1)"
if [ -z "$session" ] || [ -z "$transcript" ]; then
  echo "(no digest: no transcript for session '$session' under $projects, so work from the conversation itself)"
  exit 0
fi

digest="$(jq -r '
  def clip($n): if length > $n then .[0:$n] + " [...]" else . end;
  def oneline: gsub("\\s+"; " ");
  def noise: test("^\\s*<(local-command|command-name|command-message|command-args|system-reminder|task-notification)");
  def resulttext: if type == "string" then . else (map(select(.type == "text") | .text) | join(" ")) end;

  if .isMeta == true or .isSidechain == true then empty
  elif .type == "user" then
    (.message.content
      | if type == "string" then
          (select(noise | not) | "\nUSER: " + clip(2000))
        else
          .[]
          | if .type == "text" then (.text | select(noise | not) | "\nUSER: " + clip(2000))
            elif .type == "tool_result" and .is_error == true then
              "  FAILED: " + (.content | resulttext | oneline | clip(300))
            else empty end
        end)
  elif .type == "assistant" then
    (.message.content[]?
      | if .type == "text" then "CLAUDE: " + (.text | oneline | clip(300))
        elif .type == "tool_use" then
          (.input as $in
            | if .name == "Bash" then "  BASH: " + ($in.command | oneline | clip(300))
              elif .name == "Edit" or .name == "Write" or .name == "NotebookEdit" then "  EDIT: " + ($in.file_path // $in.notebook_path // "?")
              elif .name == "Agent" or .name == "Task" then "  SUBAGENT: " + (($in.description // "") + " :: " + ($in.prompt // "" | oneline) | clip(300))
              elif .name == "Skill" then "  SKILL: " + ($in.skill // "?")
              elif .name == "Read" or .name == "Grep" or .name == "Glob" or .name == "ToolSearch" then empty
              else "  TOOL: " + .name end)
        else empty end)
  else empty end
' "$transcript" 2>/dev/null)"

cwd="$(jq -r 'select(.cwd) | .cwd' "$transcript" 2>/dev/null | head -1)"
echo "Session started in: ${cwd:-unknown}"

size="${#digest}"
if [ "$size" -le "$limit" ]; then
  printf '%s\n' "$digest"
else
  head_part=$((limit / 3))
  tail_part=$((limit - head_part))
  printf '%s\n' "${digest:0:$head_part}"
  echo
  echo "[... $((size - limit)) characters of the middle left out; Read $transcript if a step from there matters ...]"
  echo
  printf '%s\n' "${digest: -$tail_part}"
fi
