// =====================================================================
// ChatRepository.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 3
// WHAT: The ONE place the rest of the app uses to save and load chats.
//       The ViewModels never talk to the database (ChatDao) directly;
//       they talk to this class. It hides the database details and gives
//       back the simple objects the screens already know:
//           ChatSummary  (a row in the drawer list)
//           ChatMessage  (one message in the chat)
//
// WHY A SEPARATE CLASS:
//   - If we change the database later (Phase 4 adds memories), only this
//     file has to change, not every screen.
//   - Small jobs that belong to saving live here: the section name of a
//     chat ("Today", "Yesterday"...), cleaning a title, making a fallback
//     title from the first message, protecting the search text.
//
// THREADS: Room runs its queries on its own background threads, so the
//       "suspend" functions below are safe to call from the main thread
//       (inside a coroutine). The "Flow" function is live: it sends a new
//       list by itself whenever the chats change.
//
// USED BY (next steps): ChatViewModel (save messages, open and delete
//       chats, set titles) and the drawer (chat list and search).
//
// PHASE 4 CHANGE (the only change in this file, marked "PHASE 4"):
//   New class StoredMessage and new function loadMessagesAfter(). They give
//   the memory extraction the messages of a chat that are newer than the
//   chat's "last-done marker", with their REAL database ids and dates.
//   (ChatMessage has no date, so it is not enough for the extraction.)
//   Nothing else in this file was changed.
// =====================================================================
package com.haven.chat.data

import android.content.Context
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// ---------------------------------------------------------------------
// PHASE 4 (NEW): one SAVED message, as the memory extraction needs it.
//   id         the real database id (never a temporary negative one)
//   isUser     true = the user's message, false = Gemma's answer
//   text       the visible text. For an answer this is the FINAL answer
//              (the thinking text is not part of it).
//   createdAt  when it was saved (milliseconds since 1970)
// ---------------------------------------------------------------------
class StoredMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val createdAt: Long
)

class ChatRepository(context: Context) {

    // The database queries. HavenDatabase.get() always returns the same
    // database object, so creating several repositories is harmless.
    private val dao: ChatDao = HavenDatabase.get(context).chatDao()

    // -----------------------------------------------------------------
    // CHAT LIST (live)
    // -----------------------------------------------------------------

    /**
     * The chats for the drawer, newest first, as a LIVE stream: a new list
     * arrives by itself after every change (new chat, new message, rename,
     * delete).
     *
     * query = text from the search box. Empty = all chats. Otherwise only
     * chats whose title or messages contain the text.
     */
    fun observeChats(query: String = ""): Flow<List<ChatSummary>> {
        val q = query.trim()
        val source = if (q.isEmpty()) dao.observeChats() else dao.searchChats(escapeLike(q))
        return source.map { chats ->
            // "today" is read once per list, so every row uses the same day.
            val today = LocalDate.now()
            chats.map { chat ->
                ChatSummary(
                    id = chat.id,
                    title = chat.title,
                    group = groupOf(chat.updatedAt, today)
                )
            }
        }
    }

    // -----------------------------------------------------------------
    // ONE CHAT
    // -----------------------------------------------------------------

