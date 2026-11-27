// =====================================================================
// SettingsScreen.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: The settings screen from your design:
//         top bar:  [back]  Settings            [Save & Reload]
//         card:     Model Configuration (threads, context, thinking,
//                   auto-name chats, memory thinking, memory search,
//                   heavy search)
//         card:     Heavy search models (only when heavy search is ON):
//                   embedder + reranker, each chosen from the added models
//         card:     Model: the MAIN model, chosen from the added models,
//                   and a button "Manage models"
//         button:   Load Model
//         card:     System Prompt Editor (prompt, stop sequence, reset)
//         card:     Memory Prompt Editor (the prompt that finds the personal
//                   messages and writes their tags, with reset)
// HOW IT WORKS: the screen edits a COPY of the settings ("draft").
//   Nothing is applied until the user taps "Save & Reload" or
//   "Load Model", which hand the draft to the caller. This matches your
//   notes: changing context size or threads needs the model to reload.
//
// PHASE 2 CHANGES:
//   - The Load Model button shows the REAL engine status: "Loading
//     model..." (with a small spinner), "Model loaded", or an error text.
//   - Load Model now loads what is ON SCREEN (the draft), not the older
//     saved settings. So onLoadModel receives the draft.
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
// PHASE 4 CHANGES (marked "PHASE 4", "STEP 6" and "STEP 7"):
//   - "Memory thinking" (default ON), "Memory search" (default ON) and
//     "Heavy search" (default OFF) switches, and the Memory Prompt Editor
//     (empty = use the built-in prompt; effectiveMemoryPrompt(settings)
//     gives the prompt that is really used).
//   - The Load Model button is also switched off while Gemma is saving
//     memories (the engine is busy then), and says "Saving memories...".
//
// PRIVATE MODELS (marked "MODELS"): NO MORE "ALL FILES ACCESS".
//   - The model files now live in the app's private space (ModelStore) and
//     are added on the Model management screen. So this screen has NO file
//     picker, NO "Browse" buttons and NO Storage Status card any more.
//   - The MAIN model, the EMBEDDER and the RERANKER are chosen from a drop
//     down list of the models that were added (ModelPicker below). They
//     still write the same three settings: modelPath, embedderPath,
//     rerankerPath. The Model management screen writes the same ones.
//   - A button "Manage models" opens the Model management screen
//     (onManageModels). Edits that were not saved are lost when leaving
//     this screen for it.
//   - The Load Model button is switched off while no main model is chosen
//     (or its file is gone) and says why.
//   - DEFAULT_MODEL_PATH is now empty: a new install has no model chosen.
//   - The two parameters storageAllowed and onStorageClick are gone.
//
// LONG PROMPTS (marked "PROMPTS"): the System Prompt and the Memory Prompt
//   boxes are COLLAPSED: they show 6 lines only, so the page is short. Tapping
//   a box opens it fully (and starts editing). A "Show less" button folds it
//   again. See ExpandablePromptField.
//
// NOT YET: the "Inference Parameters" card (temperature, etc.) from the
//   design. We add it later.
// =====================================================================
package com.haven.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ModelStore
import com.haven.chat.data.StoredModel
import com.haven.chat.engine.EngineManager
import com.haven.chat.engine.MemoryPrompts
import java.io.File
import kotlin.math.roundToInt

// ---------------------------------------------------------------------
// DEFAULT VALUES. "const val" = a fixed value known at build time.
// TODO: replace DEFAULT_SYSTEM_PROMPT with your real tested prompt from
//       the old app later.
// ---------------------------------------------------------------------

