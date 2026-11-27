// =====================================================================
// ModelsScreen.kt  (app/src/main/java/com/haven/chat/ui/models/)   NEW
// WHAT: The "Model management" screen. It shows the models that are in the
//       app's private folder and lets the user manage them:
//         top bar:  [back]  Model management
//         card:     what this screen is, the free space, [Add model]
//         cards:    one card per model: name, size, ROLE badges, and a "..."
//                   menu (tap the card or the dots):
//                      Use as main model     (the chat model, loaded at once)
//                      Use as embedder       (heavy search, bge-m3)
//                      Use as reranker       (heavy search, bge-reranker)
//                      Rename
//                      Delete
//         dialogs:  copy progress (with Cancel), rename, delete, messages
//
// THE ROLES (see ModelsViewModel): a model is not "a chat model" or "a search
//   model" by its file. The user chooses. The same three choices exist in the
//   Settings screen (they are the same three settings).
//
// ADD MODEL: the button opens Android's file picker. The picked file is
//   COPIED into the app's private folder (ModelsViewModel.importModel). While
//   it copies, a dialog shows the progress. The dialog cannot be closed by
//   tapping outside or pressing Back, only by Cancel, so the user cannot
//   leave the screen in the middle of a copy by accident.
//   The original file stays where it was: the user can delete it later in
//   the phone's file manager to get the space back.
//
// DESIGN NOTE: unlike PrivacyScreen, this screen gets the ViewModel itself
//   (not a long list of callbacks). It only READS its state and calls its
//   actions. This keeps the file short; there is no data logic here.
//
// USED BY: HavenApp.kt (the page "models").
// =====================================================================
package com.haven.chat.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ModelStore
import com.haven.chat.data.StoredModel
import com.haven.chat.engine.EngineManager

// The longest name the rename box accepts (the store cuts at the same length).
private const val MAX_RENAME_CHARS = 80