    /** Creates an empty chat and returns its id. */
    suspend fun createChat(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertChat(
            ChatEntity(
                title = normalizeTitle(title),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** Gives a chat a new title. An empty title is ignored. */
    suspend fun renameChat(chatId: Long, title: String) {
        val clean = normalizeTitle(title)
        if (clean.isEmpty()) return
        dao.renameChat(chatId, clean)
    }

    /**
     * Deletes the chat. The database deletes ALL its messages with it
     * (ON DELETE CASCADE) and, with secure_delete on, wipes them from the
     * file. Deleting a chat that does not exist does nothing.
     */
    suspend fun deleteChat(chatId: Long) {
        dao.deleteChat(chatId)
    }

    // -----------------------------------------------------------------
    // MESSAGES
    // -----------------------------------------------------------------

    /** All messages of a chat, oldest first, ready for the chat screen. */
    suspend fun loadMessages(chatId: Long): List<ChatMessage> {
        return dao.getMessages(chatId).map { it.toChatMessage() }
    }

    /**
     * PHASE 4 (NEW): the messages of a chat that are NEWER than the marker,
     * oldest first, for the memory extraction.
     *
     *   afterId = the chat's "last-done marker" (the id of the last message
     *             already checked). 0 = the chat was never read.
     *
     * Returns the messages (an EMPTY list means: nothing new to read), or
     * null when the database could not be read. null is NOT the same as
     * "nothing new": the delete-chat flow must treat null as "do not delete
     * the chat", otherwise a read problem could lose personal things.
     */
    suspend fun loadMessagesAfter(chatId: Long, afterId: Long): List<StoredMessage>? {
        return try {
            dao.getMessagesAfter(chatId, afterId).map { row ->
                StoredMessage(
                    id = row.id,
                    // Anything that is not exactly "USER" counts as an
                    // answer (same rule as toChatMessage in ChatEntities.kt).
                    isUser = row.role == Role.USER.name,
                    text = row.text,
                    createdAt = row.createdAt
                )
            }
        } catch (e: SQLiteException) {
            null
        }
    }

    /**
     * Saves one message and moves its chat to the top of the list.
     * Returns the id the database gave the message, or -1 when it could not
     * be saved (for example: the chat was deleted a moment ago). It never
     * crashes the app.
     */
    suspend fun addMessage(chatId: Long, message: ChatMessage): Long {
        return try {
            dao.addMessage(message.toEntity(chatId, System.currentTimeMillis()))
        } catch (e: SQLiteException) {
            -1L
        }
    }

    // -----------------------------------------------------------------
    // SMALL HELPERS
    // -----------------------------------------------------------------

    // The section name of a chat in the drawer, from its last-change time.
    private fun groupOf(updatedAt: Long, today: LocalDate): String {
        val day = Instant.ofEpochMilli(updatedAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today)
        return when {
            daysAgo <= 0 -> "Today"          // also covers a clock set back
            daysAgo == 1L -> "Yesterday"
            daysAgo <= 7 -> "Previous 7 days"
            daysAgo <= 30 -> "Previous 30 days"
            else -> "Older"
        }
    }

    // In the SQL search, % and _ are wildcards and \ is the escape sign
    // (see ChatDao.searchChats). Putting a \ in front of them makes them
    // normal characters, so a user who types "50%" finds "50%".
    private fun escapeLike(text: String): String {
        val out = StringBuilder()
        for (ch in text) {
            if (ch == '\\' || ch == '%' || ch == '_') out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    companion object {
        // Longest title we keep, and the title used when there is no text.
        private const val MAX_TITLE_CHARS = 60
        const val DEFAULT_TITLE = "New chat"

        // Length of the fallback title made from the first message.
        private const val FALLBACK_TITLE_CHARS = 40

        /** Trims a title, makes it one line and cuts it to the maximum length. */
        fun normalizeTitle(title: String): String {
            val oneLine = title.trim().replace(Regex("\\s+"), " ")
            return if (oneLine.length > MAX_TITLE_CHARS) {
                oneLine.take(MAX_TITLE_CHARS).trimEnd()
            } else {
                oneLine
            }
        }

        /**
         * A quick title from the first words of the user's first message.
         * Used right away when a chat is created (and kept when Auto-name
         * chats is OFF, or when the naming fails or is cancelled).
         * Example: "Can you explain how a binary counter works in detail?"
         *          -> "Can you explain how a binary counter..."
         */
        fun titleFromFirstMessage(text: String): String {
            // First non-empty line only, with all spaces squeezed to one.
            val line = text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?: return DEFAULT_TITLE

            if (line.length <= FALLBACK_TITLE_CHARS) return line

            // Cut at the last space, so a word is not cut in half
            // (unless the first word alone is very long).
            val cut = line.take(FALLBACK_TITLE_CHARS)
            val lastSpace = cut.lastIndexOf(' ')
            val base = if (lastSpace >= 15) cut.substring(0, lastSpace) else cut
            return base.trimEnd() + "..."
        }
    }
}
