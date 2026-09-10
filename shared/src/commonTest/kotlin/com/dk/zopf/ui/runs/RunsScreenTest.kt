package com.dk.zopf.ui.runs

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.dk.zopf.runtime.NodeExecution
import com.dk.zopf.runtime.NodeExecutor
import com.dk.zopf.runtime.Restore
import com.dk.zopf.runtime.RunRegistry
import com.dk.zopf.runtime.RunStatus
import com.dk.zopf.runtime.WorkflowRun
import com.dk.zopf.store.AppSettings
import com.dk.zopf.store.LiveSettings
import com.dk.zopf.store.RunRecord
import com.dk.zopf.ui.theme.ZopfTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test

class RunsScreenTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dirs.forEach { it.toFile().deleteRecursively() }
    }

    private object Unused : NodeExecutor {
        override suspend fun execute(execution: NodeExecution): Unit = error("${execution.node.id} tried to start a process")
    }

    private fun registry() =
        RunRegistry(
            scope,
            LiveSettings(AppSettings()),
            executor = Unused,
            archiveRoot = Files.createTempDirectory("zopf-runs-screen").also { dirs.add(it) },
        )

    private fun archived(
        id: String,
        startedAt: Instant,
    ) = WorkflowRun.restored(
        RunRecord(
            id = id,
            workflow = "ship-feature",
            startedAt = startedAt.toString(),
            finishedAt = startedAt.toString(),
            status = RunStatus.SUCCEEDED.name,
        ),
        Restore.SETTLED,
    )

    private fun stamp(
        pattern: String,
        at: Instant,
    ) = DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(ZoneId.systemDefault()).format(at)

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a listed run is stamped with when it started`() =
        runDesktopComposeUiTest(width = 900, height = 600) {
            val registry = registry()
            val today = Instant.now()
            val longAgo = today.minus(400, ChronoUnit.DAYS)
            registry.runs += archived("run-1", today)
            registry.runs += archived("run-2", longAgo)

            setContent { ZopfTheme { RunsScreen(registry) } }
            waitForIdle()

            onNodeWithText(stamp("HH:mm", today)).assertIsDisplayed()
            onNodeWithText(stamp("yyyy-MM-dd HH:mm", longAgo)).assertIsDisplayed()
        }
}
