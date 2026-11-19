// =====================================================================
// ChatDao.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 3
// WHAT: "DAO" = Data Access Object. It is the list of everything the app
//       may ASK the database to do with chats and messages:
//         - give me the chat list (and keep telling me when it changes),
//         - give me all messages of one chat,
//         - add a chat, add a message,
//         - rename a chat, delete a chat,
//         - search chats.
// HOW:  We only write the SQL line (@Query) or the label (@Insert).
//       Room writes the real code from it at build time (through KSP)
//       and CHECKS the SQL during the build: a typo in a column name
//       becomes a build error, not a crash on the phone.
//
// TWO KINDS OF FUNCTIONS:
//   - "Flow" functions (observeChats, searchChats): they are a LIVE
//     stream. The first value arrives at once, and a NEW value arrives
//     by itself every time the tables change. This is why the drawer
//     updates by itself when a chat is added, renamed or deleted.
//   - "suspend" functions: they do one job in the background and give
//     back the result. They must be called from a coroutine.
//
// WHY AN ABSTRACT CLASS (and not an interface): addMessage() below has
//       real code inside (two steps in one go), and Room supports that
//       most safely in an abstract class.
//
// PHASE 4 CHANGE (the only change in this file, marked "PHASE 4"):
//   New function getMessagesAfter() and the small class MessageBrief.
//   They let the memory extraction read only the messages that are NEWER
//   than the chat's "last-done marker" (see MemoryEntities.kt), and only
//   the columns it needs (not the thinking text, which can be long).
//   Nothing else in this file was changed.
//
// USED BY: HavenDatabase.kt (creates it), ChatRepository.kt (calls it)
// =====================================================================
package com.haven.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------
// PHASE 4 (NEW): a SHORT version of a message, for the memory extraction.
// It has only what the extraction needs:
//   id         the real database id of the message (the marker is an id)
//   role       "USER" or "ASSISTANT"
//   text       the visible text (for an answer: the FINAL answer, never
//              the thinking text, which is stored in another column)
//   createdAt  when it was saved (milliseconds since 1970)
// Room fills it from the column names of the query below, so the names
// here MUST be the same as the column names in the SELECT.
// ---------------------------------------------------------------------
data class MessageBrief(
    val id: Long,
    val role: String,
    val text: String,
    val createdAt: Long
)

@Dao
abstract class ChatDao {

    // -----------------------------------------------------------------
    // CHAT LIST (live)
    // -----------------------------------------------------------------

    // All chats, newest first. Live: a new list arrives after every change.
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    abstract fun observeChats(): Flow<List<ChatEntity>>

    // Search: chats whose TITLE contains the text, or that have a MESSAGE
    // containing the text. Newest first. Live as well.
    //   LIKE '%word%' = "contains word" (capitals do not matter).
    //   ESCAPE '\'    = a backslash in the text means "the next character is
    //                   a normal letter" (the repository uses it, so that a
    //                   typed % or _ is not treated as a wildcard).
    //   The second part uses a sub-query, so a chat appears only ONCE even
    //   when many of its messages match.
    @Query(
        """
        SELECT * FROM chats
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR id IN (SELECT chatId FROM messages
                     WHERE text LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY updatedAt DESC
        """
    )
    abstract fun searchChats(query: String): Flow<List<ChatEntity>>

    // -----------------------------------------------------------------
    // ONE CHAT
    // -----------------------------------------------------------------

    // The chat with this id, or null if it does not exist (any more).
    @Query("SELECT * FROM chats WHERE id = :chatId")
    abstract suspend fun getChat(chatId: Long): ChatEntity?

    // Adds a chat. Give id = 0 and the database picks a new id.
    // Returns the new id.
    @Insert
    abstract suspend fun insertChat(chat: ChatEntity): Long

    // Changes the title only. The chat keeps its place in the list
    // (updatedAt is NOT changed, so renaming does not move it to the top).
    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    abstract suspend fun renameChat(chatId: Long, title: String)

    // Deletes the chat. Because of ON DELETE CASCADE (see ChatEntities.kt)
    // the database deletes ALL its messages by itself, in the same step.
    @Query("DELETE FROM chats WHERE id = :chatId")
    abstract suspend fun deleteChat(chatId: Long)

    // Sets "last changed" to the given time. Moves the chat to the top.
    @Query("UPDATE chats SET updatedAt = :time WHERE id = :chatId")
    abstract suspend fun touchChat(chatId: Long, time: Long)

    // -----------------------------------------------------------------
    // MESSAGES
    // -----------------------------------------------------------------

    // All messages of a chat, oldest first (id grows with every new row,
    // so sorting by id keeps the order in which they were written).
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    abstract suspend fun getMessages(chatId: Long): List<MessageEntity>

    // PHASE 4 (NEW): the messages of a chat that are NEWER than the marker,
    // oldest first, in the short form (no thinking text).
    //   afterId = the marker: the id of the last message already checked.
    //             0 = the chat was never read, so everything is returned
    //             (real message ids start at 1).
    // Both USER and ASSISTANT messages are returned, in the order they were
    // written. The extraction needs the answers too: it saves the final
    // answer that follows each personal question.
    @Query(
        """
        SELECT id, role, text, createdAt FROM messages
        WHERE chatId = :chatId AND id > :afterId
        ORDER BY id ASC
        """
    )
    abstract suspend fun getMessagesAfter(chatId: Long, afterId: Long): List<MessageBrief>

    // Adds one message row. Returns its new id.
    @Insert
    abstract suspend fun insertMessage(message: MessageEntity): Long

    // Adds a message AND moves its chat to the top of the list.
    // "@Transaction" = both steps happen together or not at all, so the
    // database can never end up half-updated (for example if the app is
    // killed right in the middle).
    // "open" is needed because Kotlin classes are closed by default and
    // Room has to wrap this function.
    @Transaction
    open suspend fun addMessage(message: MessageEntity): Long {
        val id = insertMessage(message)
        touchChat(message.chatId, message.createdAt)
        return id
    }
}
