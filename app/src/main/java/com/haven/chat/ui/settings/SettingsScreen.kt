// =====================================================================
// SettingsScreen.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: The settings screen from your design:
//         top bar:  [back]  Settings            [Save & Reload]
//         card:     Model Configuration (threads, context, thinking,
//                   auto-name chats, memory thinking, memory search)
//         field:    model file path + Browse button
//         card:     Storage Status (file access allowed or not)
//         button:   Load Model
//         card:     System Prompt Editor (prompt, stop sequence, reset)
// HOW IT WORKS: the screen edits a COPY of the settings ("draft").
//   Nothing is applied until the user taps "Save & Reload" or
//   "Load Model", which hand the draft to the caller. This matches your
//   notes: changing context size or threads needs the model to reload.
//
// PHASE 2 CHANGES:
//   - Browse now opens Android's file picker. The picked file is turned
//     into a normal path (/storage/emulated/0/...) and put in the box.
//   - The Load Model button shows the REAL engine status: "Loading
//     model..." (with a small spinner), "Model loaded", or an error text.
//   - Load Model now loads what is ON SCREEN (the draft), not the older
//     saved settings. So onLoadModel receives the draft.
//   - onBrowseClick is gone: the picker lives inside this file.
//
// PHASE 3 CHANGES (marked "PHASE 3"):
//   - New setting "Auto-name chats" (default OFF): a switch in the
//     Model Configuration card. AppSettings has a new field autoNameChats.
//   - More context window sizes for phones with a lot of RAM:
//     2048, 4096, 8192, 16384 and 32768. The chips wrap to a second
//     line if they do not fit. The RAM warning now also covers 16384+.
//   - The Load Model button is also switched off while Gemma is naming
//     a chat (the engine is busy then).
//   - Saving to disk is NOT done here. SettingsViewModel does it (next
//     files), so this screen stays simple.
//
// MAX REPLY LENGTH (added after Phase 3):
//   A long answer stopped in the middle because the reply limit was fixed
//   at 1024 tokens (and the thinking text counts too). Now the user types
//   the limit in the Model Configuration card: "Max reply length (tokens)",
//   from 64 to 32768, default 2048. AppSettings has a new field
//   maxReplyTokens. The box turns red for a number outside the range and
//   the last valid number is kept. Saving to disk is done by SettingsStore,
//   and ChatViewModel uses the value instead of the old fixed 1024.
//
// PHASE 4 CHANGES (marked "PHASE 4" and "STEP 6"):
//   - New setting "Memory thinking" (default ON): a switch in the Model
//     Configuration card. AppSettings has a new field memoryThinking. It
//     decides whether Gemma uses thinking when it looks for the PERSONAL
//     messages of a chat ("remember my personal things from this chat" and
//     the delete-chat step). Thinking found every personal message in the
//     user's tests but is slower; OFF is faster but missed some.
//     It is separate from the "Reasoning" chips above, which are only for
//     normal chat. Saving to disk is done by SettingsStore.
//   - The Load Model button is also switched off while Gemma is saving
//     memories (the engine is busy then), and says "Saving memories...".
//   - STEP 6: new setting "Memory search" (default ON): a switch right under
//     "Memory thinking". AppSettings has a new field memorySearch. ON means
//     Gemma may look up the saved memories during a chat answer (the
//     search_memory tool) when the user asks about their own past. OFF means
//     Gemma never gets the tool, and the chat works exactly as before. The
//     tool adds a few lines to every chat prompt, so the switch is the way
//     to turn it off if it ever gets in the way.
//
// NOT YET: the "Inference Parameters" card (temperature, etc.) from the
//   design. We add it later.
// =====================================================================
package com.haven.chat.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.haven.chat.engine.EngineManager
import kotlin.math.roundToInt

// ---------------------------------------------------------------------
// DEFAULT VALUES. "const val" = a fixed value known at build time.
// TODO: replace DEFAULT_SYSTEM_PROMPT with your real tested prompt from
//       the old app later.
// ---------------------------------------------------------------------
const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant. Answer clearly and briefly."
const val DEFAULT_STOP_SEQUENCE = "<|endoftext|>"
const val DEFAULT_MODEL_PATH = "/storage/emulated/0/HavenModels/gemma4-e2b-q4_0.gguf"

private const val MAX_PROMPT_CHARS = 2000   // longest allowed system prompt
private const val MAX_STOP_CHARS = 100      // longest allowed stop sequence

// PHASE 3: the context window sizes the user can choose.
// The big ones (16384, 32768) are for phones with a lot of RAM.
private val CONTEXT_SIZES = listOf(2048, 4096, 8192, 16384, 32768)

