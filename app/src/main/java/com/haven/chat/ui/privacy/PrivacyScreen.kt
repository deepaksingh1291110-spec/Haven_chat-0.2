// =====================================================================
// PrivacyScreen.kt  (app/src/main/java/com/haven/chat/ui/privacy/)   NEW in Phase 4
// WHAT: The "Privacy & local data" screen. It shows the memories that
//       Gemma saved about the user (the "fair notebook") and lets the
//       user manage them:
//         top bar:  [back]  Privacy & local data        [Delete all]
//         card:     what this screen is, and how many memories there are
//         cards:    one card per memory: date, the user's question, the
//                   TAGS Gemma wrote for it, Gemma's final answer, and a
//                   delete (trash) icon
//         tap a card -> a dialog to EDIT the question, the answer and the tags
//         trash icon -> asks "Delete this memory?" first
//         Delete all -> asks "Delete all N memories?" first
// DESIGN RULE: this screen is "stateless", like ChatScreen. It does NOT
//   own the data. It only DRAWS the list it is given and REPORTS taps
//   upward (onUpdateMemory, onDeleteMemory, ...). PrivacyViewModel owns the
//   data. The only state kept here is "which dialog is open right now".
//
// WHAT IS SHOWN FOR A MEMORY: the question and the answer are the texts
//   that were copied from the chat, word for word. The date is the day the
//   user WROTE the question (not the day it was saved).
//
// STEP 7 CHANGES (marked "STEP 7"):
//   - TAGS. Every memory card shows its tags (the 4 to 6 search keywords
//     Gemma wrote), in the accent color, between the question and the
//     answer. A memory without tags (saved before step 7, or Gemma gave
//     none) says "No tags" in gray. This is how the user checks and tunes
//     the memory prompt: run "remember my personal things", then look here.
//   - The edit dialog has a third box, "Tags", with the tags as text
//     separated by commas. Editing the question, the answer or the tags
//     makes the search vector of that memory old, so it is made again later.
//   - onUpdateMemory now gives (id, question, answer, tags).
//
// THE LIST CAN BE NULL: null = still loading (nothing is drawn yet),
//   an empty list = loaded, no memories (a short help text is drawn).
//
// WHAT THE USER MAY DO HERE (and what stays untouched):
//   - editing, deleting one memory, deleting all memories only change the
//     memories (memories.db). Chats are never touched.
//   - a memory that was deleted here is NOT made again from the same chat
//     (the chat's marker stays, see MemoryDao.kt).
//
// USED BY: HavenApp.kt (the page "privacy").
// =====================================================================
package com.haven.chat.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.haven.chat.data.MemoryEntity
import com.haven.chat.data.tagsFromText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// How many lines a card shows before it cuts the text with "...".
// (The full text is always visible in the edit dialog.)
private const val QUESTION_PREVIEW_LINES = 3
private const val ANSWER_PREVIEW_LINES = 4
private const val TAGS_PREVIEW_LINES = 2

// The date format on a card, for example "19 Nov 2026".
private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

// Turns "milliseconds since 1970" into text, in the phone's time zone.
private fun dateText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)

