package com.dk.zopf.cli

import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.Workspace
import com.dk.zopf.store.resolvePathAgainst
import java.nio.file.Path
import java.nio.file.Paths

fun locateWorkspace(
    requested: String?,
    from: Path = Paths.get("").toAbsolutePath(),
): Workspace {
    if (requested != null) {
        val dir = resolvePathAgainst(requested, from)
        return Workspace.open(dir)
            ?: throw UsageError("$dir isn't a workspace, and has no .zopf directory in it")
    }

    enclosingWorkspace(from)?.let { return it }

    return Workspace.open(AppPaths.defaultWorkspace)
        ?: throw UsageError(
            "No workspace here. zopf looks for a .zopf directory from $from upwards, then in " +
                "${AppPaths.defaultWorkspace}. Pass --workspace <dir> to name one.",
        )
}

fun enclosingWorkspace(from: Path): Workspace? {
    var current: Path? = from.toAbsolutePath().normalize()
    while (current != null) {
        Workspace.open(current)?.let { return it }
        current = current.parent
    }
    return null
}
