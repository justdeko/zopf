package com.dk.zopf.util

object Strings {
    const val APP_NAME = "zopf"

    object Words {
        const val WORKFLOW = "workflow"
        const val RUN = "run"
        const val CONNECTOR = "connector"
        const val NODE = "node"
        const val EDGE = "edge"
        const val REPO = "repo"
        const val FILE = "file"
        const val BROKEN = "broken"
        const val ERROR = "error"
        const val WARNING = "warning"

        fun count(
            n: Int,
            noun: String,
        ) = "$n ${plural(n, noun)}"

        fun plural(
            n: Int,
            noun: String,
        ) = if (n == 1) noun else "${noun}s"
    }

    object Nav {
        const val WORKFLOWS = "Workflows"
        const val RUNS = "Runs"
        const val CONNECTORS = "Connectors"
        const val SETTINGS = "Settings"

        const val NO_WORKSPACE = "No workspace open"
        const val NOTHING_HAS_RUN = "Nothing has run yet"
        const val SETTINGS_SCOPE = "Applies to every run on this machine, in any workspace"

        fun workflowsSubtitle(
            workflows: Int,
            broken: Int,
        ): String {
            val head = Words.count(workflows, Words.WORKFLOW)
            return if (broken == 0) head else "$head · ${Words.count(broken, Words.FILE)} that can't be read"
        }

        fun connectorsSubtitle(
            connectors: Int,
            broken: Int,
        ): String {
            val head = Words.count(connectors, Words.CONNECTOR)
            return if (broken == 0) head else "$head · ${Words.count(broken, Words.BROKEN)}"
        }

        fun runsSubtitle(
            total: Int,
            active: Int,
        ) = when {
            total == 0 -> NOTHING_HAS_RUN
            active == 0 -> "${Words.count(total, Words.RUN)}, none active"
            else -> "$active of ${Words.count(total, Words.RUN)} active"
        }
    }

    object Workspaces {
        const val NONE = "No workspace"
        const val UNAVAILABLE = "Workspace unavailable"
        const val UNAVAILABLE_SHORT = "Unavailable"
        const val REFRESH = "Refresh workspaces"
        const val FORGET = "Forget workspace"
        const val OPEN = "Open workspace…"
        const val CHOOSE_TITLE = "Open or create a zopf workspace"

        const val OPEN_FIRST = "Open a workspace first."
        const val OPEN_BEFORE_DRAFTING = "Open a workspace first, then describe the workflow"
        const val OPEN_BEFORE_CONNECTOR = "Open a workspace first, then add a connector"
        const val OPEN_BEFORE_NODE_RUN = "Open a workspace before running a node"

        fun couldntOpen(name: String) = "Couldn't open $name as a workspace"

        fun opened(name: String) = "Opened $name"

        fun fromTheFuture(name: String) = "$name was written by a newer zopf. Some of it may not mean what it says here."

        fun settingsFellBack(reason: String?) = "$reason. Using the defaults."

        fun couldntSaveSettings(reason: String?) = "Couldn't save settings: $reason"
    }

    object Workflows {
        const val NEW = "New workflow"
        const val NONE_YET = "No workflows yet"
        const val UNREADABLE = "Can't be read"
        const val OPEN_FROM_SWITCHER = "Open one from the switcher above."
        const val RUN = "Run"
        const val OPEN = "Open"
        const val RENAME = "Rename"
        const val RENAME_MENU = "Rename…"
        const val RENAME_TITLE = "Rename workflow"
        const val DELETE = "Delete"
        const val DELETE_MENU = "Delete…"
        const val SHOW_IN_FINDER = "Show in Finder"
        const val CANCEL = "Cancel"
        const val CREATE = "Create"
        const val DRAFT = "Draft it"

        const val NAME_LABEL = "Name"
        const val NAME_HINT = "Becomes the filename: lower-cased and hyphenated."
        const val DESCRIBE_TAB = "Describe it"
        const val DESCRIPTION_LABEL = "What should it do?"
        const val DESCRIPTION_HINT = "A sentence or two. An agent reads this workspace and drafts the graph for you."
        const val TEMPLATE_HEADING = "Or start from a template"
        const val EMPTY_TEMPLATE = "Empty"
        const val EMPTY_TEMPLATE_BLURB = "Draw your own graph from nothing."

        const val DELETE_BODY = "Deletes the YAML file from the workspace. zopf can't undo this."

        fun unavailableTitle(name: String) = "$name is unavailable"

        fun unavailableBody(path: Any) = "$path isn't reachable. Reconnect the drive or check out the branch that has it, then refresh."

        fun noneYetBody(dir: Any?) = "New workflow drafts one from a description, or starts you from a template. They live in $dir."

        fun deleteTitle(name: String) = "Delete $name?"

        fun summary(
            nodes: String,
            repos: String,
        ) = "$nodes · $repos"

        const val NAME_FIRST = "Give it a name first"
        const val DESCRIPTION_FIRST = "Say what it should do first"

        fun nameTaken(name: String) = "A workflow named \"$name\" already exists"

        fun alreadyDrafting(name: String) = "zopf is already drafting $name"

        fun draftStopped(name: String) = "The draft of $name stopped before it finished"

        fun draftNotAWorkflow(name: String) = "The draft of $name didn't come back as a workflow"

        fun drafted(name: String) = "Wrote $name, look it over before you run it"

        fun draftedWithIssue(
            name: String,
            issue: String,
        ) = "Wrote $name, but it won't run yet: $issue"

        fun drafting(name: String) = "Drafting $name"

        fun running(name: String) = "Running $name"

        fun notRunnable(
            name: String,
            issue: String?,
        ) = "$name can't run yet. $issue"

        fun andMoreToFix(rest: Int) = if (rest == 1) " (and 1 more thing to fix)" else " (and $rest more things to fix)"

        fun notFound(name: String) = "No workflow called $name in this workspace"
    }

    object Runs {
        const val EMPTY = "Open a workflow and press Run, or run one node from the editor."
        const val SELECT_ONE = "Select a run."
        const val SHOW = "Show"
        const val STOP = "Stop"
        const val STOP_RUN = "Stop run"
        const val CLEAR = "Clear"
        const val CLEAR_RUN = "Clear run"
        const val DELETE_FROM_DISK = "Delete from disk"
        const val RETRY_FROM_NODE = "Retry from this node"
        const val COULDNT_DELETE = "Couldn't delete that run"
        const val COULDNT_RETRY = "Couldn't retry that run"

        fun takeOverIn(terminal: String) = "Take over in $terminal"

        fun retryFrom(title: String) = "Retry from $title"

        fun rowSummary(
            summary: String,
            elapsed: String,
        ) = "$summary · $elapsed"
    }

    object Console {
        const val WAITING_FOR_FIRST_EVENT = "Waiting for the first event…"
        const val CLOSE_CONSOLE = "Close console"
        const val JUMP_TO_TOP = "Jump to top"
        const val JUMP_TO_BOTTOM = "Jump to bottom"
        const val STOP = "Stop"
        const val SHOW_ALL = "Show all"
        const val SHOW_LESS = "Show less"
        const val TURN_FINISHED = "Turn finished"
        const val TURN_FAILED = "Turn failed"
        const val FOLLOW_UP_PLACEHOLDER = "Follow up in this session…"
        const val SEND = "Send"
        const val FINISH = "Finish"
        const val DENY = "Deny"
        const val ALLOW = "Allow"
        const val ALLOW_FOR_THIS_RUN = "Allow for this run"
        const val REJECT = "Reject"
        const val APPROVE = "Approve"
        const val ANSWER_PLACEHOLDER = "Your answer…"
        const val CANCEL_RUN = "Cancel run"
        const val ANSWER = "Answer"