// ExperimentalMaterial3Api is needed for TopAppBar (same as SettingsScreen).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    vm: ModelsViewModel,        // the data and the actions
    onBack: () -> Unit          // back arrow tapped
) {
    // Which dialog is open. null = none. They live only in this screen.
    var renameTarget by remember { mutableStateOf<StoredModel?>(null) }
    var deleteTarget by remember { mutableStateOf<StoredModel?>(null) }

    // Android's file picker. "*/*" = show all file types, because Android
    // does not know the .gguf type. The lambda gets the picked file's address
    // (or null if the user cancelled) and hands it to the ViewModel, which
    // copies the file right away (so no long-lasting permission is needed).
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importModel(uri)
    }

    val importing = vm.importUi != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Model management", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !importing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---------- INFO CARD + ADD BUTTON ----------
            item {
                InfoCard(
                    freeSpace = vm.freeSpaceText,
                    importing = importing,
                    onAdd = { picker.launch(arrayOf("*/*")) }
                )
            }

            // ---------- EMPTY TEXT (only when loaded and really empty) ----------
            if (vm.listLoaded && vm.models.isEmpty()) {
                item {
                    Text(
                        text = "No models yet.\n\n" +
                                "Tap \"Add model\" and pick a .gguf file, for example your " +
                                "Gemma model. Haven Chat copies it into its own private " +
                                "space and loads it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ---------- ONE CARD PER MODEL ----------
            // "key" gives each model a stable identity (its file name).
            items(vm.models, key = { it.fileName }) { model ->
                val isMain = vm.isMain(model)
                val isEmbedder = vm.isEmbedder(model)
                val isReranker = vm.isReranker(model)
                ModelCard(
                    model = model,
                    isMain = isMain,
                    isEmbedder = isEmbedder,
                    isReranker = isReranker,
                    onUseMain = { vm.setMain(model) },
                    onToggleEmbedder = { vm.setEmbedder(if (isEmbedder) null else model) },
                    onToggleReranker = { vm.setReranker(if (isReranker) null else model) },
                    onRename = { renameTarget = model },
                    onDelete = { deleteTarget = model }
                )
            }
        }
    }

    // ---------- DIALOGS ----------
    // A dialog opens in its own window, so it is fine to write them here,
    // after the screen. Each shows only while its "target" is set.

    // COPY PROGRESS (not closable except by Cancel).
    vm.importUi?.let { progress ->
        AlertDialog(
            onDismissRequest = { /* not closable: only Cancel stops the copy */ },
            title = { Text("Adding model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (progress.total > 0) {
                        Text(
                            "Copied ${ModelStore.formatSize(progress.copied)} of " +
                                    ModelStore.formatSize(progress.total) + "."
                        )
                        LinearProgressIndicator(
                            progress = {
                                (progress.copied.toFloat() / progress.total.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // The size is not known: show only what is copied so far.
                        Text("Copied ${ModelStore.formatSize(progress.copied)}.")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        text = "A big model can take a few minutes. Stay on this screen: " +
                                "leaving it cancels the copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { /* nothing: only Cancel is offered while it copies */ },
            dismissButton = {
                TextButton(onClick = { vm.cancelImport() }) { Text("Cancel") }
            }
        )
    }

    // RENAME
    renameTarget?.let { model ->
        RenameDialog(
            oldName = model.displayName,
            isMainLoaded = vm.isMain(model),
            onConfirm = { newName ->
                vm.rename(model, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // DELETE: ask first (it cannot be undone).
    deleteTarget?.let { model ->
        val extra = buildString {
            if (vm.isMain(model)) append("\nIt is the main model, so the chat will say \"Not loaded\" until you choose another one.")
            if (vm.isEmbedder(model)) append("\nIt is the embedder: heavy search stops working until you choose another one.")
            if (vm.isReranker(model)) append("\nIt is the reranker: heavy search will use the closest memories without the second check.")
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this model?") },
            text = {
                Text(
                    "\"${model.displayName}\" (${ModelStore.formatSize(model.sizeBytes)}) " +
                            "will be deleted from the app. This cannot be undone. " +
                            "You can add it again from your files." + extra
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(model)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // MESSAGE: a problem or a notice from the ViewModel.
    vm.message?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.dismissMessage() },
            title = { Text("Model management") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { vm.dismissMessage() }) { Text("OK") }
            }
        )
    }
}

// ---------------------------------------------------------------------
// The card at the top: what this screen is, the free space, Add model.
// ---------------------------------------------------------------------
@Composable
private fun InfoCard(
    freeSpace: String,
    importing: Boolean,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Your models", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Models are kept in the app's private space. Adding a model COPIES it, " +
                    "so it uses extra space on the phone until you delete the original " +
                    "file in your file manager. Pick which model runs from the \"...\" " +
                    "menu of a card.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Free space on the phone: $freeSpace",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Button(onClick = onAdd, enabled = !importing) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Add model")
        }
    }
}

// ---------------------------------------------------------------------
// ONE model card: name, size, role badges, and the "..." menu. Tapping the
// card opens the same menu.
// ---------------------------------------------------------------------
@Composable
private fun ModelCard(
    model: StoredModel,
    isMain: Boolean,
    isEmbedder: Boolean,
    isReranker: Boolean,
    onUseMain: () -> Unit,
    onToggleEmbedder: () -> Unit,
    onToggleReranker: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    // Is the menu open? (Each card remembers its own.)
    var menuOpen by remember { mutableStateOf(false) }

    // The main model can be loaded, loading, or not loaded (the engine state
    // is Compose state, so this redraws by itself).
    val engineState = EngineManager.state
    val mainNeedsLoading = engineState == EngineManager.State.NOT_LOADED ||
            engineState == EngineManager.State.ERROR

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clip first, so the tap ripple has rounded corners
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { menuOpen = true }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = ModelStore.formatSize(model.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Role badges. A model with no role shows "Not in use".
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isMain) RoleBadge("Main \u00B7 " + EngineManager.statusText())
                if (isEmbedder) RoleBadge("Embedder")
                if (isReranker) RoleBadge("Reranker")
                if (!isMain && !isEmbedder && !isReranker) {
                    Text(
                        text = "Not in use",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // "..." button with its menu. The Box is the anchor the menu opens at.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Model options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                // The main model: choose it (and load it), or load it again
                // when it is the main model but not in RAM.
                DropdownMenuItem(
                    text = { Text(if (isMain) "Load main model" else "Use as main model") },
                    enabled = !isMain || mainNeedsLoading,
                    onClick = {
                        menuOpen = false
                        onUseMain()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isEmbedder) "Remove embedder role" else "Use as embedder (search)") },
                    onClick = {
                        menuOpen = false
                        onToggleEmbedder()
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (isReranker) "Remove reranker role" else "Use as reranker (search)") },
                    onClick = {
                        menuOpen = false
                        onToggleReranker()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// A small rounded label, for example "Main \u00B7 Ready" or "Embedder".
// ---------------------------------------------------------------------
@Composable
private fun RoleBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

// ---------------------------------------------------------------------
// The rename dialog: a text box with the old name, [Cancel] [Save].
// ".gguf" is added by the app, so it is not shown here.
// ---------------------------------------------------------------------
@Composable
private fun RenameDialog(
    oldName: String,
    isMainLoaded: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // The text being edited. It starts with the old name.
    var text by remember { mutableStateOf(oldName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename model") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_RENAME_CHARS) text = it },
                singleLine = true,
                supportingText = {
                    Text(
                        (if (isMainLoaded) "The main model is loaded again after renaming.  " else "") +
                                "${text.length} / $MAX_RENAME_CHARS"
                    )
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()                // an empty name is not allowed
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
