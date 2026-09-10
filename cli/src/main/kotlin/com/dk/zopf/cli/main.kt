package com.dk.zopf.cli

import com.dk.zopf.store.Log
import java.io.PrintStream
import kotlin.system.exitProcess

const val EXIT_OK = 0
const val EXIT_FAILED = 1
const val EXIT_STOPPED = 2
const val EXIT_USAGE = 3

fun main(args: Array<String>) {
    exitProcess(zopf(args.toList(), System.out, System.err))
}

fun zopf(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int =
    try {
        dispatch(args, out, err)
    } catch (usage: UsageError) {
        err.println("zopf: ${usage.message}")
        EXIT_USAGE
    } catch (failure: Throwable) {
        Log.error("zopf ${args.joinToString(" ")}", failure)
        err.println("zopf: ${failure.message ?: failure}")
        err.println("More in ${Log.file}")
        EXIT_FAILED
    }

private fun dispatch(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val command = args.firstOrNull()
    val rest = args.drop(1)
    return when (command) {
        null, "help", "--help", "-h" -> {
            usage(out)
            EXIT_OK
        }

        "version", "--version", "-v" -> {
            printVersion(out, err)
            EXIT_OK
        }

        "check-update" -> {
            checkForUpdate(out, err)
        }

        "run" -> {
            runWorkflow(Options.parse(rest, RUN_OPTIONS, RUN_SWITCHES), out, err)
        }

        "list" -> {
            listWorkflows(Options.parse(rest, LIST_OPTIONS), out)
        }

        "validate" -> {
            validateWorkflows(Options.parse(rest, VALIDATE_OPTIONS), out)
        }

        "runs" -> {
            listRuns(Options.parse(rest, RUNS_OPTIONS), out)
            EXIT_OK
        }

        "prune" -> {
            pruneRuns(Options.parse(rest, PRUNE_OPTIONS, PRUNE_SWITCHES), out)
            EXIT_OK
        }

        else -> {
            throw UsageError("No \"$command\" command. Try run, list, validate, runs, prune or check-update")
        }
    }
}

fun usage(out: PrintStream) {
    out.println(braid(colour = System.console() != null))
    out.println()
    out.println(
        """
        zopf runs agent workflows from a terminal, driving the claude, codex or dsh CLI you already have.

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

        The workspace is either the .zopf dir, the nearest parent that has one, or
        ~/.zopf. Use zopf.yaml for workspace-wide defaults.

        Exit codes: 0 run successful, 1 a node failed with nothing to catch it, 2 the run was
        stopped, 3 bad usage.
        """.trimIndent(),
    )
}