        fun takeOverIn(terminal: String) = "Take over in $terminal"

        fun wantsToRun(tool: String) = "$tool wants to run"

        fun exitCode(code: Int) = "exit $code"

        fun inDuration(duration: String) = " in $duration"
    }

    object Connectors {
        const val NEW = "New connector"
        const val NONE_YET = "No connectors yet"
        const val UNREADABLE = "Can't be read"
        const val REFRESH = "Refresh connectors"
        const val SHOW_IN_FINDER = "Show in Finder"
        const val DELETE = "Delete"
        const val DELETE_MENU = "Delete…"
        const val CANCEL = "Cancel"
        const val CREATE = "Create"
        const val INPUTS = "Inputs"
        const val OUTPUTS = "Outputs"
        const val SECRETS = "Secrets"
        const val NAME_LABEL = "Name"
        const val NAME_HINT = "Becomes the folder name: lower-cased and hyphenated."
        const val SHARE_ACROSS_WORKSPACES = "Share across workspaces"
        const val REQUIRED_MARKER = " *"
        const val OPTIONAL_MARKER = " (optional)"

        const val NO_DECLARED_INPUTS = "No declared inputs"

        fun emptyBody(agent: String) =
            "A connector is a folder with a manifest and a script. zopf pipes JSON into it and reads JSON back. " +
                "Name one and $agent opens in its folder to write it with you."

        fun emptyWhere(
            workspaceDir: Any,
            sharedDir: Any,
        ) = "They live in $workspaceDir or $sharedDir."

        fun shareHint(sharedDir: Any) = "Writes it to $sharedDir instead of this workspace."

        fun createHint(agent: String) =
            "zopf creates the folder and opens $agent in it, in your terminal. It knows the connector contract " +
                "and will ask what this one should do."

        fun changeIn(agent: String) = "Change in $agent"

        fun fixIn(agent: String) = "Fix in $agent"

        fun deleteTitle(name: String) = "Delete $name?"

        fun deleteBody(dir: Any) = "Deletes $dir and everything in it. Workflows that call it will fail."

        fun secretRow(
            name: String,
            source: String,
        ) = "$name · $source"

        fun keychainHint(service: String) = "security add-generic-password -a \"\$USER\" -s $service -w"

        fun detail(
            source: String,
            run: String,
            timeoutSeconds: Int,
        ) = "$source · $run · ${timeoutSeconds}s timeout"

        fun alreadyExists(name: String) = "A connector named \"$name\" already exists"

        fun missingAgent(
            executable: String,
            label: String,
            name: String,
        ) = "Can't find $executable. Install $label to write \"$name\" with it, or write connector.json and the script yourself."

        fun openedIn(
            name: String,
            terminal: String,
        ) = "Opened $name in $terminal. Refresh when you're done."

        fun couldntOpen(
            terminal: String,
            reason: String?,
        ) = "Couldn't open $terminal: $reason"
    }

    object Settings {
        const val RUNNING = "Running"
        const val AGENTS = "Agents"
        const val PERMISSIONS = "Permissions"
        const val NOTIFICATIONS = "Notifications"
        const val APPEARANCE = "Appearance"
        const val TAKE_OVER = "Take-over"
        const val HISTORY = "History"
        const val UPDATES = "Updates"
        const val OPEN_SOURCE = "Open source"
        const val OPEN_SOURCE_LICENSES = "Open source licenses"
        const val LICENSES_HINT = "Select an entry to show its full license."
        const val LICENSES_DONE = "Done"
        const val CURRENT = "Current"

        const val NO_FOLLOW_UPS = "follow-ups"
        const val NO_TAKE_OVER = "take-over"
        const val NO_INLINE_APPROVAL = "inline approval"
        const val NO_PERMISSION_MODE = "a permission mode"
        const val NO_SKILLS = "skills"
        const val NO_MODEL_CHOICE = "a model you pick"
        const val NO_COST_REPORT = "a dollar cost"

        const val DEFAULT_CLI_LABEL = "Default CLI"
        const val DEFAULT_CLI_HINT =
            "Used only by an agent node that names no CLI, in a workflow whose defaults name none either. " +
                "zopf drives whichever you pick with the login you already have."
        const val DEFAULTS_SCOPE =
            "These are only this machine's fallback. A workspace that names a provider or model under defaults: " +
                "in its zopf.yaml wins over them, so a repo can pin what its own workflows run on."

        const val ASK_BEFORE_HINT =
            "The node pauses and the run console offers Allow or Deny. When off, each node's own permission mode decides."

        const val CHECK_FOR_RELEASES = "Check for new releases"
        const val CHECK_FOR_RELEASES_HINT =
            "Once a week, zopf asks GitHub for the latest release and says so here. It sends its own version and nothing else."
        const val INSTALL_ON_ITS_OWN = "Install them on its own"
        const val INSTALL_ON_ITS_OWN_HINT =
            "zopf downloads the release, refuses anything not signed by the developer who signed this copy, " +
                "and swaps it in the next time you quit."
        const val UPDATE_ENV_HINT =
            "ZOPF_NO_UPDATE_CHECK=1 turns it off for `zopf` in a terminal too. `zopf upgrade` installs the new one there."
        const val CHECKING_SIGNATURE = "Checking who signed it"
        const val RESTART_NOW = "Restart now"
        const val OPEN_THE_RELEASE = "Open the release"
        const val INSTALL_IT = "Install it"

        const val RUNS_TO_KEEP = "Runs to keep"
        const val KEEP_ALL = "All"
        const val RUNS_TO_KEEP_HINT =
            "A run archives every message, tool call and line of output, so a chatty build step can be megabytes. " +
                "zopf keeps all of it by default."
        const val PRUNE_HINT =
            "Pick a number and older runs are deleted the next time zopf starts. The Runs screen and `zopf runs` " +
                "read the same archive, so both lose them."

        const val CONCURRENCY = "Nodes at once"
        const val CONCURRENCY_HINT =
            "Counted per run. Two workflows going at once can each have this many processes in flight. " +
                "Gates and branches never take a slot, since they run nothing."
        const val CONCURRENCY_APPLIES_NEXT = "Applies to the next run you start. Anything already going keeps the limit it began with."

        const val NOTIFY_ABOUT = "Notify me about"
        const val COLOUR_SCHEME = "Colour scheme"
        const val THEME_SYSTEM_HINT = "Follows macOS, and changes with it while the app is open."
        const val THEME_FIXED_HINT =
            "Fixed, whichever theme macOS uses. File pickers and the menu bar icon still follow the system."
        const val TERMINAL_APP_LABEL = "Terminal app"

        fun defaultModelNone(cli: String) = "Whatever $cli is set to"

        fun defaultModelHint(label: String) =
            "Used only when neither the node nor its workflow names one, and only for nodes running $label — " +
                "a model name belongs to the CLI it was written for."

        fun askBefore(tools: String) = "Ask before $tools"

        fun capabilityNote(
            label: String,
            missing: List<String>,
            hasFollowUps: Boolean,
            hasSandbox: Boolean,
        ) = buildString {
            append(label)
            append(if (hasFollowUps) " has no " else " takes one turn per node, with no ")
            val head = missing.dropLast(1)
            if (head.isNotEmpty()) append("${head.joinToString()} or ")
            append(missing.last())
            append(".")
            if (hasSandbox) append(" It takes a sandbox instead.")
        }

        fun version(version: String) = "zopf $version"

        fun writtenTo(file: Any) = "Written to $file"

        fun loggingTo(file: Any) = "Logging to $file"

        fun updateAvailable(
            newer: String,
            running: String,
        ) = "zopf $newer is out. You have $running."