const val DEFAULT_SYSTEM_PROMPT = """You are a helpful assistant with a long-term memory. Today is {today}.

First decide the type of question.
- About the world (history, science, definitions, how things work, who or what something is): answer directly from your own knowledge. Do not call any memory tool.
- About the user (words like my, I, we, our, last time, before, remind me, what did I, earlier, my project): use the memory steps below.

MEMORY STEPS for questions about the user's past (their projects, earlier chats, things they told you before). Follow them in order:
1. Call the tool search_memory with 3-6 short keywords and synonyms. Use the real tool call, never write a call as plain text.
2. Read the result. If it clearly answers the question, answer from it.
3. If it says no memory matched, or the rows do not answer the question, and the tool deep_search_memory is available, call deep_search_memory with a short query in plain words. If that tool is not available, skip this step.
4. If the tools returned nothing useful, say plainly that you have no saved record of it. You may still offer general help, but say clearly that it is general advice and not from the user's past.
Never guess about the user's past.

Example: user asks "how did I fix my counter timing error?"
search_memory says no memory matched -> if deep_search_memory is available, call it with "fix counter timing error" -> answer only from the result, or say you have no record.

COLORED TEXT: You may color words using HTML <span> tags, for example: <span style="color:blue">word</span>. Use straight quotes ("), always close tags with </span>, and color words only when useful.
Allowed colors: red, orange, yellow, gold, lime, green, teal, cyan, blue, purple, pink, magenta, brown, gray, white.
Aliases: aqua = cyan, violet = purple, grey = gray."""

const val DEFAULT_STOP_SEQUENCE = "<|endoftext|>"

// MODELS: empty = no model chosen yet (a new install). The user adds a model
// on the Model management screen, and the first one is chosen automatically.
const val DEFAULT_MODEL_PATH = ""

private const val MAX_PROMPT_CHARS = 2000   // longest allowed system prompt
private const val MAX_STOP_CHARS = 100      // longest allowed stop sequence

// PROMPTS: how many lines a long prompt box shows while it is folded.
private const val COLLAPSED_PROMPT_LINES = 6

// STEP 7: longest memory (extraction) prompt. The built-in one is about
// 3000 characters, so there is plenty of room for edits. Public, because
// SettingsStore uses it too.
const val MAX_MEMORY_PROMPT_CHARS = 8000

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
    val modelPath: String = DEFAULT_MODEL_PATH,    // MAIN model file ("" = none chosen)
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val stopSequence: String = DEFAULT_STOP_SEQUENCE,
    val autoNameChats: Boolean = false,            // PHASE 3: let Gemma title new chats (default OFF)
    val maxReplyTokens: Int = DEFAULT_MAX_REPLY_TOKENS, // longest reply in tokens (thinking + answer), typed by the user
    val memoryThinking: Boolean = true,            // PHASE 4: use thinking when Gemma looks for personal messages (default ON)
    val memorySearch: Boolean = true,              // STEP 6: Gemma may search the saved memories in a chat (default ON)
    val heavySearch: Boolean = false,              // STEP 7: heavy search with the embedder + reranker (default OFF)
    val embedderPath: String = "",                 // STEP 7: the bge-m3 GGUF file ("" = none chosen)
    val rerankerPath: String = "",                 // STEP 7: the bge-reranker GGUF file ("" = none chosen)
    val memoryPrompt: String = ""                  // STEP 7: edited extraction prompt ("" = use the built-in one)
)

/**
 * STEP 7: the extraction prompt that is REALLY used: the one the user edited
 * in Settings, or, when none was edited (empty), the built-in prompt.
 */
fun effectiveMemoryPrompt(settings: AppSettings): String =
    settings.memoryPrompt.ifBlank { MemoryPrompts.DEFAULT_EXTRACTION_PROMPT }

