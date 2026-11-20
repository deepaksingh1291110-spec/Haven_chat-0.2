// =====================================================================
// MemoryRepository.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: The ONE place the rest of the app uses to save and load the
//       memories (the "fair notebook"). The ViewModels never talk to the
//       database (MemoryDao) directly; they talk to this class. It is the
//       twin of ChatRepository.kt, but for memories.
//
// WHAT IT OFFERS:
//   - the live list of memories + a live counter     (Privacy screen)
//   - edit one memory, delete one, delete all         (Privacy screen)
//   - saveBatch(): save the new memories of ONE batch and move the
//     chat's "last-done marker" in the same database step  (extraction)
//   - lastMemorizedMessageId(): where the extraction must continue
//   - removeMarker(): called when a chat is deleted
//   - (step 6) allMemories() and count(): what the keyword search for
//     Gemma needs
//
// WHY A SEPARATE CLASS: if the database changes later (for example vectors
//   for heavy search), only this file has to change, not every screen.
//
// THREADS: Room runs its queries on its own background threads, so the
//   "suspend" functions below are safe to call from the main thread
//   (inside a coroutine). The "Flow" functions are live: they send a new
//   value by itself whenever the memories change.
//
// NOTHING HERE TALKS TO GEMMA. This class only stores and loads. Which
//   messages are personal is decided elsewhere (later steps); this class
//   just receives the final pairs and saves them word for word.
//
// PHASE 4, STEP 6 (the only change in this file, marked "STEP 6"):
//   allMemories() and count(). They never throw: a database problem gives
//   null (allMemories) or 0 (count), so a problem in the memory search can
//   never crash a chat answer.
//
// USED BY: the extraction code (saveBatch, markers), the delete-chat flow
//   (removeMarker), the Privacy & local data screen, and the memory search.
// =====================================================================
package com.haven.chat.data

import android.content.Context
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------
// One NEW memory, as the extraction hands it over. The repository turns
// it into a database row (MemoryEntity).
//   question          the user's personal message, word for word
//   answer            Gemma's FINAL answer to it (never the thinking text),
//                     word for word. null = there was no saved answer.
//   questionTime      when the user wrote the question (milliseconds since
//                     1970). Comes from the saved message.
//   questionMessageId the id of the question message in haven.db (a REAL
//                     database id, never a temporary negative one). It stops
//                     the same message from being saved twice.
// ---------------------------------------------------------------------
class NewMemory(
    val question: String,
    val answer: String?,
    val questionTime: Long,
    val questionMessageId: Long
)

class MemoryRepository(context: Context) {

    // The database queries. MemoryDatabase.get() always returns the same
    // database object, so creating several repositories is harmless.
    private val dao: MemoryDao = MemoryDatabase.get(context).memoryDao()

    // -----------------------------------------------------------------
    // MEMORY LIST (live)
    // -----------------------------------------------------------------

    /**
     * All memories, newest first, as a LIVE stream: a new list arrives by
     * itself after every change (saved, edited, deleted). The screens use
     * MemoryEntity directly: it already holds everything they show
     * (question, answer, date).
     */
    fun observeMemories(): Flow<List<MemoryEntity>> = dao.observeMemories()

    /** How many memories are saved, as a LIVE number. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    // -----------------------------------------------------------------
    // STEP 6: for the keyword search (read once, not live)
    // -----------------------------------------------------------------

    /**
     * ALL memories, once, newest first. The keyword search (MemorySearch.kt)
     * scores them in memory. Returns null when the database could not be
     * read (then the search tells Gemma it could not look). Never throws.
     */
    suspend fun allMemories(): List<MemoryEntity>? {
        return try {
            dao.getAllMemories()
        } catch (e: SQLiteException) {
            null
        }
    }

    /**
     * How many memories are saved, once. 0 also when the database could not
     * be read: then Gemma simply gets no search tool for that answer.
     * Never throws.
     */
    suspend fun count(): Int {
        return try {
            dao.getCount()
        } catch (e: SQLiteException) {
            0
        }
    }

    // -----------------------------------------------------------------
    // EDIT / DELETE (Privacy & local data screen)
    // -----------------------------------------------------------------

    /**
     * Saves the user's edit of a memory. The question must not be empty
     * (an empty question is ignored). An empty answer becomes "no answer".
     * The date the user wrote it stays as it was.
     */
    suspend fun updateMemory(memoryId: Long, question: String, answer: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        val a = answer.trim().ifEmpty { null }
        dao.updateMemory(memoryId, q, a, System.currentTimeMillis())
    }

    /** Deletes one memory. The database wipes it from the file (secure_delete). */
    suspend fun deleteMemory(memoryId: Long) {
        dao.deleteMemory(memoryId)
    }

    /**
     * Deletes ALL memories. The markers stay on purpose: memories that the
     * user deleted are not made again from the same chats.
     */
    suspend fun deleteAllMemories() {
        dao.deleteAllMemories()
    }

    // -----------------------------------------------------------------
    // EXTRACTION: markers and batches
    // -----------------------------------------------------------------

    /**
     * The id of the last message of this chat that was already checked.
     * 0 = this chat was never read, so the extraction starts at its
     * beginning (real message ids start at 1).
     * The extraction reads only messages with a BIGGER id.
     */
    suspend fun lastMemorizedMessageId(chatId: Long): Long {
        return dao.getMarker(chatId)?.lastMessageId ?: 0L
    }

    /**
     * Saves the personal memories of ONE batch AND moves the chat's marker
     * to [lastMessageId], both in ONE database step (all or nothing).
     *
     *   chatId        the chat the batch came from
     *   memories      the personal pairs of this batch. May be EMPTY: then
     *                 only the marker moves, so the batch is not read again.
     *   lastMessageId the highest message id of the whole batch (personal
     *                 or not): everything up to it has now been checked.
     *
     * The marker only ever moves FORWARD: a smaller id than the saved
     * marker is ignored.
     *
     * Returns how many memories were really added (an already saved message
     * is skipped and not counted), or -1 when nothing could be saved (for
     * example the phone storage is full). It never crashes the app. The
     * delete-chat flow MUST treat -1 as "do not delete the chat".
     */
    suspend fun saveBatch(
        chatId: Long,
        memories: List<NewMemory>,
        lastMessageId: Long
    ): Int {
        return try {
            val now = System.currentTimeMillis()

            // Never move the marker backwards.
            val oldMarker = dao.getMarker(chatId)?.lastMessageId ?: 0L
            val newMarker = maxOf(oldMarker, lastMessageId)

            val rows = memories.map { m ->
                MemoryEntity(
                    id = 0,                              // the database picks the id
                    question = m.question,
                    answer = m.answer,
                    createdAt = m.questionTime,
                    savedAt = now,
                    sourceChatId = chatId,               // reference only, no link
                    sourceMessageId = m.questionMessageId
                )
            }

            dao.saveBatch(rows, ChatMarkerEntity(chatId, newMarker, now))
        } catch (e: SQLiteException) {
            -1
        }
    }

    /**
     * Removes the marker of a chat. Call it AFTER the chat was deleted.
     * (A marker that is left over by mistake is harmless: chat ids are
     * never reused.)
     */
    suspend fun removeMarker(chatId: Long) {
        dao.deleteMarker(chatId)
    }
}
