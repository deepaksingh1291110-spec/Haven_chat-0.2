// =====================================================================
// SettingsScreen.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: The settings screen from your design:
//         top bar:  [back]  Settings            [Save & Reload]
//         card:     Model Configuration (threads, context, thinking)
//         field:    model file path + Browse button
//         card:     Storage Status (file access allowed or not)
//         button:   Load Model
//         card:     System Prompt Editor (prompt, stop sequence, reset)
// HOW IT WORKS: the screen edits a COPY of the settings ("draft").
//   Nothing is applied until the user taps "Save & Reload", which hands
//   the draft to the caller (onSaveAndReload). This matches your notes:
//   changing context size or threads needs the model to reload.
// NOT YET: the "Inference Parameters" card (temperature, etc.) from the
//   design. We add it later when the engine exists.
// =====================================================================
package com.haven.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------
// DEFAULT VALUES. "const val" = a fixed value known at build time.
// TODO: replace DEFAULT_SYSTEM_PROMPT with your real tested prompt from
//       the old app when we reach Phase 2.
// ---------------------------------------------------------------------
const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Answer clearly and briefly."
const val DEFAULT_STOP_SEQUENCE = "<|endoftext|>"
const val DEFAULT_MODEL_PATH = "/storage/emulated/0/HavenModels/gemma4-e2b-q4_0.gguf"

private const val MAX_PROMPT_CHARS = 2000   // longest allowed system prompt
private const val MAX_STOP_CHARS = 100      // longest allowed stop sequence

// ---------------------------------------------------------------------
// All user settings in ONE container. "copy(...)" makes a new copy with
// one value changed (the old one is never edited in place).
// ---------------------------------------------------------------------
data class AppSettings(
    val threads: Int = 4,                          // CPU threads (1 to 6)
    val contextSize: Int = 4096,                   // 2048, 4096 or 8192
    val thinkingEnabled: Boolean = true,           // show the AI's thinking
    val modelPath: String = DEFAULT_MODEL_PATH,    // where the GGUF file is
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val stopSequence: String = DEFAULT_STOP_SEQUENCE
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,                    // settings when the screen opens
    storageAllowed: Boolean,                 // is "All files access" granted?
    onBack: () -> Unit,                      // back arrow tapped
    onSaveAndReload: (AppSettings) -> Unit,  // "Save & Reload" tapped
    onLoadModel: () -> Unit,                 // "Load Model" tapped
    onBrowseClick: () -> Unit,               // "Browse" tapped (file picker later)
    onStorageClick: () -> Unit               // storage badge tapped (permission)
) {
    // The draft: what the user is editing right now.
    var draft by remember { mutableStateOf(initial) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { onSaveAndReload(draft) }) {
                        Text("Save & Reload")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        // verticalScroll = the page scrolls when it is taller than the screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()                        // move up with the keyboard
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ================= MODEL CONFIGURATION =================
            SettingsCard(title = "Model Configuration") {

                // --- Threads slider ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Threads", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${draft.threads}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = draft.threads.toFloat(),            // slider uses decimals
                    onValueChange = { draft = draft.copy(threads = it.roundToInt()) },
                    valueRange = 1f..6f,
                    steps = 4                                   // 4 stops between 1 and 6
                )

                // --- Context window chips ---
                Text("Context window", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Draw one chip for each allowed size.
                    listOf(2048, 4096, 8192).forEach { size ->
                        FilterChip(
                            selected = draft.contextSize == size,
                            onClick = { draft = draft.copy(contextSize = size) },
                            label = { Text("$size") }
                        )
                    }
                }
                // RAM warning from your project notes (Android can kill the app).
                if (draft.contextSize == 8192) {
                    Text(
                        text = "8192 uses much more RAM. The app may be closed by Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                // --- Thinking on / off ---
                Text("Reasoning", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.thinkingEnabled,
                        onClick = { draft = draft.copy(thinkingEnabled = true) },
                        label = { Text("Thinking ON") }
                    )
                    FilterChip(
                        selected = !draft.thinkingEnabled,
                        onClick = { draft = draft.copy(thinkingEnabled = false) },
                        label = { Text("Standard") }
                    )
                }
            }

            // ================= MODEL FILE PATH =================
            SettingsCard(title = "Model file") {
                OutlinedTextField(
                    value = draft.modelPath,
                    onValueChange = { draft = draft.copy(modelPath = it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Path to GGUF file") },
                    singleLine = true
                )
                OutlinedButton(onClick = onBrowseClick) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text("  Browse")
                }
            }

            // ================= STORAGE STATUS =================
            SettingsCard(title = "Storage Status") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onStorageClick),   // tap to grant access
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("File access", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Internal shared storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Small badge: "Allowed" or "Tap to allow".
                    Text(
                        text = if (storageAllowed) "Allowed" else "Tap to allow",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (storageAllowed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // ================= LOAD MODEL BUTTON =================
            Button(
                onClick = onLoadModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Load Model", style = MaterialTheme.typography.labelLarge)
            }

            // ================= SYSTEM PROMPT EDITOR =================
            SettingsCard(title = "System Prompt Editor") {
                OutlinedTextField(
                    value = draft.systemPrompt,
                    onValueChange = {
                        // Accept the new text only while it is short enough.
                        if (it.length <= MAX_PROMPT_CHARS) draft = draft.copy(systemPrompt = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Prompt") },
                    minLines = 3,
                    supportingText = {
                        Text("Sent at the start of the context window.  " +
                                "${draft.systemPrompt.length} / $MAX_PROMPT_CHARS")
                    }
                )
                OutlinedTextField(
                    value = draft.stopSequence,
                    onValueChange = {
                        if (it.length <= MAX_STOP_CHARS) draft = draft.copy(stopSequence = it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Stop sequence") },
                    singleLine = true,
                    supportingText = {
                        Text("Token that stops generation.  " +
                                "${draft.stopSequence.length} / $MAX_STOP_CHARS")
                    }
                )
                // Put prompt and stop sequence back to the defaults above.
                TextButton(
                    onClick = {
                        draft = draft.copy(
                            systemPrompt = DEFAULT_SYSTEM_PROMPT,
                            stopSequence = DEFAULT_STOP_SEQUENCE
                        )
                    }
                ) {
                    Text("Reset to default")
                }
            }

            // Empty space at the bottom so the last card is not cramped.
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------
// HELPER: a rounded card with a title and content inside.
// "content" is a block of UI that the caller puts inside the card.
// ---------------------------------------------------------------------
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