// ExperimentalLayoutApi is needed for FlowRow (chips that wrap to a new line).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,                    // settings when the screen opens
    onBack: () -> Unit,                      // back arrow tapped
    onSaveAndReload: (AppSettings) -> Unit,  // "Save & Reload" tapped
    onLoadModel: (AppSettings) -> Unit,      // "Load Model" tapped (gets what is on screen)
    onManageModels: () -> Unit               // MODELS: "Manage models" tapped
) {
    // The draft: what the user is editing right now.
    var draft by remember { mutableStateOf(initial) }

    // MAX REPLY LENGTH: the text exactly as the user types it. It is kept
    // apart from the draft because "" or "5" is allowed while typing, but
    // the draft only ever receives a VALID number.
    var maxReplyText by remember { mutableStateOf(initial.maxReplyTokens.toString()) }

    // MODELS: the models that were added (the choices of the pickers). They
    // are read from the private folder once, when the screen opens.
    val context = LocalContext.current
    val modelStore = remember { ModelStore(context) }
    var storedModels by remember { mutableStateOf<List<StoredModel>>(emptyList()) }
    LaunchedEffect(Unit) { storedModels = modelStore.list() }

    // MODELS: does the chosen main model file exist? (A quick check of one
    // file name, fine to do on screen.)
    val mainExists = draft.modelPath.isNotBlank() && File(draft.modelPath).exists()

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
                // your own past.
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

                // --- STEP 7: Heavy search switch ---
                // OFF (default): only the quick keyword search above.
                // ON: Gemma can also do a DEEP search with two small search
                // models (the embedder finds the closest memories, the
                // reranker picks the best ones). It is more exact, but slower,
                // because Gemma is unloaded and loaded again for each deep
                // search. When it is ON, a card below asks for the two models.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Heavy search", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Deep memory search with two search models. More exact, " +
                                    "but slower: Gemma is loaded again for each search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = draft.heavySearch,
                        onCheckedChange = { draft = draft.copy(heavySearch = it) }
                    )
                }
            }

            // ================= STEP 7: HEAVY SEARCH MODELS =================
            // Only visible while the Heavy search switch is ON.
            // MODELS: the two models are chosen from the added models.
            if (draft.heavySearch) {
                SettingsCard(title = "Heavy search models") {
                    Text(
                        text = "Choose the two search models from the models you added. " +
                                "Heavy search stays off until both are chosen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // The embedder (bge-m3): turns texts into vectors.
                    ModelPicker(
                        label = "Embedder (bge-m3)",
                        currentPath = draft.embedderPath,
                        models = storedModels,
                        allowNone = true,
                        onPick = { draft = draft.copy(embedderPath = it) }
                    )

                    // The reranker (bge-reranker-v2-m3): picks the best matches.
                    ModelPicker(
                        label = "Reranker (bge-reranker-v2-m3)",
                        currentPath = draft.rerankerPath,
                        models = storedModels,
                        allowNone = true,
                        onPick = { draft = draft.copy(rerankerPath = it) }
                    )
                }
            }

            // ================= MODEL (MAIN MODEL) =================
            // MODELS: replaces the old "Model file" card with its path box and
            // Browse button. The main model is chosen from the added models.
            SettingsCard(title = "Model") {
                ModelPicker(
                    label = "Main model (the chat model)",
                    currentPath = draft.modelPath,
                    models = storedModels,
                    allowNone = false,
                    onPick = { draft = draft.copy(modelPath = it) }
                )
                if (storedModels.isEmpty()) {
                    Text(
                        text = "No models added yet. Tap \"Manage models\" to add one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onManageModels) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Text("  Manage models")
                }
                Text(
                    text = "Adding, renaming and deleting models is done there. " +
                            "Settings you changed here and did not save are lost when you leave.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ================= LOAD MODEL BUTTON =================
            // The button text follows the real engine status.
            // It is switched off while loading, while Gemma is answering,
            // while Gemma is naming a chat (PHASE 3), while Gemma is saving
            // memories (PHASE 4) and (MODELS) while no main model is chosen.
            Button(
                onClick = { onLoadModel(draft) },
                enabled = mainExists &&
                          engineState != EngineManager.State.LOADING &&
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
            // MODELS: say why the button is off.
            if (!mainExists) {
                Text(
                    text = "Choose a main model above to load it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
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
                // PROMPTS: folded to 6 lines; a tap opens the whole prompt.
                ExpandablePromptField(
                    value = draft.systemPrompt,
                    onValueChange = {
                        // Accept the new text only while it is short enough.
                        if (it.length <= MAX_PROMPT_CHARS) draft = draft.copy(systemPrompt = it)
                    },
                    label = "Prompt",
                    hint = "Sent at the start of the context window.  " +
                            "${draft.systemPrompt.length} / $MAX_PROMPT_CHARS"
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

            // ================= STEP 7: MEMORY PROMPT EDITOR =================
            // The prompt that Gemma gets when it looks for the PERSONAL
            // messages of a chat and writes their tags. The user can edit it
            // here for trial and error, without rebuilding the app.
            //   - Empty in the draft = the built-in prompt is used. The box
            //     then SHOWS the built-in text, so it can be edited from there.
            //   - "Reset to default" empties the draft's prompt again.
            // The reply Gemma writes must keep the shape
            //   {"personal": [{"n": 2, "tags": ["a", "b", "c", "d"]}]}
            // because the app reads exactly that.
            SettingsCard(title = "Memory Prompt Editor") {
                // PROMPTS: folded to 6 lines; a tap opens the whole prompt.
                ExpandablePromptField(
                    value = effectiveMemoryPrompt(draft),
                    onValueChange = {
                        if (it.length <= MAX_MEMORY_PROMPT_CHARS) draft = draft.copy(memoryPrompt = it)
                    },
                    label = "Prompt for finding personal messages and tags",
                    hint = (if (draft.memoryPrompt.isBlank()) "Built-in prompt in use.  "
                            else "Your edited prompt in use.  ") +
                            "${effectiveMemoryPrompt(draft).length} / $MAX_MEMORY_PROMPT_CHARS"
                )
                Text(
                    text = "Keep the reply shape: {\"personal\": [{\"n\": 2, \"tags\": [\"a\", \"b\"]}]}. " +
                            "The app reads exactly that. Press Save & Reload to use your changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Back to the built-in prompt.
                TextButton(
                    onClick = { draft = draft.copy(memoryPrompt = "") },
                    enabled = draft.memoryPrompt.isNotBlank()
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

// ---------------------------------------------------------------------
// MODELS HELPER: chooses ONE model from the models that were added.
// It looks like a button with the chosen model's name and a small arrow;
// tapping it opens a list.
//   label         what the choice is called ("Main model", "Embedder"...)
//   currentPath   the path now ("" = none chosen)
//   models        the added models (the choices)
//   allowNone     true = the list also has "None" (for the two search models;
//                 the main model always needs a choice)
//   onPick        the user chose a model (its path), or "" for None
// Under the button a short red text says when nothing is chosen or when the
// chosen file is gone (for example it was deleted, or it is the old path from
// the version that needed "All files access").
// It draws several things one after another; it is meant to be used inside
// a SettingsCard (a Column), which spaces them.
// ---------------------------------------------------------------------
@Composable
private fun ModelPicker(
    label: String,
    currentPath: String,
    models: List<StoredModel>,
    allowNone: Boolean,
    onPick: (String) -> Unit
) {
    // Is the list open?
    var open by remember { mutableStateOf(false) }

    val current = models.firstOrNull { it.path == currentPath }
    val exists = currentPath.isNotBlank() && File(currentPath).exists()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        // The Box is the anchor the list opens at.
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        current != null -> current.displayName
                        currentPath.isBlank() -> "Not chosen"
                        exists -> File(currentPath).name
                        else -> "Missing: " + File(currentPath).name
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false }
            ) {
                if (allowNone) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            open = false
                            onPick("")
                        }
                    )
                }
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.displayName + "  (" + ModelStore.formatSize(model.sizeBytes) + ")") },
                        onClick = {
                            open = false
                            onPick(model.path)
                        }
                    )
                }
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No models added yet") },
                        enabled = false,
                        onClick = { }
                    )
                }
            }
        }

        // A problem text under the button (nothing when all is fine).
        val problem = when {
            currentPath.isBlank() -> if (allowNone) "" else "Not chosen yet."
            !exists -> "This file was not found. Choose a model from the list."
            else -> ""
        }
        if (problem.isNotEmpty()) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// ---------------------------------------------------------------------
// PROMPTS HELPER: a text box for a LONG prompt.
//   - Folded: it shows only COLLAPSED_PROMPT_LINES (6) lines. A longer text
//     scrolls inside the box, so the Settings page stays short.
//   - A tap on the box gives it the focus, which OPENS it: the whole prompt
//     is shown (and the user can edit it).
//   - "Show less" folds it again and removes the focus (the keyboard closes).
//   value / onValueChange  the text and its changes (the caller limits the length)
//   label                  the small title of the box
//   hint                   the line under the box (for example the character count)
// ---------------------------------------------------------------------
@Composable
private fun ExpandablePromptField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String
) {
    // Is the box open (whole text) or folded (6 lines)?
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            // The first tap puts the cursor in the box (focus): open it.
            .onFocusChanged { if (it.isFocused) expanded = true },
        label = { Text(label) },
        minLines = 3,
        maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_PROMPT_LINES,
        supportingText = { Text(hint) }
    )
    if (expanded) {
        TextButton(
            onClick = {
                expanded = false
                focusManager.clearFocus()
            }
        ) {
            Text("Show less")
        }
    }
}
