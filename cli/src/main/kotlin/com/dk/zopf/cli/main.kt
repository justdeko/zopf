package com.dk.zopf.cli

import com.dk.zopf.store.Log
import com.dk.zopf.util.Strings
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

        "upgrade" -> {
            upgradeCli(Options.parse(rest, UPGRADE_OPTIONS, UPGRADE_SWITCHES), out, err)
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
            throw UsageError(Strings.Cli.noSuchCommand(command))
        }
    }
}

fun usage(out: PrintStream) {
    out.println(braid(colour = System.console() != null))
    out.println()
    out.println(Strings.Help.USAGE)
}