        fun downloading(percent: Int) = "Downloading $percent%"

        fun readyToInstall(version: String) = "$version is ready."

        fun notifyHint(levelHint: String) = "$levelHint Nothing is posted while zopf is the app you're looking at."

        fun openedWith(app: String) = "Opened with: open -a $app"

        fun terminalFallback(app: String) = "Blank falls back to $app."
    }

    object Editor {
        const val BACK = "Back to workflows"
        const val SAVE = "Save"
        const val RUN_WORKFLOW = "Run workflow"
        const val WORKFLOW_SETTINGS = "Workflow settings"
        const val EDIT_YAML = "Edit the YAML"
        const val BACK_TO_CANVAS = "Back to the canvas"
        const val SHOW_INSPECTOR = "Show inspector"
        const val HIDE_INSPECTOR = "Hide inspector"
        const val RESIZE_INSPECTOR = "Resize inspector"
        const val ZOOM_IN = "Zoom in"
        const val ZOOM_OUT = "Zoom out"
        const val FIT_TO_WINDOW = "Fit to window"
        const val LAY_OUT_AGAIN = "Lay out again"
        const val MOVE_NODES = "Move nodes"
        const val STOP_MOVING_NODES = "Stop moving nodes"
        const val EMPTY_CANVAS = "Add a node from the palette on the left."
        const val CANCEL = "Cancel"
        const val DIRTY_MARKER = " •"

        const val RUN_THIS_NODE = "Run this node"
        const val CONNECT_FROM_HERE = "Connect from here"
        const val DUPLICATE = "Duplicate"
        const val DELETE = "Delete"

        const val FILE_CHANGED_ON_DISK = "This file changed on disk."
        const val LOAD_THEIRS = "Load theirs"
        const val KEEP_MINE = "Keep mine"

        const val UNSAVED_BODY = "Some changes haven't been written to the YAML file yet."
        const val SAVE_AND_CLOSE = "Save and close"
        const val KEEP_EDITING = "Keep editing"
        const val DISCARD = "Discard"

        const val FAILED_EDGE_LABEL = "failed"
        const val SOURCE_UNPARSEABLE = "This YAML doesn't parse"
        const val NOT_YAML = "This isn't YAML zopf can read."
        const val RENAME_ON_WORKFLOWS_SCREEN = "Rename on the Workflows screen to move the file."

        fun editNode(title: String) = "Edit $title"

        fun runNode(title: String) = "Run $title"

        fun unsavedTitle(name: String) = "Save $name?"

        fun addNode(
            label: String,
            blurb: String,
        ) = "Add a $label node. $blurb"

        fun connectingFrom(title: String) = "Connecting from $title. Click the next node."

        fun newNodeAfter(title: String) = "New node after $title"

        fun subtitle(
            nodes: Int,
            edges: Int,
            errors: Int,
        ) = buildString {
            append(Words.count(nodes, Words.NODE))
            append(" · ")
            append(Words.count(edges, Words.EDGE))
            if (errors > 0) append(" · $errors to fix")
        }

        fun nodeCountDescription(nodes: Int) = "$nodes nodes"

        const val SELF_EDGE = "A node can't feed itself"
        const val ALREADY_CONNECTED = "Those are already connected"
        const val WOULD_LOOP = "That would make a loop, and a workflow has to finish"

        fun noSuchNode(id: String) = "No node called \"$id\""

        fun skillDirWithoutManifest(name: Any) = "$name has no SKILL.md, so the CLI won't load it as a skill"

        fun renamedButPromptsStale(
            to: String,
            from: String,
            stale: String,
        ) = "Renamed to $to. $stale still reads \${$from…}. zopf doesn't edit prompt files, so fix that one by hand."
    }

    object EditorOverview {
        const val STOPS_THE_RUN = "Stops the run"
        const val WORTH_A_LOOK = "Worth a look"
        const val AT_A_GLANCE = "At a glance"
        const val REPOS = "Repos"
        const val SELECT_A_NODE = "Select a node to edit it."
        const val READY_TO_RUN = "Ready to run"
        const val NOT_READY_TO_RUN = "Not ready to run"
        const val NOTHING_TO_FIX = "Nothing to fix."
        const val OPEN = "Open"
        const val NO_NODES_YET = "No nodes yet. Add one from the toolbar and it will show up here."
    }

    object EditorNodeCard {
        const val GATE_SUBTITLE = "Waits for approval"
        const val CONNECT_FROM_HERE = "Connect from here"
        const val CANCEL_CONNECTION = "Cancel connection"
    }

    object Inspector {
        const val CHOOSE = "Choose"
        const val NOTHING_TO_CHOOSE_FROM = "Nothing to choose from"
        const val REMOVE = "Remove"
        const val ADD = "Add"
        const val BROWSE = "Browse"
        const val BROWSE_LABEL = "Browse…"
        const val DISCONNECT = "Disconnect"
        const val NEW_INPUT_NAME = "New input name"
        const val ADD_INPUT = "Add input"
        const val INSERT_FROM_UPSTREAM = "Insert from upstream"

        const val ID_LABEL = "Id"
        const val TITLE_LABEL = "Title"
        const val TITLE_HINT = "Shown on the card. Defaults to the id."
        const val RENAME_HINT = "Press ✓ to rename. References are updated too."
        const val RENAME = "Rename"
        const val CONNECT_FROM_HERE = "Connect from here"

        const val PROVIDER_HINT = "Which agent CLI runs this node. Absent everywhere means Claude Code."
        const val MODEL_LABEL = "Model"
        const val ALSO_READ_LABEL = "Also read"
        const val ALSO_READ_EMPTY = "Add more repos to give this node extra directories to read."
        const val ALLOWED_TOOLS_LABEL = "Allowed tools"
        const val ALLOWED_TOOLS_ADD = "Tool rule"
        const val ALLOWED_TOOLS_HINT = "Passed straight to --allowedTools. Narrow a tool with a pattern, like Bash(<command> *)."
        const val PERMISSION_MODE_LABEL = "Permission mode"
        const val PERMISSION_MODE_HINT = "What this node may do without asking."
        const val WORKFLOW_DEFAULT = "Workflow default"
        const val SANDBOX_LABEL = "Sandbox"

        const val DECLARE_OUTPUT_FIELDS = "Declare output fields…"
        const val OUTPUT_SCHEMA = "Output schema"
        const val OUTPUTS = "Outputs"
        const val FIELD_LABEL = "Field"
        const val TYPE_LABEL = "Type"
        const val REMOVE_FIELD = "Remove field"
        const val FIELD_DESCRIPTION_LABEL = "Description"
        const val FIELD_DESCRIPTION_HINT = "Sent to the model as this field's description."
        const val FIELD_OPTIONAL = "Optional"
        const val SCHEMA_INTRO = "The node fills this shape instead of writing freely."
        const val SCHEMA_NESTED_NOTE =
            " An object or array field is up to the model: it picks the keys and the size, and the value " +
                "arrives whole, since \${node.field} only goes one level deep. Pass it to a shell node running jq, " +
                "or split it into plain fields you can compare."
        const val RESULT_IS_WHOLE_OBJECT = ": the whole object, as JSON text"
        const val NESTED_TOO_DEEP = ": JSON text, too deep to compare"

        const val USE_PROMPT_FILE = "Read the prompt from a .md file…"
        const val PROMPT_FILE_LABEL = "Prompt file"
        const val TYPE_IT_HERE_INSTEAD = "Type it here instead"
        const val PROMPT_LABEL = "Prompt"
        const val CHOOSE_PROMPT = "Choose a prompt"
        const val PROMPT_FILE_HINT =
            "Read when the node runs. \${node.field} inside it is interpolated the same way. The editor can't " +
                "check those references, or update them on rename."

