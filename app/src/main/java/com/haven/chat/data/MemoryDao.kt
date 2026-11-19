// =====================================================================
// MemoryDao.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: "DAO" = Data Access Object. The list of everything the app may
//       ASK the memories database to do:
//         - give me the saved memories (and keep telling me when they change),
//         - add memories, edit one, delete one, delete all,
//         - read / move / remove the "last-done marker" of a chat,
//         - save a batch of new memories AND the marker together.
// HOW:  Same as ChatDao.kt: we write the SQL line (@Query) or the label
//       (@Insert) and Room writes the real code at build time (KSP). The
//       SQL is CHECKED during the build: a typo in a column name is a
//       build error, not a crash on the phone.
//
// FLOW vs SUSPEND (same rule as ChatDao.kt):
//   - "Flow" function (observeMemories): a LIVE stream. The first list
//     arrives at once and a NEW list arrives by itself after every change.
//     The "Privacy & local data" screen will use it, so the list updates
//     by itself after an edit or delete.
//   - "suspend" functions: do one job in the background and give back the
//     result. They must be called from a coroutine.
//
// WHY AN ABSTRACT CLASS (and not an interface): saveBatch() below has real
//   code inside (two steps in one go), and Room supports that most safely
//   in an abstract class. Same reason as ChatDao.
//
// DUPLICATE SAFETY: inserts use OnConflictStrategy.IGNORE. The memories
//   table has a unique index on (sourceChatId, sourceMessageId) (see
//   MemoryEntities.kt). If the same message is inserted twice, the second
//   insert does nothing (and reports -1) instead of crashing or making a
//   duplicate.
//
// MARKERS AND DELETES (on purpose):
//   - Deleting ONE memory does not touch any marker, so a memory the user
//     deleted is NOT made again from the same chat.
//   - "Delete all" also keeps the markers, for the same reason.
//
// NOT HERE YET: the keyword search for Gemma (light search). It is added
//   in step 6 of the plan.
//
// USED BY: MemoryDatabase.kt (creates it), MemoryRepository.kt (calls it)
// =====================================================================
package com.haven.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MemoryDao {

    // -----------------------------------------------------------------
    // MEMORY LIST (live)
    // -----------------------------------------------------------------

    // All memories, newest first (by the date the user wrote the question;
    // id breaks a tie). Live: a new list arrives after every change.
    @Query("SELECT * FROM memories ORDER BY createdAt DESC, id DESC")
    abstract fun observeMemories(): Flow<List<MemoryEntity>>

    // How many memories there are (live). For a counter on the Privacy screen.
    @Query("SELECT COUNT(*) FROM memories")
    abstract fun observeCount(): Flow<Int>

    // -----------------------------------------------------------------
    // ONE MEMORY
    // -----------------------------------------------------------------

    // The memory with this id, or null if it does not exist (any more).
    @Query("SELECT * FROM memories WHERE id = :memoryId")
    abstract suspend fun getMemory(memoryId: Long): MemoryEntity?

    // Adds one memory. Give id = 0 and the database picks a new id.
    // Returns the new id, or -1 if it was ignored (same chat message
    // already saved). IGNORE = "do nothing on a duplicate".
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMemory(memory: MemoryEntity): Long

    // Adds several memories. One id per memory, in the same order;
    // an ignored duplicate gives -1 at its place.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMemories(memories: List<MemoryEntity>): List<Long>

    // The user edited a memory on the Privacy screen: change the texts and
    // remember when. (The date the user wrote it, createdAt, stays as it was.)
    @Query(
        """
        UPDATE memories
        SET question = :question, answer = :answer, savedAt = :savedAt
        WHERE id = :memoryId
        """
    )
    abstract suspend fun updateMemory(memoryId: Long, question: String, answer: String?, savedAt: Long)

    // Deletes one memory. Deleting an id that does not exist does nothing.
    @Query("DELETE FROM memories WHERE id = :memoryId")
    abstract suspend fun deleteMemory(memoryId: Long)

    // Deletes ALL memories ("Delete all" on the Privacy screen).
    // The markers stay (see the note at the top).
    @Query("DELETE FROM memories")
    abstract suspend fun deleteAllMemories()

    // -----------------------------------------------------------------
    // MARKERS (how far each chat was already read)
    // -----------------------------------------------------------------

    // The marker of a chat, or null if this chat was never read.
    @Query("SELECT * FROM chat_markers WHERE chatId = :chatId")
    abstract suspend fun getMarker(chatId: Long): ChatMarkerEntity?

    // Saves a marker. If the chat already has one, it is replaced
    // (that is how the marker "moves forward").
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMarker(marker: ChatMarkerEntity)

    // Removes the marker of a chat (called when the chat is deleted).
    @Query("DELETE FROM chat_markers WHERE chatId = :chatId")
    abstract suspend fun deleteMarker(chatId: Long)

    // -----------------------------------------------------------------
    // THE IMPORTANT ONE: save new memories AND move the marker together
    // -----------------------------------------------------------------

    // "@Transaction" = both steps happen together or not at all. So the
    // marker can never say "this part of the chat is done" when its
    // memories were not saved (for example if the app is killed right in
    // the middle), and never the other way round.
    // The list may be EMPTY (a batch with no personal message): then only
    // the marker moves, so the batch is not read again.
    // Returns how many memories were really added (duplicates not counted).
    // "open" is needed because Kotlin classes are closed by default and
    // Room has to wrap this function.
    @Transaction
    open suspend fun saveBatch(memories: List<MemoryEntity>, marker: ChatMarkerEntity): Int {
        val ids = insertMemories(memories)
        upsertMarker(marker)
        return ids.count { it != -1L }
    }
}
