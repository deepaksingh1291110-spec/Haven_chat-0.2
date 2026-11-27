// =====================================================================
// DrawerContent.kt  (app/src/main/java/com/haven/chat/ui/drawer/)
// WHAT: The content of the side menu (drawer) from your design:
//         [New chat] [Search........]  [X]
//         Today
//           . Chat title                          ...
//         Hardware Status card (CPU, Instruction Set, Threading)
//         Preferences: Settings, Model management, Privacy, Storage, About
// HOW:  It draws only the INSIDE of the menu. The sliding animation and
//       the dark overlay come from ModalNavigationDrawer (in HavenApp).
// STATELESS: it gets data in, and reports taps out (callbacks).
//
// PHASE 3 CHANGES (marked "PHASE 3"):
//   - The chat list is now REAL (from the database, through ChatViewModel).
//   - Search: the text is handed UP (onQueryChange). The database does the
//     searching (titles AND message texts), so the list we get is already
//     filtered. The old filter inside this file is gone.
//   - Tapping a chat opens it (onChatClick). The open chat is highlighted.
//   - The "..." on every chat is now a real menu: Rename and Delete.
//       Rename -> small dialog with a text box.
//       Delete -> asks "Are you sure?" first (it cannot be undone).
//     Delete is switched off while Gemma is busy.
//   - UI FIX: the search box is now exactly as tall as the "New chat"
//     button (48 dp, same rounded shape). Material's TextField has a fixed
//     minimum height of 56 dp that cannot be made smaller, so the search
//     box is built by hand with BasicTextField.
//   - Texts for an empty list: "No chats yet" and "No chats found".
//   - A small hint is shown while Gemma is busy (switching or deleting
//     chats waits until it is done).
//   - HARDWARE CARD IS NOW REAL (step 8): it shows the chip name, the CPU
//     features that speed up Gemma (NEON, DOTPROD, I8MM...) and how many
//     of the phone's cores are used. The facts come from HardwareInfo.kt.
//     No new parameters are needed: the card reads them once by itself.
//
// PHASE 4 CHANGES (marked "PHASE 4"):
//   Step 4 - DELETE ASKS ABOUT MEMORIES FIRST.
//   The delete dialog now has THREE buttons:
//     1) "Save personal things, then delete"  -> onSaveAndDeleteChat(id)
//        Gemma first finds the personal things in the chat and saves them
//        as memories; the chat is deleted only when that worked.
//     2) "Delete without saving"              -> onDeleteChat(id)
//        The old behaviour: the chat and its messages are deleted at once.
//     3) "Cancel"                             -> nothing happens.
//   Memories that were saved before stay in every case: deleting a chat
//   never deletes its memories.
//   Step 5 - PRIVACY ROW WORKS.
//   The row "Privacy & local data" (it did nothing before) now calls
//   onPrivacyClick. HavenApp closes the drawer and opens the privacy
//   screen, where the saved memories can be seen, edited and deleted.
//   Both new callbacks have a default value ({}), so this file compiles
//   even before HavenApp.kt passes them.
//
// PRIVATE MODELS (marked "MODELS"):
//   The row "Model management" (it did nothing before) now calls
//   onModelsClick. HavenApp closes the drawer and opens the model
//   management screen, where the user adds, renames, deletes and chooses
//   models. The new callback has a default value ({}), like the others.
//   Nothing else in this file changed.
// =====================================================================
package com.haven.chat.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatSummary
import com.haven.chat.engine.HardwareInfo
import com.haven.chat.ui.theme.DarkDrawerBackground

// PHASE 3: one height for the "New chat" button AND the search box.
private val TopRowHeight = 48.dp

// PHASE 3: longest title the rename box accepts (the repository cuts at the
// same length).
private const val MAX_RENAME_CHARS = 60