        const val SKILLS_LABEL = "Skills"
        const val SKILLS_EMPTY = "None found in this workflow's repos, the workspace, or ~/.claude/skills. Add a directory below."
        const val ADD_SKILL_DIRECTORY = "Add a skill directory…"
        const val CHOOSE_SKILL_DIRECTORY = "Choose a skill directory"
        const val SKILLS_HINT =
            "A ticked skill is passed to the session and named in the prompt, so it is loaded and asked for. " +
                "Skills in this node's own .claude/skills or in ~/.claude/skills load anyway, so they are only named."

        const val COMMAND_LABEL = "Command"
        const val COMMAND_HINT = "Run with zsh -lc, so your login PATH applies."

        const val CONNECTOR_LABEL = "Connector"
        const val CONNECTOR_NONE = "Choose a connector"
        const val CONNECTOR_HINT = "A folder under connectors/. Workspace first, then ~/.zopf/connectors."
        const val NO_CONNECTORS_YET = "No connectors yet. Add one on the Connectors screen."
        const val INPUTS = "Inputs"
        const val UNDECLARED_INPUTS = "Undeclared inputs"
        const val INPUT_REQUIRED = "Required"
        const val INPUT_OPTIONAL = "Optional"

        const val EXPRESSION_LABEL = "Expression"
        const val EXPRESSION_HINT = "Interpolated, then compared with == or !=, or read as truthy. No other syntax."
        const val BRANCH_HELP = "Set each outgoing edge to the true or the false side under Connections below."

        const val QUESTION_LABEL = "Question"
        const val QUESTION_HINT = "Interpolated, so it can quote an earlier node's output."
        const val CHOICES_LABEL = "Choices"
        const val CHOICES_ADD = "Choice"
        const val CHOICES_HINT =
            "Leave empty for a free-text answer. A question with choices can also be answered from the menu bar, " +
                "which has no text field."
        const val DEFAULT_LABEL = "Default"
        const val DEFAULT_FREE_TEXT_HINT = "Prefills the answer field."
        const val DEFAULT_CHOICE_HINT = "Which choice starts selected."
        const val INPUT_HELP =
            "The run waits here until you answer, in the app or from the menu bar. Cancelling skips the rest of " +
                "the run, like rejecting a gate."

        const val GATE_HELP =
            "The run waits here until you approve it, in the app or from the menu bar. The title is the question " +
                "you'll be asked."
        const val GATE_SHOW_LABEL = "What to show"
        const val GATE_SHOW_HINT =
            "Interpolated, so it can quote what you're approving. A whole result is fine: the run shows the first " +
                "lines and expands on a click."

        const val RUNS_IN_LABEL = "Runs in"
        const val RUNS_IN_HINT = "Becomes the process working directory."
        const val ADD_REPO_TO_WORKFLOW = "Add a repo to this workflow"

        const val CONNECTIONS = "Connections"
        const val NO_CONNECTIONS = "Nothing connected yet. Use the link handle on the card to draw an edge."
        const val ON_FAILURE = "on failure"

        fun modelHint(cli: String) = "Passed to $cli as --model. Anything the CLI takes works, not just the listed ones."

        fun deleteNode(title: String) = "Delete $title"

        fun idHint(id: String) = "Used by \${$id.result}"

        fun providerNone(label: String) = "Default ($label)"

        fun inherited(value: String) = "Inherited ($value)"

        fun cliDefault(cli: String) = "Whatever $cli is set to"

        fun cliConfiguredFor(cli: String) = "Whatever $cli is configured for"

        fun workflowDefault(value: String) = "Workflow default ($value)"

        fun sandboxHint(label: String) = "Chosen before launch, since $label has no way to ask mid-turn. Nothing here can be approved inline."

        fun ignoredFields(
            label: String,
            fields: String,
            count: Int,
        ) = "$label has no $fields, so ${if (count == 1) "it is" else "they are"} left out of the command. " +
            "Change the CLI, or clear ${if (count == 1) "it" else "them"} in the YAML."

        fun noSchemaHint(id: String) = "With no schema, the node answers in prose and \${$id.result} is that prose."

        fun removeInput(key: String) = "Remove $key"

        fun connectorHelp(id: String) =
            "Input values interpolate \${node.field} like prompts do, and arrive as one JSON object on the " +
                "script's stdin. The reply is \${$id.result}, plus one field per key of the object."

        fun fieldDescription(description: String) = ": $description"

        fun nestedFieldDescription(description: String) = ": $description, as JSON text"

        fun notMarkdown(name: Any) = "$name isn't markdown. Its text is sent as the prompt anyway."

        fun skillIn(
            name: String,
            source: String,
        ) = "$name in $source"

        fun inputDefault(value: String) = " · defaults to $value"

        fun edgeFrom(title: String) = "← $title"

        fun edgeTo(title: String) = "→ $title"
    }

    object WorkflowSettings {
        const val DONE = "Done"
        const val CANCEL = "Cancel"
        const val DEFAULTS = "Defaults"
        const val REPOS = "Repos"
        const val ADD_REPO = "Add repo"
        const val BROWSE = "Browse…"
        const val ADD = "Add"

        const val DESCRIPTION_LABEL = "Description"
        const val DESCRIPTION_HINT = "Shown in the workflow list."
        const val SKILL_DIRS_LABEL = "Skill directories"
        const val SKILL_DIR_ADD = "Path to a skill"
        const val SKILL_DIRS_HINT =
            "A directory with a SKILL.md in it. Absolute, ~-relative, or relative to the workspace root. " +
                "Nodes tick it by name."
        const val CHOOSE_SKILL_DIR = "Choose a skill directory"
        const val CHOOSE_REPO = "Choose a repo"

        const val REPO_LABEL = "Repo"
        const val REPO_NONE = "None"
        const val REPO_HINT = "Used by nodes that don't name one."
        const val PROVIDER_HINT = "Which agent CLI this workflow's agent nodes run, unless they name their own."
        const val PERMISSION_MODE_LABEL = "Permission mode"
        const val PERMISSION_MODE_NONE = "Whatever the CLI is set to"
        const val PERMISSION_MODE_HINT = "Claude Code only."
        const val SANDBOX_LABEL = "Sandbox"
        const val SANDBOX_NONE = "Whatever the CLI is set to"
        const val SANDBOX_HINT = "codex only."

        const val NO_REPOS_DECLARED = "None declared. Nodes fall back to the workspace directory."
        const val USED_BY_OTHER_WORKFLOWS = "Used by other workflows"
        const val REPO_ID_LABEL = "Id"
        const val REPO_ID_HINT = "How nodes refer to it, e.g. app."
        const val REPO_PATH_LABEL = "Path"

        fun title(name: String) = "$name settings"

        fun providerNone(label: String) = "Default ($label)"

        fun modelNone(cli: String) = "Whatever $cli is set to"

        fun modelHint(label: String) = "Read by nodes running $label. A node on another CLI names its own."

        fun selfRepoAvailable(path: Any?) = "\"self\" is available without declaring it: $path"

        fun removeRepo(id: String) = "Remove $id"
    }

