// =====================================================================
// PrivacyViewModel.kt  (app/src/main/java/com/haven/chat/ui/privacy/)   NEW in Phase 4
// WHAT: The "brain" of the "Privacy & local data" screen. It holds the
//       saved memories (the "fair notebook") for the screen to draw, and
//       does what the user asks there:
//         - show the list of memories (live: it updates by itself),
//         - edit one memory (question, answer and, STEP 7, its tags),
//         - delete one memory,
//         - delete ALL memories.
//       PrivacyScreen.kt only DRAWS what this class holds and reports taps
//       upward, like ChatScreen / ChatViewModel.
//
// WHY A VIEWMODEL: so the list and a half-finished edit survive a screen
//   rotation, and so the screen itself stays simple.
//
// WHERE THE DATA COMES FROM: MemoryRepository -> MemoryDao -> memories.db.
//   The list is a LIVE stream (Flow): after every edit or delete a new list
//   arrives by itself, so this class never has to refresh anything.
//
// "memories" IS NULL WHILE LOADING: the first list needs a few
//   milliseconds. null means "not loaded yet" (the screen draws nothing),
//   an EMPTY list means "loaded, there are no memories". This avoids a
//   flash of "No memories yet" every time the screen opens.
//
// NOTHING HERE TOUCHES CHATS OR GEMMA. Deleting a memory here never
//   touches a chat, and it never changes a "last-done marker": a memory the
//   user deleted is NOT made again from the same chat (see MemoryDao.kt).
//
// NEVER CRASHES ON A DATABASE PROBLEM: if the database refuses a change
//   (for example the phone storage is full), "problem" gets a plain-words
//   text and the screen shows it. The list is simply unchanged.
//
// STEP 7 CHANGE (marked "STEP 7"):
//   updateMemory() has a version with the TAGS (as comma-separated text,
//   for example "brother, rahul, kotlin"). The repository cleans them and
//   also drops the memory's search vector (it no longer matches the new
//   text; it is made again later). The older version without tags is kept:
//   the memory keeps the tags it has.
// =====================================================================
package com.haven.chat.ui.privacy

import android.app.Application
import android.database.sqlite.SQLiteException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haven.chat.data.MemoryEntity
import com.haven.chat.data.MemoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrivacyViewModel(application: Application) : AndroidViewModel(application) {

    // The one place that saves and loads memories.
    private val repo = MemoryRepository(application)

    // The saved memories, newest first (by the date the user wrote the
    // question), as a LIVE value. null = still loading (see the top).
    // stateIn = turns the stream into a value the screen can read; it stays
    // alive for 5 s after the screen stops looking (for example a rotation).
    val memories: StateFlow<List<MemoryEntity>?> = repo.observeMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // A plain-words problem text, or null when everything is fine.
    // The screen shows it in a small dialog; clearProblem() closes it.
    var problem by mutableStateOf<String?>(null)
        private set

    /** Closes the problem dialog. */
    fun clearProblem() {
        problem = null
    }

    /**
     * STEP 7: saves the user's edit of one memory, tags included.
     * The question must not be empty (the repository ignores an empty one);
     * an empty answer becomes "no answer". [tags] is text with commas, for
     * example "brother, rahul, kotlin". The date the user wrote it stays as
     * it was.
     */
    fun updateMemory(memoryId: Long, question: String, answer: String, tags: String) {
        viewModelScope.launch {
            try {
                repo.updateMemory(memoryId, question, answer, tags)
            } catch (e: SQLiteException) {
                problem = "The memory could not be saved. Is the phone storage full?"
            }
        }
    }

    /**
     * The older version without tags: the memory keeps the tags it has.
     */
    fun updateMemory(memoryId: Long, question: String, answer: String) {
        viewModelScope.launch {
            try {
                repo.updateMemory(memoryId, question, answer)
            } catch (e: SQLiteException) {
                problem = "The memory could not be saved. Is the phone storage full?"
            }
        }
    }

    /**
     * Deletes ONE memory. The database wipes it from the file
     * (secure_delete is ON in memories.db), and its search vector goes with it.
     */
    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch {
            try {
                repo.deleteMemory(memoryId)
            } catch (e: SQLiteException) {
                problem = "The memory could not be deleted."
            }
        }
    }

    /**
     * Deletes ALL memories (the screen asks "Are you sure?" first).
     * The chats and the markers are not touched.
     */
    fun deleteAllMemories() {
        viewModelScope.launch {
            try {
                repo.deleteAllMemories()
            } catch (e: SQLiteException) {
                problem = "The memories could not be deleted."
            }
        }
    }
}
