// =====================================================================
// MemoryEntities.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: Describes the two TABLES of the "fair notebook" (the memories):
//         memories       one row per saved PERSONAL memory
//         chat_markers   one row per chat: "how far did we already read it?"
//       In Room, a table is described by a Kotlin "data class" with the
//       @Entity label. Room reads these classes at build time (through KSP)
//       and writes the SQL code for us (same way as ChatEntities.kt).
//
// THE PICTURE (rough notebook and fair notebook):
//   - ROUGH notebook = chats + messages (ChatEntities.kt, haven.db).
//     The user writes anything there. Deleting a chat tears out its pages.
//   - FAIR notebook = THIS file's tables (memories.db, a separate file).
//     Only the important PERSONAL things are copied here.
//
// MOST IMPORTANT RULE: THERE IS NO FOREIGN KEY to the chats table, and no
//   cascade. Deleting a chat must NEVER delete its memories. The memories
//   live in their own database file, so Room could not even link them.
//   "sourceChatId" and "sourceMessageId" are plain numbers kept only as a
//   reference (and to stop the same message being saved twice).
//
// WHAT ONE MEMORY IS:
//   - the user's personal QUESTION (or message), copied word for word,
//   - Gemma's FINAL ANSWER to it, copied word for word. NEVER the thinking
//     text. Gemma only DECIDES which questions are personal; the app copies
//     the texts straight from the saved messages (nothing is rewritten).
//   - the answer can be missing (null), for example when the answer was
//     stopped before any text was written. Then only the question is kept.
//
// DATES are plain numbers: milliseconds since 1970 (System.currentTimeMillis()),
//   like in ChatEntities.kt.
//
// NO "UPDATE A CHANGED FACT" RULE HERE: if the user later says "I moved to
//   Pune", the old memory ("I live in Delhi") is NOT found and replaced by
//   the app, because that would need a second, harder Gemma job. Both
//   memories stay, each with its date, so the newer one is clear. The user
//   can edit or delete the old one on the "Privacy & local data" screen.
//
// LATER (only if heavy search is ever built): a vector (BLOB) and the name
//   of the embedding model are added to "memories". That needs
//   MemoryDatabase version 2 plus a Migration. Not now.
//
// USED BY: MemoryDao.kt, MemoryDatabase.kt, MemoryRepository.kt
// =====================================================================
package com.haven.chat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------------
// TABLE "memories": one row per saved personal memory.
// ---------------------------------------------------------------------
@Entity(
    tableName = "memories",

    // SAFETY: the same message of the same chat can be saved only ONCE.
    // If the extraction runs twice over the same message (for example the
    // app was killed before the marker was saved), the second insert is
    // ignored by the DAO instead of making a duplicate.
    // A memory that the user adds by hand has no source (both are null);
    // SQLite treats nulls as different, so any number of those is allowed.
    // Message ids and chat ids are never reused (Room uses AUTOINCREMENT),
    // so a deleted chat can never clash with a later one.
    indices = [Index(value = ["sourceChatId", "sourceMessageId"], unique = true)]
)
data class MemoryEntity(
    // Unique number of the memory. autoGenerate = the database picks the
    // next free number when we insert with id = 0.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The user's personal question or message, word for word.
    val question: String,

    // Gemma's FINAL answer (never the thinking text), word for word.
    // null = there was no saved answer.
    val answer: String? = null,

    // When the user WROTE the question (copied from the message). This is
    // the date shown to the user, and the one a time filter ("last month")
    // will use later.
    val createdAt: Long,

    // When this memory was saved or last edited by the user.
    val savedAt: Long,

    // REFERENCE ONLY, no link: the chat this memory came from, and the id
    // of the question message inside it. The chat may be deleted long ago;
    // the memory stays. null = made by hand on the Privacy screen.
    val sourceChatId: Long? = null,
    val sourceMessageId: Long? = null
)

// ---------------------------------------------------------------------
// TABLE "chat_markers": one row per chat that was (partly) read.
// The "last-done marker" of the design: the id of the LAST message of
// this chat that was already checked for personal things.
// The next run reads only messages with a BIGGER id, so old messages are
// never read again and old memories are never made twice.
//
// It is saved in the SAME database step as the new memories (see
// MemoryDao), so the marker can never say "done" for memories that were
// not saved, and the other way round.
//
// "chatId" is a plain number, no foreign key (chats live in another file).
// When a chat is deleted, the repository removes its marker row. A marker
// that is left over by mistake is harmless: chat ids are never reused.
// ---------------------------------------------------------------------
@Entity(tableName = "chat_markers")
data class ChatMarkerEntity(
    // The chat. One row per chat, so the chat id IS the primary key
    // (no autoGenerate: we choose the value ourselves).
    @PrimaryKey
    val chatId: Long,

    // Highest message id already checked in this chat.
    val lastMessageId: Long,

    // When the marker last moved (milliseconds since 1970).
    val updatedAt: Long
)