    object MenuBar {
        const val FILE = "File"
        const val WORKFLOW = "Workflow"
        const val VIEW = "View"
        const val WORKSPACE = "Workspace"
        const val WINDOW = "Window"
        const val MINIMIZE = "Minimize"
        const val ZOOM = "Zoom"
        const val CLOSE_WINDOW = "Close Window"
        const val NEW_WORKFLOW = "New Workflow…"
        const val NEW_CONNECTOR = "New Connector…"
        const val SAVE = "Save"
        const val SHOW_IN_FINDER = "Show in Finder"
        const val RUN_WORKFLOW = "Run Workflow"
        const val RUN_SELECTED_NODE = "Run Selected Node"
        const val STOP_RUN = "Stop Run"
        const val STOP_EVERYTHING = "Stop Everything"
        const val ADD_NODE = "Add Node"
        const val DUPLICATE_NODE = "Duplicate Node"
        const val CONNECT_FROM_NODE = "Connect From Node"
        const val DELETE_NODE = "Delete Node"
        const val WORKFLOW_SETTINGS = "Workflow Settings…"
        const val INSPECTOR = "Inspector"
        const val MOVE_NODES = "Move Nodes"
        const val ZOOM_IN = "Zoom In"
        const val ZOOM_OUT = "Zoom Out"
        const val FIT_TO_WINDOW = "Fit to Window"
        const val LAY_OUT_AGAIN = "Lay Out Again"
        const val OPEN_WORKSPACE = "Open Workspace…"
        const val REFRESH_WORKSPACES = "Refresh Workspaces"
        const val REFRESH_CONNECTORS = "Refresh Connectors"

        fun workspaceUnavailable(name: String) = "$name (unavailable)"
    }

    object Tray {
        const val SHOW_ZOPF = "Show zopf"
        const val QUIT_ZOPF = "Quit zopf"
        const val NOTHING_RUNNING = "Nothing running"
        const val STOP_EVERYTHING = "Stop everything"
        const val RUN = "Run"
        const val SHOW = "Show"
        const val STOP = "Stop"
        const val ALLOW = "Allow"
        const val ALLOW_FOR_THIS_RUN = "Allow for this run"
        const val DENY = "Deny"
        const val APPROVE = "Approve"
        const val REJECT = "Reject"
        const val TYPE_AN_ANSWER = "Type an answer in zopf…"
        const val CANCEL_RUN = "Cancel run"
        const val NEEDS_YOU = "needs you"
        const val NEEDS_AN_ANSWER = "needs an answer"
        const val IDLE = "zopf · nothing running"

        fun takeOverIn(terminal: String) = "Take over in $terminal"

        fun runLine(
            workflow: String,
            detail: String,
        ) = "$workflow · $detail"

        fun oneActive(
            workflow: String,
            summary: String,
        ) = "zopf · $workflow: $summary"

        fun manyActive(
            active: Int,
            waiting: Int,
        ) = "zopf · $active runs, $waiting waiting for you"
    }

    object Notifications {
        const val GATE_FALLBACK_BODY = "This gate is waiting for you before the run goes on."
        const val APPROVE = "Approve"
        const val REJECT = "Reject"
        const val ALLOW = "Allow"
        const val ALLOW_FOR_THIS_RUN = "Allow for this run"
        const val DENY = "Deny"
        const val ORPHANS_TITLE = "Sessions still running"
        const val FALLBACK_TITLE = APP_NAME
        const val FALLBACK_BODY = "(no output)"

        fun needsAnAnswer(node: String) = "$node · needs an answer"

        fun needsApproval(node: String) = "$node · needs approval"

        fun needsYou(node: String) = "$node · needs you"

        fun permissionBody(
            tool: String,
            summary: String,
        ) = "$tool: $summary".trim().trimEnd(':')

        fun runSettled(
            workflow: String,
            status: String,
        ) = "$workflow · ${status.lowercase()}"

        fun orphansBody(count: Int) = "$count session(s) from a previous launch are still alive. Take them over in Terminal or stop them."
    }

    object NodeKinds {
        const val AGENT = "Agent"
        const val SHELL = "Shell"
        const val CONNECTOR = "Connector"
        const val GATE = "Gate"
        const val BRANCH = "Branch"
        const val INPUT = "Input"

        const val AGENT_BLURB = "Run a headless agent session"
        const val SHELL_BLURB = "Run a command in your login shell"
        const val CONNECTOR_BLURB = "Call a connector script"
        const val GATE_BLURB = "Wait for you to approve"
        const val BRANCH_BLURB = "Take one path or the other"
        const val INPUT_BLURB = "Ask you for a value"

        const val COPY_SUFFIX = " copy"

        fun notANodeType(
            raw: String,
            kinds: String,
        ) = "\"$raw\" isn't a kind of node. It can be $kinds"
    }

    object Providers {
        const val CLAUDE_CODE = "Claude Code"
        const val CODEX = "codex"
        const val DEEP_SEEK_HARNESS = "DeepSeek Harness"
    }

    object RunStates {
        const val QUEUED = "Queued"
        const val STARTING = "Starting"
        const val RUNNING = "Running"
        const val WAITING = "Waiting for you"
        const val SUCCEEDED = "Done"
        const val FAILED = "Failed"
        const val STOPPED = "Stopped"
        const val SKIPPED = "Skipped"
        const val DETACHED = "In Terminal"
    }

    object Prefs {
        const val NOTIFY_EVERYTHING = "Everything"
        const val NOTIFY_IMPORTANT = "Important"
        const val NOTIFY_NOTHING = "Nothing"

        const val NOTIFY_EVERYTHING_HINT = "Every run that finishes, and every gate, question and permission prompt."
        const val NOTIFY_IMPORTANT_HINT = "Runs that fail, and every gate, question and permission prompt."
        const val NOTIFY_NOTHING_HINT = "No notifications. The menu bar icon still counts what's running and waiting."

        const val SECRET_FROM_ENVIRONMENT = "environment"
        const val SECRET_FROM_KEYCHAIN = "keychain"
        const val SECRET_MISSING = "not set"

        fun secretSource(keychain: String?) = keychain?.let { "environment, or keychain \"$it\"" } ?: SECRET_FROM_ENVIRONMENT
    }

    object Validation {
        const val ERROR = "error"
        const val WARNING = "warning"

        fun fromTheFuture(
            needs: Int?,
            reads: Int,
        ) = "This workflow needs workflow format v$needs. This zopf reads v$reads, so update zopf"

        fun repoMoved(
            id: String,
            path: String,
        ) = "Repo \"$id\" isn't at $path any more"

        fun defaultRepoUndeclared(repo: String) = "Default repo \"$repo\" isn't declared"

        fun repoUndeclared(
            where: String,
            repo: String,
        ) = "$where runs in \"$repo\", which isn't declared"

        fun alsoReadUndeclared(
            where: String,
            repo: String,
        ) = "$where also reads \"$repo\", which isn't declared"

        fun inFile(source: String?) = source?.let { " in $it" }.orEmpty()

        fun referencesUnknownNode(
            where: String,
            referenced: String,
            located: String,
        ) = "$where reads \${$referenced…}$located, which is no longer a node"

        fun referencesUnconnectedNode(
            where: String,
            referenced: String,
            located: String,
        ) = "$where reads \${$referenced…}$located, but nothing connects $referenced to it"

        fun referencesUnknownField(
            referenced: String,
            produced: String,
            where: String,
            field: String,
            located: String,
        ) = "$referenced produces $produced, so $where can't read \${$referenced.$field}$located"

        fun orphanNode(title: String) = "$title isn't connected to anything"

        fun duplicateId(
            count: Int,
            id: String,
        ) = "There are $count nodes called \"$id\". Ids have to be unique, since \${$id.result} can only mean one of them"

        fun selfEdge(from: String) = "The edge on \"$from\" feeds itself, so it could never run"

        fun edgeToMissingNode(
            missing: String,
            name: String,
            other: String,
        ) = "An edge connects \"$missing\", which isn't a node in $name. The edge to \"$other\" is ignored, " +
            "so the run takes a path nobody wrote"

        fun cycle(stuck: String) = "$stuck can never run: the edges into them make a loop"

