// =====================================================================
// ChatEntities.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 3
// WHAT: Describes the two database TABLES of the app:
//         chats     one row per chat   (id, title, dates)
//         messages  one row per message (which chat, who wrote it, text...)
//       In Room, a table is described by a Kotlin "data class" with the
//       @Entity label. Room reads these classes at build time (through
//       KSP) and writes all the SQL code for us.
//
// HOW THEY ARE CONNECTED:
//       Every message has a "chatId" that points to a row in chats.
//       This link is a FOREIGN KEY with ON DELETE CASCADE:
//           delete a chat  ->  SQLite deletes ALL its messages by itself.
//       So we never have "orphan" messages of a chat that is gone.
//       (HavenDatabase.kt switches foreign keys on, and also switches on
//       "secure delete", so the deleted text is wiped from the file.)
//
// DATES are saved as a plain number: milliseconds since 1970
//       (System.currentTimeMillis()). Easy to sort and to compare.
//
// ALSO IN THIS FILE: two small helper functions that convert between the
//       database row (MessageEntity) and the object the screens use
//       (ChatMessage, see ChatModels.kt).
//
// USED BY: ChatDao.kt, HavenDatabase.kt, ChatRepository.kt
// LATER (Phase 4): a third table for long-term memories will be added
//       here, linked to chats in the same way (so deleting a chat also
//       deletes the memories made from it).
// =====================================================================
package com.haven.chat.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------------------------------------------------------------
// TABLE "chats": one row per chat.
// ---------------------------------------------------------------------
@Entity(tableName = "chats")
data class ChatEntity(
    // Unique number of the chat. autoGenerate = the database picks the
    // next free number when we insert with id = 0.
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // The title shown in the drawer list.
    val title: String,

    // When the chat was created (milliseconds since 1970).
    val createdAt: Long,

    // When the chat last changed (a message was added, or it was renamed).
    // The drawer sorts by this: newest first. It also decides the section
    // name ("Today", "Yesterday", ...).
    val updatedAt: Long
)

// ---------------------------------------------------------------------
// TABLE "messages": one row per message.
// ---------------------------------------------------------------------
@Entity(
    tableName = "messages",

    // The link to the chats table (see the top of this file).
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],        // column "id" in chats ...
            childColumns = ["chatId"],     // ... is stored here in messages
            onDelete = ForeignKey.CASCADE  // delete the chat = delete its messages
        )
    ],

    // An index makes "give me all messages of chat 7" fast, and it also
    // makes the cascade delete fast. Room warns at build time without it.
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Which chat this message belongs to.
    val chatId: Long,

    // "USER" or "ASSISTANT" (the name of the Role enum in ChatModels.kt).
    val role: String,

    // The visible message text.
    val text: String,

    // The AI's thinking text (null = none, or a user message).
    val thinking: String? = null,

    // Speed and size of the answer (null for user messages).
    val tokensPerSecond: Float? = null,
    val tokenCount: Int? = null,

    // When the message was saved (milliseconds since 1970).
    val createdAt: Long
)

// ---------------------------------------------------------------------
// CONVERSION: database row  ->  object for the screens
// ---------------------------------------------------------------------
fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    // valueOf turns the text "USER" back into Role.USER. If the text is
    // unknown for any reason, we treat the message as an answer.
    role = try {
        Role.valueOf(role)
    } catch (e: IllegalArgumentException) {
        Role.ASSISTANT
    },
    text = text,
    thinking = thinking,
    tokensPerSecond = tokensPerSecond,
    tokenCount = tokenCount
)

// ---------------------------------------------------------------------
// CONVERSION: object from the screens  ->  database row
// The new row always gets id = 0, so the database chooses a fresh id.
// ---------------------------------------------------------------------
fun ChatMessage.toEntity(chatId: Long, createdAt: Long): MessageEntity = MessageEntity(
    id = 0,
    chatId = chatId,
    role = role.name,                  // Role.USER -> "USER"
    text = text,
    thinking = thinking,
    tokensPerSecond = tokensPerSecond,
    tokenCount = tokenCount,
    createdAt = createdAt
)
