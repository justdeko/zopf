package com.dk.zopf.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dk.zopf.model.NodeType
import com.dk.zopf.model.RepoRef
import com.dk.zopf.model.Workflow
import com.dk.zopf.model.WorkflowIssue
import com.dk.zopf.model.WorkflowNode
import com.dk.zopf.model.label
import com.dk.zopf.model.validate
import com.dk.zopf.ui.preview.PreviewFixtures
import com.dk.zopf.ui.theme.ZopfIcons
import com.dk.zopf.ui.theme.ZopfTheme

@Composable
fun WorkflowOverview(
    workflow: Workflow,
    issues: List<WorkflowIssue>,
    onSelectNode: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val errors = issues.filter { it.severity == WorkflowIssue.Severity.ERROR }
    val warnings = issues.filter { it.severity == WorkflowIssue.Severity.WARNING }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Readiness(errors.size, warnings.size)

        if (errors.isNotEmpty()) {
            Gap()
            SectionLabel("Stops the run")
            Gap(6)
            IssueList(errors, workflow, onSelectNode)
        }

        if (warnings.isNotEmpty()) {
            Gap()
            SectionLabel("Worth a look")
            Gap(6)
            IssueList(warnings, workflow, onSelectNode)
        }

        Gap()
        SectionLabel("At a glance")
        Gap(6)
        Tally(workflow)

        if (workflow.repos.isNotEmpty()) {
            Gap()
            SectionLabel("Repos")
            Gap(6)
            workflow.repos.forEach { Repo(it) }
        }

        Gap()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Gap(8)
        Text(
            "Select a node to edit it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Readiness(
    errors: Int,
    warnings: Int,
) {
    val ready = errors == 0
    val headline = if (ready) "Ready to run" else "Not ready to run"
    val detail =
        listOfNotNull(
            errors.takeIf { it > 0 }?.let { count(it, "error") },
            warnings.takeIf { it > 0 }?.let { count(it, "warning") },
        ).joinToString(", ").ifEmpty { "Nothing to fix." }

    Surface(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (ready) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val onContainer =
                if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
            Surface(
                Modifier.size(28.dp),
                shape = CircleShape,
                color = onContainer.copy(alpha = 0.12f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (ready) ZopfIcons.Check else ZopfIcons.Warning,
                        contentDescription = null,
                        Modifier.size(16.dp),
                        tint = onContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(headline, style = MaterialTheme.typography.titleSmall, color = onContainer)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun IssueList(
    issues: List<WorkflowIssue>,
    workflow: Workflow,
    onSelectNode: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        issues.forEach { issue ->
            IssueCard(issue, issue.nodeId?.let { workflow.node(it) }, onSelectNode)
        }
    }
}

@Composable
private fun IssueCard(
    issue: WorkflowIssue,
    node: WorkflowNode?,
    onSelectNode: (String) -> Unit,
) {
    val isError = issue.severity == WorkflowIssue.Severity.ERROR
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        Modifier
            .fillMaxWidth()
            .let { if (node == null) it else it.clickable { onSelectNode(node.id) } },
        shape = MaterialTheme.shapes.small,
        color =
            if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Icon(
                ZopfIcons.Warning,
                contentDescription = null,
                Modifier.size(14.dp).padding(top = 2.dp),
                tint = content,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(issue.message, style = MaterialTheme.typography.labelSmall, color = content)
                if (node != null) {
                    Gap(4)
                    NodeChip(node, content.copy(alpha = 0.7f))
                }
            }
        }
    }
}

@Composable
private fun NodeChip(
    node: WorkflowNode,
    tint: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(node.type.icon, contentDescription = null, Modifier.size(11.dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(
            "Open",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tally(workflow: Workflow) {
    if (workflow.nodes.isEmpty()) {
        Text(
            "No nodes yet. Add one from the toolbar and it will show up here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NodeType.entries
            .map { type -> type to workflow.nodes.count { it.type == type } }
            .filter { (_, tally) -> tally > 0 }
            .forEach { (type, tally) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        type.icon,
                        contentDescription = null,
                        Modifier.size(14.dp),
                        tint = ZopfTheme.colors.node(type).accent,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        count(tally, type.label.lowercase()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                ZopfIcons.Link,
                contentDescription = null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                count(workflow.edges.size, "edge"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Repo(repo: RepoRef) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            ZopfIcons.Folder,
            contentDescription = null,
            Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(repo.id, style = MaterialTheme.typography.labelMedium)
            Text(
                repo.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
private fun WorkflowOverviewPreview() {
    val workflow = PreviewFixtures.workflow()
    ZopfTheme {
        Surface(Modifier.width(320.dp)) {
            WorkflowOverview(workflow, workflow.validate(), onSelectNode = {})
        }
    }
}