        const val NEEDS_PROMPT = "a prompt"
        const val NEEDS_COMMAND = "a command"
        const val NEEDS_CONNECTOR = "a connector"
        const val NEEDS_EXPRESSION = "an expression"
        const val NEEDS_QUESTION = "a question"

        fun needs(
            title: String,
            what: String,
        ) = "$title needs $what"

        fun defaultNotAChoice(
            title: String,
            choices: String,
            default: String,
        ) = "$title offers $choices, so its default \"$default\" can't be picked"

        fun promptFileMissing(
            title: String,
            file: String,
        ) = "$title reads its prompt from $file, which isn't there"

        fun inlinePromptIgnored(
            title: String,
            file: String,
        ) = "$title sends $file, so its inline prompt is ignored"

        fun schemaOnNonAgent(title: String) = "$title declares an output schema, which only an agent node can use"

        fun unnamedOutputField(title: String) = "$title declares an output field with no name"

        fun duplicateOutputField(
            title: String,
            field: String,
        ) = "$title declares the output field \"$field\" more than once"

        fun outputFieldShadowsBuiltIn(
            title: String,
            field: String,
            id: String,
        ) = "$title declares an output field named \"$field\", which \${$id.$field} already means"

        fun outputFieldUnreferenceable(
            title: String,
            field: String,
            id: String,
        ) = "$title declares the output field \"$field\", which \${$id.…} can't name"

        fun unknownSkill(
            title: String,
            skill: String,
        ) = "$title uses the \"$skill\" skill, which isn't in this workflow's repos, the workspace or ~/.claude/skills"

        fun fieldDoesNothing(
            title: String,
            type: String,
            field: String,
        ) = "$title is a $type node, so its \"$field\" does nothing"

        fun executableMissing(
            title: String,
            cli: String,
        ) = "$title runs $cli, which isn't on your PATH. The node will fail when it starts"

        fun providerIgnoresFields(
            title: String,
            label: String,
            fields: String,
        ) = "$title runs $label, which ignores $fields"

        fun connectorMissing(
            title: String,
            connector: String,
        ) = "$title calls \"$connector\", which isn't a connector in this workspace or ~/.zopf/connectors"

        fun connectorInputMissing(
            title: String,
            input: String,
        ) = "$title needs an input for \"$input\""

        fun connectorInputUndeclared(
            title: String,
            input: String,
            connector: String,
        ) = "$title sets \"$input\", which $connector doesn't declare"

        fun failureEdgeOnInfallibleNode(
            title: String,
            type: String,
            to: String,
        ) = "$title is a $type, which never fails, so the edge to $to can never be taken"

        fun conditionOnNonBranch(
            title: String,
            to: String,
        ) = "$title isn't a branch, so the \"when\" on its edge to $to can never match and $to never runs"

        fun branchEdgeWithoutCondition(title: String) = "$title has an edge that is neither true nor false"

        fun duplicateBranchEdges(
            title: String,
            count: Int,
            condition: String,
        ) = "$title has $count \"$condition\" edges; it can only take one"

        const val ID_CHARACTERS = "Ids can use letters, digits, - and _ only"
        const val REPO_ID_CHARACTERS = "Repo ids can use letters, digits, - and _ only"

        fun noSuchNode(id: String) = "No node called \"$id\""

        fun idTaken(id: String) = "\"$id\" is already taken"

        fun repoTaken(id: String) = "\"$id\" is already declared"

        fun unknownKey(
            where: String,
            key: String,
        ) = "$where sets \"$key\", which zopf doesn't know, so it is ignored"

        const val THE_WORKFLOW = "the workflow"

        fun nodeAt(index: Int) = "node ${index + 1}"

        fun edgeAt(index: Int) = "edge ${index + 1}"
    }

    object Transcript {
        const val CARRIED_OVER = "Carried over from the previous attempt"
        const val STARTED_BEFORE_QUIT = "Started before zopf last quit, and is still running outside it."
        const val ENDING_SESSION = "Ending the session"
        const val DEFAULT_MODEL = "default model"
        const val SKIPPED_FAILED_DEPENDENCY = "Skipped: something it depends on failed"
        const val SKIPPED_OTHER_PATH = "Skipped: the run took another path"
        const val UNREACHABLE = "Never became reachable. Its dependencies are in a loop."
        const val WAITING_FOR_YOU = "Waiting for you"
        const val APPROVED = "Approved"
        const val REJECTED = "Rejected. The rest of the run is skipped."
        const val CANCELLED = "Cancelled. The rest of the run is skipped."
        const val APPROVED_OUTPUT = "approved"
        const val REJECTED_OUTPUT = "rejected"

        fun sessionStarted(
            model: String,
            cwd: Any?,
        ) = "Session started · $model · $cwd"

        fun denied(
            tool: String,
            summary: String,
        ) = "Denied: $tool $summary".trimEnd()

        fun rateLimit(detail: String?) = "Rate limit: $detail"

        fun wantsToRun(
            tool: String,
            summary: String,
        ) = "$tool wants to run: $summary".trimEnd(':', ' ')

        fun allowed(tool: String) = "Allowed $tool"

        fun deniedTool(tool: String) = "Denied $tool"

        fun wontAskAgain(tool: String) = "Won't ask about $tool again in this run"

        fun youSaid(text: String) = "You: $text"

        fun promptFrom(file: String) = "Prompt from $file"

        fun willAskBefore(tools: String) = "Will ask before $tools"

        fun sandbox(mode: String) = "Sandbox: $mode"

        fun streamEnded(reason: String?) = "Stream ended: $reason"

        fun outputEnded(reason: String?) = "Output ended: $reason"

        fun shellCommand(command: String) = "$ $command"

        fun connectorSent(
            name: String,
            json: String,
        ) = "$name ← $json"

        fun secretsFound(names: String) = "Secrets: $names"

        fun secretsOptionalMissing(names: String) = "Optional and not set: $names"

        fun secretsMissing(
            name: String,
            missing: String,
        ) = "$name needs $missing. Set them on this node."

        fun secretsUnresolved(
            missing: String,
            name: String,
            detail: String,
        ) = "Couldn't find $missing. $name reads $detail"

        fun connectorPlainOutput(name: String) = "$name printed no JSON object, so its plain output is the result"

        fun connectorMissingOutputs(
            name: String,
            fields: String,
            count: Int,
        ) = "$name declares $fields but didn't return ${if (count == 1) "it" else "them"}"

        fun gaveUp(seconds: Int) = "Gave up after ${seconds}s. Nothing finished it."

        fun ignoredFields(
            label: String,
            fields: String,
        ) = "$label has no $fields, so it is left out"

        fun unresolvedReferences(refs: String) = "Nothing has produced $refs yet, so the reference is left as written"

        fun takenOver(
            terminal: String,
            resume: String,
        ) = "Taken over in $terminal. Continue there with $resume"

        fun couldntOpen(
            terminal: String,
            reason: String?,
        ) = "Couldn't open $terminal: $reason"

        fun couldntSend(reason: String?) = "Couldn't send that: $reason"

        fun branchCompared(
            left: String,
            operator: String,
            right: String,
            taken: Boolean,
        ) = "\"$left\" $operator \"$right\" → $taken"

        fun branchTruthy(
            resolved: String,
            taken: Boolean,
        ) = "\"$resolved\" is ${if (taken) "set" else "empty or false"} → $taken"

        fun skillsPassed(list: String) = "Skills: $list, one --plugin-dir each"

        fun skillsAmbient(list: String) = "Already loaded by this session, so passed no flag: $list"

        fun skillsUnresolved(names: String) = "No skill directory found for $names. Declare it in the workflow's settings, or the name goes nowhere."

        fun skillForcedCommand(name: String) = "$name sets disable-model-invocation, so it is invoked as /$name and the prompt goes to it as arguments"