// MAX REPLY LENGTH (tokens Gemma may write for ONE reply; thinking counts too).
// The user types the number. The default and the allowed range live here so
// SettingsStore and ChatViewModel use the same values.
const val DEFAULT_MAX_REPLY_TOKENS = 2048
const val MIN_REPLY_TOKENS = 64
const val MAX_REPLY_TOKENS = 32768

// ---------------------------------------------------------------------
// All user settings in ONE container. "copy(...)" makes a new copy with
// one value changed (the old one is never edited in place).
// ---------------------------------------------------------------------
data class AppSettings(
    val threads: Int = 4,                          // CPU threads (1 to 6)
    val contextSize: Int = 4096,                   // one of CONTEXT_SIZES
    val thinkingEnabled: Boolean = true,           // show the AI's thinking
    val modelPath: String = DEFAULT_MODEL_PATH,    // where the GGUF file is
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val stopSequence: String = DEFAULT_STOP_SEQUENCE,
    val autoNameChats: Boolean = false,            // PHASE 3: let Gemma title new chats (default OFF)
    val maxReplyTokens: Int = DEFAULT_MAX_REPLY_TOKENS, // longest reply in tokens (thinking + answer), typed by the user
    val memoryThinking: Boolean = true,            // PHASE 4: use thinking when Gemma looks for personal messages (default ON)
    val memorySearch: Boolean = true               // STEP 6: Gemma may search the saved memories in a chat (default ON)
)

// ---------------------------------------------------------------------
// HELPER: turns the "address" that Android's file picker gives us into a
// normal file path that the C++ engine can open.
//
// The picker returns something like
//   content://com.android.externalstorage.documents/document/primary%3AHavenModels%2Fgemma.gguf
// Its "document id" is   primary:HavenModels/gemma.gguf
// "primary:" means the phone's internal storage = /storage/emulated/0/
//
// Returns null when we cannot make a normal path (SD card, cloud, ...).
// ---------------------------------------------------------------------
private fun pathFromPickedUri(uri: Uri): String? {
    return try {
        val id = DocumentsContract.getDocumentId(uri)
        when {
            // Internal storage: "primary:folder/file.gguf"
            id.startsWith("primary:") -> "/storage/emulated/0/" + id.removePrefix("primary:")
            // Some pickers hand over a ready path: "raw:/storage/..."
            id.startsWith("raw:") -> id.removePrefix("raw:")
            // SD card, Downloads app ids, cloud drives: no usable path.
            else -> null
        }
    } catch (e: Exception) {
        null   // not a document address
    }
}

