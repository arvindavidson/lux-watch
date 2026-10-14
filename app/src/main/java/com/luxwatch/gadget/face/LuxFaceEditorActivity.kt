package com.luxwatch.gadget.face

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.watchface.editor.EditorSession
import kotlinx.coroutines.launch

/**
 * The Lux face's Customize screen (long-press the face → Customize): pick
 * what the two complications show. The editor session closes itself with the
 * activity (closing it again crashed the app).
 */
class LuxFaceEditorActivity : ComponentActivity() {
    private var session by mutableStateOf<EditorSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            session = EditorSession.createOnWatchEditorSession(this@LuxFaceEditorActivity).apply {
                // Save the choices when the editor closes (the default, made explicit).
                commitChangesOnClose = true
            }
        }
        setContent {
            MaterialTheme {
                ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
                    item { ListHeader { Text("Lux face") } }
                    item { SlotChip("Left complication", LuxWatchFaceService.LEFT_SLOT) }
                    item { SlotChip("Right complication", LuxWatchFaceService.RIGHT_SLOT) }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Done") },
                            onClick = { finish() },
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SlotChip(label: String, slotId: Int) {
        // What the slot shows now, so a change is visible straight away.
        val sources = session?.complicationsDataSourceInfo?.collectAsState()?.value
        val current = sources?.get(slotId)?.name
        Chip(
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            secondaryLabel = { Text(if (session == null) "\u2026" else current ?: "Empty \u2014 tap to choose") },
            enabled = session != null,
            onClick = {
                val s = session ?: return@Chip
                lifecycleScope.launch { s.openComplicationDataSourceChooser(slotId) }
            },
            colors = ChipDefaults.secondaryChipColors(),
        )
    }

}
