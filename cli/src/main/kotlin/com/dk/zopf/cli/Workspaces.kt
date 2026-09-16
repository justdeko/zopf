package com.dk.zopf.cli

import com.dk.zopf.store.AppPaths
import com.dk.zopf.store.workspace.Workspace
import com.dk.zopf.store.workspace.resolvePathAgainst
import com.dk.zopf.util.Strings
import java.nio.file.Path
import java.nio.file.Paths

fun locateWorkspace(
    requested: String?,
    from: Path = Paths.get("").toAbsolutePath(),
): Workspace {
    if (requested != null) {
        val dir = resolvePathAgainst(requested, from)
        return Workspace.open(dir)
            ?: throw UsageError(Strings.RunErrors.notAWorkspace(dir))
    }

    enclosingWorkspace(from)?.let { return it }

    return Workspace.open(AppPaths.defaultWorkspace)
        ?: throw UsageError(
            Strings.RunErrors.noWorkspaceHere(AppPaths.defaultWorkspace),
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