// ExperimentalMaterial3Api is needed for TopAppBar (same as SettingsScreen).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    memories: List<MemoryEntity>?,                         // null = still loading
    problem: String?,                                      // a plain-words problem, or null
    onBack: () -> Unit,                                    // back arrow tapped
    onUpdateMemory: (Long, String, String, String) -> Unit, // id, new question, new answer, new tags (STEP 7)
    onDeleteMemory: (Long) -> Unit,                        // id (after the user confirmed)
    onDeleteAll: () -> Unit,                               // after the user confirmed
    onClearProblem: () -> Unit                             // the problem dialog was closed
) {
    // Which dialog is open. null / false = none. They live only in this
    // screen (remember), they are not data.
    var editTarget by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MemoryEntity?>(null) }
    var deleteAllOpen by remember { mutableStateOf(false) }

    val count = memories?.size ?: 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Privacy & local data", style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only useful when there is something to delete.
                    TextButton(
                        onClick = { deleteAllOpen = true },
                        enabled = count > 0
                    ) {
                        Text(
                            text = "Delete all",
                            color = if (count > 0) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

        // LazyColumn = a scrolling list that only draws what is on screen.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---------- INFO CARD ----------
            item {
                InfoCard(count = count, loaded = memories != null)
            }

            // ---------- EMPTY TEXT (only when loaded and really empty) ----------
            if (memories != null && memories.isEmpty()) {
                item {
                    Text(
                        text = "No memories yet.\n\n" +
                                "In a chat, tap the bookmark icon at the top, or type " +
                                "\"remember my personal things from this chat\". " +
                                "Gemma then saves your personal messages here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // ---------- ONE CARD PER MEMORY ----------
            // "key" gives each memory a stable identity (its id), so the list
            // does not jump when one is edited or deleted.
            if (memories != null) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        onClick = { editTarget = memory },
                        onDeleteClick = { deleteTarget = memory }
                    )
                }
            }
        }
    }

    // ---------- DIALOGS ----------
    // A dialog opens in its own window, so it is fine to write them here,
    // after the screen. Each shows only while its "target" is set.

    // EDIT dialog
    editTarget?.let { memory ->
        EditDialog(
            memory = memory,
            onSave = { question, answer, tags ->
                onUpdateMemory(memory.id, question, answer, tags)
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    // DELETE ONE: ask first (it cannot be undone).
    deleteTarget?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this memory?") },
            text = {
                Text(
                    "\"${memory.question.take(120)}" +
                            (if (memory.question.length > 120) "..." else "") +
                            "\" will be deleted from this phone. This cannot be undone. " +
                            "The chat it came from is not touched."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMemory(memory.id)
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

    // DELETE ALL: ask first.
    if (deleteAllOpen) {
        AlertDialog(
            onDismissRequest = { deleteAllOpen = false },
            title = { Text("Delete all memories?") },
            text = {
                Text(
                    "All $count memories will be deleted from this phone. " +
                            "This cannot be undone. Your chats are not deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAll()
                        deleteAllOpen = false
                    }
                ) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAllOpen = false }) { Text("Cancel") }
            }
        )
    }

    // PROBLEM dialog: shown when the database refused a change.
    if (problem != null) {
        AlertDialog(
            onDismissRequest = onClearProblem,
            title = { Text("Problem") },
            text = { Text(problem) },
            confirmButton = {
                TextButton(onClick = onClearProblem) { Text("OK") }
            }
        )
    }
}

// ---------------------------------------------------------------------
// The card at the top: what this screen is, and how many memories.
// ---------------------------------------------------------------------
@Composable
private fun InfoCard(count: Int, loaded: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Saved memories", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Only your personal things are saved here: your question and " +
                    "Gemma's final answer, word for word. They stay on this phone, " +
                    "and they stay even when a chat is deleted. " +
                    "Each memory also has tags: short search keywords that Gemma wrote. " +
                    "Tap a memory to edit it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (loaded) {
            Text(
                text = if (count == 1) "1 memory" else "$count memories",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------------------------------------------------------------------
// ONE memory card: date + trash icon on top, then the question, then the
// tags (STEP 7), then the answer (cut after a few lines). Tapping the card
// opens the edit dialog.
// ---------------------------------------------------------------------
@Composable
private fun MemoryCard(
    memory: MemoryEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // STEP 7: the tags as a list ("a, b, c" -> [a, b, c]).
    val tags = tagsFromText(memory.tags)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // clip first, so the tap ripple has rounded corners
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dateText(memory.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Delete this memory",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // The right margin of the texts is 12 dp (the card keeps only 4 dp
        // on the right so the trash icon sits close to the edge).
        Column(
            modifier = Modifier.padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = memory.question,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = QUESTION_PREVIEW_LINES,
                overflow = TextOverflow.Ellipsis
            )

            // STEP 7: the tags. Accent color when there are some, gray
            // "No tags" when there are none.
            if (tags.isEmpty()) {
                Text(
                    text = "No tags",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = tags.joinToString("  \u00B7  "),       // tag  ·  tag  ·  tag
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = TAGS_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val answer = memory.answer
            if (answer.isNullOrBlank()) {
                Text(
                    text = "No saved answer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = ANSWER_PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// The edit dialog: three text boxes (question, tags, answer) and
// [Cancel] [Save]. The texts start with the saved values. Save is allowed
// only when the question is not empty. An empty answer is allowed (it means
// "no answer"), and so are empty tags. The content scrolls, because an
// answer can be long.
// ---------------------------------------------------------------------
@Composable
private fun EditDialog(
    memory: MemoryEntity,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // The texts being edited. "remember(memory.id)" makes them start again
    // from the saved values if a different memory is opened.
    var question by remember(memory.id) { mutableStateOf(memory.question) }
    var tags by remember(memory.id) { mutableStateOf(memory.tags) }
    var answer by remember(memory.id) { mutableStateOf(memory.answer ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit memory") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Your question") },
                    isError = question.isBlank(),
                    minLines = 2,
                    maxLines = 8
                )
                // STEP 7: the tags, as text separated by commas.
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tags (separated by commas)") },
                    supportingText = { Text("4 to 6 short words that would find this memory.") },
                    minLines = 1,
                    maxLines = 4
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gemma's answer") },
                    minLines = 3,
                    maxLines = 10
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(question, answer, tags) },
                enabled = question.isNotBlank()          // an empty question is not allowed
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
