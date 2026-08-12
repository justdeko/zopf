package com.dk.zopf.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import zopf.shared.generated.resources.Res

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val libraries by produceState<Libs?>(null) {
        value =
            Libs
                .Builder()
                .withJson(Res.readBytes("files/aboutlibraries.json").decodeToString())
                .build()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(max = 720.dp).heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("Open source licenses", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select an entry to show its full license.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val loaded = libraries) {
                        null -> {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        }

                        else -> {
                            LibrariesContainer(loaded, Modifier.fillMaxSize())
                        }
                    }
                }
                TextButton(onDismiss, Modifier.align(Alignment.End).padding(top = 8.dp)) {
                    Text("Done")
                }
            }
        }
    }
}