// ExperimentalLayoutApi is needed for FlowRow (chips that wrap to a new line).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,                    // settings when the screen opens
    storageAllowed: Boolean,                 // is "All files access" granted?
    onBack: () -> Unit,                      // back arrow tapped
    onSaveAndReload: (AppSettings) -> Unit,  // "Save & Reload" tapped
    onLoadModel: (AppSettings) -> Unit,      // "Load Model" tapped (gets what is on screen)
    onStorageClick: () -> Unit               // storage badge tapped (permission)
) {
    // The draft: what the user is editing right now.
    var draft by remember { mutableStateOf(initial) }

    // A short message under the Browse button (for example "not a .gguf file").
    var browseMessage by remember { mutableStateOf("") }

    // MAX REPLY LENGTH: the text exactly as the user types it. It is kept
    // apart from the draft because "" or "5" is allowed while typing, but
    // the draft only ever receives a VALID number.
    var maxReplyText by remember { mutableStateOf(initial.maxReplyTokens.toString()) }

    // The file picker. "OpenDocument" shows Android's normal file chooser.
    // When the user picks a file, the lambda below receives its address
    // (or null if the user cancelled).
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val path = pathFromPickedUri(uri)
            browseMessage = when {
                path == null ->
                    "Cannot use this location. Pick the file from internal storage (for example the HavenModels folder)."
                !path.endsWith(".gguf", ignoreCase = true) ->
                    "That is not a .gguf file."
                else -> {
                    draft = draft.copy(modelPath = path)   // put it in the box
                    ""
                }
            }
        }
    }

    // The REAL engine status. EngineManager.state is Compose state, so
    // this screen redraws by itself when loading starts or ends.
    val engineState = EngineManager.state

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
                // PHASE 3: five sizes now. FlowRow puts the chips side by side
                // and moves the ones that do not fit to the next line.
                Text("Context window", style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Draw one chip for each allowed size.
                    CONTEXT_SIZES.forEach { size ->
                        FilterChip(
                            selected = draft.contextSize == size,
                            onClick = { draft = draft.copy(contextSize = size) },
                            label = { Text("$size") }
                        )
                    }
                }
                // RAM warnings (Android can kill the app when RAM runs out).
                if (draft.contextSize >= 16384) {
                    Text(
                        text = "${draft.contextSize} needs a phone with a lot of free RAM. " +
                                "The app may be closed by Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (draft.contextSize == 8192) {
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

                // --- MAX REPLY LENGTH: the user types the number ---
                // How many tokens Gemma may write for ONE reply. The thinking
                // text counts too. When the limit is reached the reply simply
                // stops (that is why a long answer can end in the middle).
                // Only a number from MIN to MAX is accepted into the draft;
                // otherwise the box turns red and the last valid number stays.
                val replyNumber = maxReplyText.toIntOrNull()
                val replyError = replyNumber == null ||
                        replyNumber < MIN_REPLY_TOKENS || replyNumber > MAX_REPLY_TOKENS
                OutlinedTextField(
                    value = maxReplyText,
                    onValueChange = { raw ->
                        // Keep digits only, at most 5 of them.
                        val digits = raw.filter { it.isDigit() }.take(5)
                        maxReplyText = digits
                        val n = digits.toIntOrNull()
                        if (n != null && n in MIN_REPLY_TOKENS..MAX_REPLY_TOKENS) {
                            draft = draft.copy(maxReplyTokens = n)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Max reply length (tokens)") },
                    singleLine = true,
                    isError = replyError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            if (replyError) {
                                "Enter a number from $MIN_REPLY_TOKENS to $MAX_REPLY_TOKENS."
                            } else {
                                "Thinking counts too. Longer replies take longer to write."
                            }
                        )
                    }
                )

                // --- PHASE 3: Auto-name chats switch ---
                // OFF (default): a new chat gets the first words of your
                // first message as its title, instantly.
                // ON: after the first answer, Gemma writes a better title in
                // the background (a few seconds; the input is locked while
                // it works).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-name chats", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Gemma writes a short title after the first answer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.autoNameChats,
                        onCheckedChange = { draft = draft.copy(autoNameChats = it) }
                    )
                }

                // --- PHASE 4: Memory thinking switch ---
                // ON (default): when Gemma looks for the PERSONAL messages of
                // a chat, it thinks first. In the user's tests this found
                // every personal message, but it takes longer.
                // OFF: faster, but it can miss a personal message that is
                // mixed with a general question.
                // Only the memory job reads this setting, not normal chat.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Memory thinking", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Gemma thinks when it looks for your personal messages. " +
                                    "More accurate, but slower.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.memoryThinking,
                        onCheckedChange = { draft = draft.copy(memoryThinking = it) }
                    )
                }

                // --- STEP 6: Memory search switch ---
                // ON (default): during a chat answer Gemma may look up your
                // saved memories (the search_memory tool) when you ask about
                // your own past. It only does this when you have saved
                // memories.
                // OFF: Gemma never gets the tool; chat works as before.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Memory search", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Gemma may look up your saved memories when you ask " +
                                    "about your own past.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.memorySearch,
                        onCheckedChange = { draft = draft.copy(memorySearch = it) }
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
                // Browse: open the file picker ("*/*" = show all file types,
                // because Android does not know the .gguf type).
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text("  Browse")
                }
                if (browseMessage.isNotEmpty()) {
                    Text(
                        text = browseMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
            // The button text follows the real engine status.
            // It is switched off while loading, while Gemma is answering,
            // while Gemma is naming a chat (PHASE 3) and while Gemma is
            // saving memories (PHASE 4).
            Button(
                onClick = { onLoadModel(draft) },
                enabled = engineState != EngineManager.State.LOADING &&
                          engineState != EngineManager.State.THINKING &&
                          engineState != EngineManager.State.NAMING &&
                          engineState != EngineManager.State.MEMORIZING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                // A small spinner while the model is being read into RAM.
                if (engineState == EngineManager.State.LOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = when (engineState) {
                        EngineManager.State.LOADING -> "Loading model..."
                        EngineManager.State.READY -> "Model loaded (tap to reload)"
                        EngineManager.State.THINKING -> "Gemma is answering..."
                        EngineManager.State.NAMING -> "Naming chat..."
                        EngineManager.State.MEMORIZING -> "Saving memories..."
                        else -> "Load Model"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
            // If loading failed, show the plain-words reason right here.
            if (engineState == EngineManager.State.ERROR && EngineManager.errorMessage.isNotEmpty()) {
                Text(
                    text = EngineManager.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