@Composable
fun DrawerContent(
    chats: List<ChatSummary>,            // chats to list (already filtered by the search)
    currentChatId: Long?,                // PHASE 3: the open chat (highlighted), or null
    query: String,                       // PHASE 3: text in the search box
    onQueryChange: (String) -> Unit,     // PHASE 3: the user typed in the search box
    engineBusy: Boolean,                 // PHASE 3: Gemma is loading / answering / naming
    threads: Int,                        // CPU threads shown in Hardware Status
    onNewChatClick: () -> Unit,
    onChatClick: (Long) -> Unit,         // gives the id of the tapped chat
    onRenameChat: (Long, String) -> Unit, // PHASE 3: chat id and the new title
    onDeleteChat: (Long) -> Unit,        // PHASE 3: chat id (after the user chose "Delete without saving")
    onSaveAndDeleteChat: (Long) -> Unit = {}, // PHASE 4: chat id (after the user chose "Save personal things, then delete")
    onSettingsClick: () -> Unit,
    onPrivacyClick: () -> Unit = {},     // PHASE 4: the "Privacy & local data" row was tapped
    onModelsClick: () -> Unit = {},      // MODELS: the "Model management" row was tapped
    onCloseClick: () -> Unit
) {
    // Group by section name ("Today", "Yesterday"...). Result: Map<name, list>.
    // The list is sorted newest first, so the sections come in the right order.
    val grouped = chats.groupBy { it.group }

    // PHASE 3: which chat is being renamed / deleted right now (null = none).
    // These decide whether a dialog is shown.
    var renameTarget by remember { mutableStateOf<ChatSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSummary?>(null) }

    // Your design uses pure black in dark mode; light mode uses the theme color.
    val drawerColor =
        if (isSystemInDarkTheme()) DarkDrawerBackground
        else MaterialTheme.colorScheme.background

    // LazyColumn = a scrolling list. The WHOLE drawer scrolls, so it
    // still fits on small phones.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(drawerColor)
            .statusBarsPadding()                       // stay below the clock/battery bar
            .padding(horizontal = 16.dp)
    ) {
        // ---------- TOP ROW: New chat, Search, Close ----------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The button gets the shared height.
                Button(
                    onClick = onNewChatClick,
                    modifier = Modifier.height(TopRowHeight),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF2F2F2),
                        contentColor = Color(0xFF1C1B1F)
                    )
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = " New chat", style = MaterialTheme.typography.labelLarge)
                }

                // PHASE 3: the search box, built by hand so it can be exactly
                // as tall as the button (see the notes at the top).
                SearchBox(
                    query = query,
                    onQueryChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)                    // take the free width
                        .padding(horizontal = 8.dp)
                        .height(TopRowHeight)
                )

                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Filled.Close, contentDescription = "Close menu")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // PHASE 3: hint while the engine is busy.
            if (engineBusy) {
                Text(
                    text = "Gemma is busy. Opening, switching and deleting chats works again when it is done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }

        // ---------- PHASE 3: EMPTY LIST TEXT ----------
        if (chats.isEmpty()) {
            item {
                Text(
                    text = if (query.isBlank()) "No chats yet" else "No chats found",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        }

        // ---------- CHAT HISTORY, grouped by section ----------
        grouped.forEach { (groupName, groupChats) ->
            // Section title ("Today").
            item {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )
            }
            // One row per chat in the section.
            items(groupChats, key = { it.id }) { chat ->
                ChatRow(
                    chat = chat,
                    selected = chat.id == currentChatId,
                    engineBusy = engineBusy,
                    onClick = { onChatClick(chat.id) },
                    onRenameClick = { renameTarget = chat },
                    onDeleteClick = { deleteTarget = chat }
                )
            }
        }

        // ---------- HARDWARE STATUS CARD ----------
        item {
            Spacer(Modifier.height(24.dp))
            HardwareCard(threads = threads)
        }

        // ---------- PREFERENCES ----------
        item {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            PreferenceRow(Icons.Outlined.Settings, "Settings", onSettingsClick)
            // MODELS: this row works now (it opens the model management screen).
            PreferenceRow(Icons.Outlined.Memory, "Model management", onModelsClick)
            // PHASE 4: this row works now (it opens the memories screen).
            PreferenceRow(Icons.Outlined.Shield, "Privacy & local data", onPrivacyClick)
            PreferenceRow(Icons.Outlined.Storage, "Storage & cache") { /* TODO later */ }
            PreferenceRow(Icons.Outlined.Info, "About Haven") { /* TODO later */ }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------- PHASE 3: DIALOGS ----------
    // A dialog opens in its own window, so it is fine to write it here,
    // after the list. It shows only while its "target" is not null.

    // RENAME dialog
    renameTarget?.let { chat ->
        RenameDialog(
            oldTitle = chat.title,
            onConfirm = { newTitle ->
                onRenameChat(chat.id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // DELETE dialog
    // PHASE 4: three choices instead of two. Material's AlertDialog has only
    // two button slots, so the three buttons are stacked in the "confirm"
    // slot, one under the other, each on its own line (the labels are long).
    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this chat?") },
            text = {
                Text(
                    "\"${chat.title}\" and all its messages will be deleted from " +
                            "this phone. This cannot be undone.\n\n" +
                            "Gemma can first save the personal things from this chat " +
                            "as memories. Saved memories stay even after the chat is deleted."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    // 1) Save the personal things first, then delete.
                    TextButton(
                        onClick = {
                            onSaveAndDeleteChat(chat.id)
                            deleteTarget = null
                        }
                    ) {
                        Text("Save personal things, then delete")
                    }
                    // 2) The old behaviour: delete at once.
                    TextButton(
                        onClick = {
                            onDeleteChat(chat.id)
                            deleteTarget = null
                        }
                    ) {
                        Text("Delete without saving", color = MaterialTheme.colorScheme.error)
                    }
                    // 3) Do nothing.
                    TextButton(onClick = { deleteTarget = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------------
// PHASE 3: the search box. Looks like before (rounded, dark, magnifier
// icon) but its height is whatever the caller gives it (48 dp).
// BasicTextField = a plain text input with NO built-in look; we draw the
// look ourselves in "decorationBox".
// ---------------------------------------------------------------------
@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        // The blinking cursor gets the theme's main color.
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // The hint text: one line only, so it never wraps.
                    if (query.isEmpty()) {
                        Text(
                            text = "Search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()   // the real typed text goes here
                }
            }
        }
    )
}

// ---------------------------------------------------------------------
// ONE chat row: small colored dot, title, "..." menu (Rename, Delete).
// ---------------------------------------------------------------------
@Composable
private fun ChatRow(
    chat: ChatSummary,
    selected: Boolean,            // PHASE 3: this is the open chat
    engineBusy: Boolean,          // PHASE 3: Delete is switched off while busy
    onClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // Is the small "..." menu open? (Each row remembers its own.)
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // The open chat gets a soft background.
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The dot: a small circle.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = chat.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,                                  // long titles get "..."
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )

        // "..." button with its menu. The Box is the anchor the menu opens at.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "Chat options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRenameClick()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Delete",
                            color = if (engineBusy) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = if (engineBusy) MaterialTheme.colorScheme.onSurfaceVariant
                                   else MaterialTheme.colorScheme.error
                        )
                    },
                    enabled = !engineBusy,                 // not while Gemma is busy
                    onClick = {
                        menuOpen = false
                        onDeleteClick()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// PHASE 3: the rename dialog: a text box with the old title, [Cancel] [Save].
// ---------------------------------------------------------------------
@Composable
private fun RenameDialog(
    oldTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // The text being edited. It starts with the old title.
    var text by remember { mutableStateOf(oldTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_RENAME_CHARS) text = it },
                singleLine = true,
                supportingText = { Text("${text.length} / $MAX_RENAME_CHARS") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()                // an empty title is not allowed
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ---------------------------------------------------------------------
// The rounded card with 3 lines of hardware info.
// PHASE 3 (step 8): the values are REAL now.
//   CPU             the chip name of this phone
//   Instruction Set the CPU features that make Gemma faster
//   Threading       "threads in use of cores available", e.g. "4 of 8 cores"
// ---------------------------------------------------------------------
@Composable
private fun HardwareCard(threads: Int) {
    // Read the phone's facts ONCE and keep them while the drawer exists.
    // (HardwareInfo.read() takes a few milliseconds and never throws.)
    val hardware = remember { HardwareInfo.read() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Hardware Status", style = MaterialTheme.typography.titleMedium)
        HardwareLine("CPU", hardware.cpuName)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HardwareLine("Instruction Set", hardware.features)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HardwareLine("Threading", "$threads of ${hardware.cores} cores")
    }
}

// One "label ........ value" line inside the hardware card.
// PHASE 3: the value may be long (for example "NEON, DOTPROD, I8MM, BF16"),
// so it takes only the room that is left and is right-aligned. If it is
// still too long it moves to a second line instead of pushing the label.
@Composable
private fun HardwareLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween   // label left, value right
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f, fill = false)      // use only the space that is left
                .padding(start = 16.dp)
        )
    }
}

// One preference row: icon in a rounded square, then the title.
@Composable
private fun PreferenceRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        )
    }
}