        fun skillCommandsDropped(rest: String) = "Only one command is expanded per message, so $rest won't fire. Give each one its own node."
    }

    object RunErrors {
        const val NOT_A_GRAPH = "A single node run isn't a graph. Run it again from the editor."
        const val NO_SESSION_TO_TAKE_OVER = "Only an agent node has a session to take over"
        const val NO_SESSION_ID_YET = "This run hasn't reported a session id yet"
        const val NO_DIRECTORY_TO_RESUME = "This node has no directory to resume in"
        const val NOT_ON_DISK_YET = "This run isn't on disk yet"
        const val NOTHING_ARCHIVED = "No runs are archived yet, so there is nothing to resume"
        const val NO_UPDATE_WAITING = "There is no update waiting to be installed."
        const val SHELL_TAKES_NO_FOLLOW_UPS = "A shell node doesn't take follow-ups"
        const val CONNECTOR_TAKES_INPUTS_UP_FRONT = "A connector takes its inputs up front"
        const val THE_WORKSPACE_IT_RAN_IN = "the workspace it ran in"
        const val THIS_WORKSPACE = "this workspace"

        fun stillRunning(name: String) = "$name is still running"

        fun stillRunningElsewhere(name: String) = "$name is still running outside zopf"

        fun stillRunningSomewhereElse(name: String) = "$name is still running somewhere else, so stop it before resuming"

        fun workflowGone(
            name: String,
            root: String,
        ) = "$name isn't in $root any more, so there is nothing to retry from"

        fun nodeGone(
            id: String,
            name: String,
        ) = "\"$id\" isn't in $name any more"

        fun cannotResumeHeadless(label: String) = "$label can't resume a headless session, so there is nothing to hand over"

        fun noArchivedRun(id: String) = "No archived run has an id starting with \"$id\". Run \"zopf runs\" to see them"

        fun ambiguousRunId(
            id: String,
            count: Int,
            matches: String,
        ) = "\"$id\" matches $count runs. Use more of the id: $matches"

        fun archiveEmpty(dir: Any) = "$dir has no run.json in it any more"

        fun formatTooNew(
            name: String,
            needs: Int?,
            reads: Int,
        ) = "$name needs workflow format v$needs. This zopf reads v$reads, so update zopf to run it"

        fun noNodes(name: String) = "$name has no nodes yet"

        fun singleNodeNeedsGraph(label: String) = "$label nodes only mean something with the rest of the graph around them, so run the workflow instead"

        fun nodeNamesNoConnector(title: String) = "$title doesn't name a connector"

        fun connectorNotFound(
            connector: String,
            workspace: String,
        ) = "No connector called \"$connector\" in $workspace or ~/.zopf/connectors"

        fun repoUndeclared(
            repo: String,
            name: String,
        ) = "Repo \"$repo\" isn't declared in $name"

        fun pathGone(path: Any) = "$path isn't there any more"

        fun executableMissing(executable: String) = "Couldn't find \"$executable\" on your PATH"

        fun takesOneTurn(label: String) = "$label takes one turn and doesn't read stdin"

        fun missingExecutables(
            executables: String,
            labels: String,
            count: Int,
        ) = "Can't find $executables. Install $labels and sign in, or the nodes that use " +
            "${if (count == 1) "it" else "them"} will fail."

        fun couldntOpenTerminal(
            terminal: String,
            reason: String?,
        ) = "Couldn't open $terminal: $reason"

        fun notAWorkspace(dir: Any) = "$dir isn't a workspace, and has no .zopf directory in it"

        fun noWorkspaceHere(
            from: Any,
            fallback: Any,
        ) = "No workspace here. zopf looks for a .zopf directory from $from upwards, then in $fallback. " +
            "Pass --workspace <dir> to name one."

        fun notAFile(path: Any) = "$path is a directory, not a file zopf can write"

        fun settingsUnreadable(
            file: Any,
            reason: String?,
        ) = "$file couldn't be read: $reason"

        const val NAME_CANNOT_BE_EMPTY = "Name cannot be empty"

        fun workflowExists(name: String) = "A workflow named \"$name\" already exists"

        fun workflowExistsIn(
            name: String,
            target: Any,
        ) = "\"$name\" already exists in $target"

        const val PERMISSION_DENIED_UNREACHABLE = "zopf could not be reached, so the answer is no"
        const val PERMISSION_UNKNOWN_SESSION = "zopf doesn't recognise this session"
        const val UNNAMED_TOOL = "a tool"

        fun permissionTimedOut(tool: String) = "Nobody answered in time, so $tool was denied"

        const val NOTHING_WAS_A_WORKFLOW = "Nothing in that answer was a workflow"
    }

    object Updates {
        const val NOT_AN_APP = "zopf installs updates only when it runs as an app. The release page has the new one."
        const val TRANSLOCATED = "macOS is running zopf out of the disk image. Drag it to Applications and it can update itself."
        const val QUOTED_PATH = "zopf can't update itself from a path with a quote in it."
        const val UNSIGNED = "This build isn't signed, so zopf can't tell a real update from a forged one."
        const val NOT_OUR_SIGNER = "The download isn't signed by the team that signed this copy of zopf. Nothing was installed."
        const val NOT_NOTARIZED = "macOS won't vouch for the download, so zopf left it alone."
        const val NOT_RUNNING_FROM_BUNDLE = "zopf isn't running from an app bundle."
        const val INSTALL_FAILED = "the update wouldn't install"
        const val NOTHING_IN_PARTICULAR = "nothing in particular"

        fun cannotWriteTo(dir: Any) = "zopf can't write to $dir, so it can't replace itself there."

        fun noAppAsset(version: String) = "Release $version publishes no app to install. The release page has it."

        fun noBundleInImage(bundle: String) = "The disk image holds no $bundle."

        fun stagedMissing(
            version: String,
            cache: Any,
        ) = "The staged $version has gone missing from $cache."

        fun wrongVersionInside(
            inside: String,
            expected: String,
        ) = "The download says it is $inside, not $expected."

        fun wouldntCopy(reason: String?) = "The new zopf wouldn't copy out of the disk image: $reason"

        fun wouldntMount(reason: String?) = "The disk image wouldn't mount: $reason"

        fun readyToRestart(version: String) = "zopf $version is ready. Restart zopf to use it."

        fun isOut(version: String) = "zopf $version is out. Settings has it."

        fun githubAnswered(code: Int) = "GitHub answered $code"

        fun notAVersion(tag: String) = "\"$tag\" doesn't name a version"

        fun downloadStopped(
            read: Long,
            total: Long,
        ) = "the download stopped at $read of $total bytes"

        fun notHttps(url: String) = "$url isn't an https link"

        fun answeredWith(
            url: String,
            code: Int,
        ) = "$url answered $code"

        fun nowhereToLand(target: Any) = "$target has nowhere to land"

        fun tookTooLong(tool: String) = "$tool took too long"

        fun toolFailed(tool: String) = "$tool failed"
    }

    object Cli {
        const val NO_WORKFLOWS_YET = "  no workflows yet"
        const val NO_RUNS_YET = "no runs archived yet"
        const val WOULD_DELETE = "would delete"
        const val DELETED = "deleted"
        const val WOULD_GO = " would go"
        const val GONE = " gone"
        const val NOTHING_TO_CARRY_OVER = "nothing to carry over"
        const val NOTHING_LEFT_TO_RUN = "nothing left to run"
        const val THE_LATEST_RELEASE = "the latest release"
        const val UNKNOWN_VERSION = "unknown"
        const val NOT_INSTALLED_BY_INSTALLER = "zopf: this copy wasn't put there by the installer, so it can't replace itself. Reinstall with"
        const val TAR_TOOK_TOO_LONG = "tar took too long"
        const val TAR_FAILED = "tar failed"
        const val WHICH_WORKFLOW = "Which workflow? Run \"zopf list\" to see what this workspace has"
        const val UNKNOWN_COMMAND = "Try run, list, validate, runs, prune, check-update or upgrade"

