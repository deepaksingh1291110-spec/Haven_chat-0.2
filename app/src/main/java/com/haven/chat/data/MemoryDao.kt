// =====================================================================
// MemoryDao.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: "DAO" = Data Access Object. The list of everything the app may
//       ASK the memories database to do:
//         - give me the saved memories (and keep telling me when they change),
//         - add memories, edit one, delete one, delete all,
//         - read / move / remove the "last-done marker" of a chat,
//         - save a batch of new memories AND the marker together,
//         - (step 6) give me ALL memories once, and how many there are,
//           for the keyword search that Gemma can use,
//         - (step 7) save, read and find the search VECTORS of the memories.
// HOW:  Same as ChatDao.kt: we write the SQL line (@Query) or the label
//       (@Insert) and Room writes the real code at build time (KSP). The
//       SQL is CHECKED during the build: a typo in a column name is a
//       build error, not a crash on the phone.
//
// FLOW vs SUSPEND (same rule as ChatDao.kt):
//   - "Flow" function (observeMemories): a LIVE stream. The first list
//     arrives at once and a NEW list arrives by itself after every change.
//     The "Privacy & local data" screen uses it, so the list updates by
//     itself after an edit or delete.
//   - "suspend" functions: do one job in the background and give back the
//     result. They must be called from a coroutine.
//
// WHY AN ABSTRACT CLASS (and not an interface): saveBatch() and
//   editMemory() below have real code inside (two steps in one go), and
//   Room supports that most safely in an abstract class. Same reason as
//   ChatDao.
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
// PHASE 4, STEP 6 (marked "STEP 6"): getAllMemories() and getCount(). The
//   keyword search itself is NOT SQL: Gemma's keywords are different every
//   time, and there are only a few hundred or thousand short memories, so
//   MemorySearch.kt (a plain Kotlin file) scores them in memory. The DAO
//   only has to hand over the rows.
//
// PHASE 4, STEP 7 (marked "STEP 7"): VECTORS FOR HEAVY SEARCH.
//   - A memory can have a vector (table memory_vectors, one row per memory).
//   - getMemoriesWithoutVector(model): the memories that still need a vector
//     from the embedding model [model]. Only memories WITH tags are listed
//     (old memories from before step 7 have none, and are not worth the
//     time). A memory with an old model's vector is listed too, so its
//     vector is made again (the insert replaces the old row).
//   - insertVectors / getVectors / getVectorCount: save, read all (for the
//     brute-force comparison) and count the vectors of one model.
//   - getMemoriesByIds: the memories behind the best vectors.
//   - editMemory(): when the user edits a memory, its vector no longer
//     matches the new text, so it is deleted in the SAME step; the memory
//     then shows up in getMemoriesWithoutVector and is embedded again.
//   - Deleting a memory needs no extra code: ON DELETE CASCADE removes its
//     vector (see MemoryEntities.kt).
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

    // STEP 6: ALL memories, ONCE (not live), newest first. The keyword
    // search reads this list and scores it in memory (see MemorySearch.kt).
    @Query("SELECT * FROM memories ORDER BY createdAt DESC, id DESC")
    abstract suspend fun getAllMemories(): List<MemoryEntity>

    // STEP 6: how many memories there are, ONCE (not live). The chat uses it
    // to decide whether Gemma gets the search tool at all (with no memories
    // there is nothing to search).
    @Query("SELECT COUNT(*) FROM memories")
    abstract suspend fun getCount(): Int

    // -----------------------------------------------------------------
    // ONE MEMORY
    // -----------------------------------------------------------------

    // The memory with this id, or null if it does not exist (any more).
    @Query("SELECT * FROM memories WHERE id = :memoryId")
    abstract suspend fun getMemory(memoryId: Long): MemoryEntity?

    // STEP 7: the memories with these ids (the ones behind the best vectors).
    // Room turns the list into "IN (1, 2, 3...)". The order of the result is
    // NOT the order of [ids]; the caller sorts it.
    @Query("SELECT * FROM memories WHERE id IN (:ids)")
    abstract suspend fun getMemoriesByIds(ids: List<Long>): List<MemoryEntity>

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
    // KEPT from step 3 (it does not touch the tags). The Privacy screen now
    // uses editMemory() below instead.
    @Query(
        """
        UPDATE memories
        SET question = :question, answer = :answer, savedAt = :savedAt
        WHERE id = :memoryId
        """
    )
    abstract suspend fun updateMemory(memoryId: Long, question: String, answer: String?, savedAt: Long)

    // STEP 7: the same, but with the tags too.
    @Query(
        """
        UPDATE memories
        SET question = :question, answer = :answer, tags = :tags, savedAt = :savedAt
        WHERE id = :memoryId
        """
    )
    abstract suspend fun updateMemoryWithTags(
        memoryId: Long,
        question: String,
        answer: String?,
        tags: String,
        savedAt: Long
    )

    // Deletes one memory. Deleting an id that does not exist does nothing.
    // STEP 7: its vector goes with it (ON DELETE CASCADE).
    @Query("DELETE FROM memories WHERE id = :memoryId")
    abstract suspend fun deleteMemory(memoryId: Long)

    // Deletes ALL memories ("Delete all" on the Privacy screen). All the
    // vectors go with them (ON DELETE CASCADE).
    // The markers stay (see the note at the top).
    @Query("DELETE FROM memories")
    abstract suspend fun deleteAllMemories()

    // -----------------------------------------------------------------
    // STEP 7: VECTORS
    // -----------------------------------------------------------------

    // The memories that still need a vector from the embedding model [model],
    // oldest first. Only memories WITH tags (see the notes at the top).
    // LEFT JOIN = every memory, with its vector row if it has one for this
    // model; "v.memoryId IS NULL" keeps only those that have none.
    @Query(
        """
        SELECT m.* FROM memories m
        LEFT JOIN memory_vectors v ON v.memoryId = m.id AND v.model = :model
        WHERE v.memoryId IS NULL AND m.tags != ''
        ORDER BY m.id ASC
        """
    )
    abstract suspend fun getMemoriesWithoutVector(model: String): List<MemoryEntity>

    // Saves vectors. A memory that already has a row (for example from an
    // older embedding model) gets it REPLACED.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertVectors(vectors: List<MemoryVectorEntity>)

    // ALL vectors of one embedding model, once. Heavy search compares the
    // question's vector with every one of them (a few thousand at most:
    // that takes milliseconds).
    @Query("SELECT * FROM memory_vectors WHERE model = :model")
    abstract suspend fun getVectors(model: String): List<MemoryVectorEntity>

    // How many vectors the embedding model [model] has made.
    @Query("SELECT COUNT(*) FROM memory_vectors WHERE model = :model")
    abstract suspend fun getVectorCount(model: String): Int

    // Removes the vector of one memory.
    @Query("DELETE FROM memory_vectors WHERE memoryId = :memoryId")
    abstract suspend fun deleteVector(memoryId: Long)

    // STEP 7: the user edited a memory. Change it AND delete its vector in
    // ONE step, so the old vector can never describe the new text. The
    // memory is embedded again the next time vectors are made.
    // "@Transaction" = both steps happen together or not at all.
    // "open" is needed because Kotlin classes are closed by default and
    // Room has to wrap this function.
    @Transaction
    open suspend fun editMemory(
        memoryId: Long,
        question: String,
        answer: String?,
        tags: String,
        savedAt: Long
    ) {
        updateMemoryWithTags(memoryId, question, answer, tags, savedAt)
        deleteVector(memoryId)
    }

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
    @Transaction
    open suspend fun saveBatch(memories: List<MemoryEntity>, marker: ChatMarkerEntity): Int {
        val ids = insertMemories(memories)
        upsertMarker(marker)
        return ids.count { it != -1L }
    }
}