        fun header(
            name: String,
            root: Any,
        ) = "$name · $root"

        fun brokenFile(
            file: Any,
            message: String?,
        ) = "$file: doesn't parse. $message"

        fun issueLine(
            name: String,
            rendered: String,
        ) = "$name: $rendered"

        fun warningLine(
            name: String,
            rendered: String,
        ) = "$name: warning: $rendered"

        fun okLine(name: String) = "$name: ok"

        fun nothingToPrune(keep: Int) = "nothing to prune, keeping $keep"

        fun pruned(
            count: Int,
            verb: String,
            keep: Int,
        ) = "$count run(s)$verb, $keep kept"

        fun version(version: String) = "zopf $version"

        fun newerRelease(version: String) = "zopf $version is out. Run zopf upgrade to install it."

        fun newerThanRunning(
            version: String,
            running: String,
        ) = "zopf $version is out. You have $running."

        fun readRelease(url: String) = "Run zopf upgrade to install it, or read $url"

        fun latestAlready(version: String) = "zopf $version is the latest release"

        fun couldntAskGitHub(reason: String?) = "zopf: couldn't ask GitHub for the latest release: $reason"

        fun couldntAskGitHubFor(
            what: String,
            reason: String?,
        ) = "zopf: couldn't ask GitHub for $what: $reason"

        fun failed(message: String) = "zopf: $message"

        fun moreIn(file: Any) = "More in $file"

        fun noSuchCommand(command: String) = "No \"$command\" command. $UNKNOWN_COMMAND"

        fun noSuchWorkflow(
            name: String,
            workspace: String,
            root: Any,
        ) = "No workflow called \"$name\" in $workspace ($root)"

        fun oneAtATime(extra: String) = "One workflow at a time. \"$extra\" is extra"

        fun resumeMismatch(
            of: String,
            named: String,
        ) = "That run is of $of, not \"$named\". Resume it without naming a workflow"

        fun wontGetThrough(name: String) = "zopf: $name didn't start, because it wouldn't get through the run. Fix these and try again:"

        fun dryRunHeader(
            name: String,
            nodes: Int,
        ) = "$name · $nodes nodes · nothing started"

        fun wave(
            index: Int,
            ids: String,
        ) = "  ${index + 1}. $ids"

        fun outOfTime(seconds: Int) = "Out of time. The run was given ${seconds}s."

        fun approvedByPolicy() = "Approved by --on-gate approve"

        fun rejectedByPolicy() = "Rejected by --on-gate reject"

        fun gateNeedsPolicy(title: String) = "$title is a gate and nobody is here to answer it. Pass --on-gate approve or --on-gate reject to decide up front."

        fun inputNeedsAnswer(id: String) = "Nothing answers $id and it declares no default. Pass --answer $id=<value>."

        fun answerUnknownNode(
            id: String,
            name: String,
        ) = "--answer $id=…: $name has no node called \"$id\""

        fun answerWrongNodeType(
            id: String,
            type: String,
        ) = "--answer $id=…: \"$id\" is a $type node, not an input"

        fun answerNotAChoice(
            id: String,
            value: String,
            choices: String,
        ) = "--answer $id=$value: \"$id\" offers $choices"

        fun resuming(
            id: String,
            carried: String,
            redo: String,
        ) = "Resuming $id · $carried · $redo"

        fun carryingOver(names: String) = "carrying over $names"

        fun runningNodes(names: String) = "running $names"

        fun optionNeedsNumber(
            name: String,
            raw: String,
        ) = "--$name takes a number, not \"$raw\""

        fun optionOutOfRange(
            name: String,
            first: Int,
            last: Int,
        ) = "--$name has to be between $first and $last"

        fun optionNeedsChoice(
            name: String,
            values: String,
            raw: String,
        ) = "--$name takes $values, not \"$raw\""

        fun optionNeedsPair(
            name: String,
            raw: String,
        ) = "--$name takes name=value, not \"$raw\""

        fun unknownOption(
            name: String,
            all: String,
        ) = "Unknown option --$name. This command takes $all"

        fun optionNeedsValue(name: String) = "--$name needs a value"

        fun versionTakesSemver(raw: String) = "--version takes x.y.z, not \"$raw\""

        fun alreadyInstalled(version: String) = "zopf $version is already what's installed"

        fun noCliAsset(
            version: String,
            url: String,
        ) = "zopf: release $version publishes no command line to install. $url"

        fun wouldInstall(
            version: String,
            target: Any,
            link: Any,
        ) = "would install $version to $target and point $link at it"

        fun downloading(version: String) = "Downloading zopf $version"

        fun couldntDownload(
            name: Any,
            reason: String?,
        ) = "zopf: couldn't download $name: $reason"

        fun noChecksum(version: String) = "note: $version publishes no .sha256, so the download wasn't verified"

        fun checksumMismatch(name: Any) = "zopf: $name isn't the file GitHub says it is. Nothing was installed."

        fun couldntUnpack(
            name: Any,
            reason: String?,
        ) = "zopf: couldn't unpack $name: $reason"

        fun noBinaryInside(name: Any) = "zopf: $name holds no bin/zopf where one was expected. Nothing was installed."

        fun updated(
            from: String,
            to: String,
            link: Any,
        ) = "Updated zopf $from → $to at $link"

        fun prunedRuns(
            gone: Int,
            keep: Int,
        ) = "Deleted $gone archived runs, keeping the last $keep. See Settings › History."
    }

    object Help {
        const val USAGE = """zopf runs agent workflows from a terminal, driving the claude, codex or dsh CLI you already have.

zopf run <workflow>       run a workflow to the end, then exit with its verdict
  --workspace <dir>       workspace to read from (default: the one around you)
  --repo <id>=<path>      point a repo the workflow declares at another path
  --on-gate <policy>      what to do at a gate: approve, reject or fail (default)
  --answer <node>=<value> answer an input node up front
  --concurrency <n>       how many nodes may run at once (overrides settings.json)
  --model <name>          model for nodes that don't name one
  --provider <name>       claude, codex or dsh, for agent nodes that don't name one
  --timeout <seconds>     stop the run if it hasn't finished by then
  --format <format>       text (default), json (the archive's NDJSON) or quiet
  --resume <run-id>       redo the run from where it stopped ("last" is the newest)
  --dry-run               print the order nodes would run in, start nothing

zopf list                 list the workflows here, including the ones that don't parse
  --workspace <dir>

zopf validate [workflow]  run the editor's checks as an exit code, every workflow by default
  --workspace <dir>

zopf runs                 list the run archive, newest first
  --last <n>              how many to show (default 10)

zopf prune                delete archived runs, oldest first
  --keep <n>              how many to keep (default 200)
  --older-than <days>     only delete runs finished more than this many days ago
  --dry-run               list what would go, delete nothing

zopf version              print the version of this build, and any newer one already known

zopf check-update         ask GitHub whether a newer zopf has been released

zopf upgrade              install the newest release over this one and relink zopf
  --version <x.y.z>       a specific release, rather than the newest published one
  --dry-run               say what it would install, download nothing

The workspace is either the .zopf dir, the nearest parent that has one, or
~/.zopf. Use zopf.yaml for workspace-wide defaults.

Exit codes: 0 run successful, 1 a node failed with nothing to catch it, 2 the run was
stopped, 3 bad usage."""
    }
}
