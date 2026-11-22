// =====================================================================
// MemoryEntities.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: Describes the THREE database TABLES of the "fair notebook" (the
//       memories):
//         memories        one row per saved PERSONAL memory
//         memory_vectors  one row per memory that has a search VECTOR
//                         (STEP 7, for heavy search)
//         chat_markers    one row per chat: "how far did we already read it?"
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
//   (The ONE foreign key in this file is inside the fair notebook itself:
//   a vector belongs to a memory, so deleting a memory also deletes its
//   vector, see memory_vectors below.)
//
// WHAT ONE MEMORY IS:
//   - the user's personal QUESTION (or message), copied word for word,
//   - Gemma's FINAL ANSWER to it, copied word for word. NEVER the thinking
//     text. Gemma only DECIDES which questions are personal (and writes the
//     TAGS); the app copies the texts straight from the saved messages
//     (nothing is rewritten).
//   - the answer can be missing (null), for example when the answer was
//     stopped before any text was written. Then only the question is kept.
//   - STEP 7: 4 to 6 TAGS that Gemma wrote for the question. They are the
//     search keywords of the memory (light search matches them, and heavy
//     search embeds them together with the question).
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
// STEP 7 CHANGES (marked "STEP 7"):
//   - memories got a column "tags" (text, tags separated by commas).
//     Memories saved before this step simply have no tags (empty text).
//   - a new table memory_vectors holds the search vector of a memory. It is
//     a SEPARATE table on purpose: the Privacy screen and the light search
//     read the whole memories table, and a vector (about 4 KB each) would
//     make every one of those reads much bigger for nothing.
//   - The database version goes from 1 to 2 and MemoryDatabase.kt gets a
//     proper Migration that adds the column and the table WITHOUT touching
//     the saved memories.
//   - two small helper pairs at the bottom: tags <-> text, vector <-> bytes.
//
// USED BY: MemoryDao.kt, MemoryDatabase.kt, MemoryRepository.kt
// =====================================================================
package com.haven.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    val sourceMessageId: Long? = null,

    // STEP 7: the tags Gemma wrote for this question, separated by commas,
    // for example "brother, rahul, kotlin, course, family, plan".
    // Empty text = no tags (memories from before step 7, or Gemma gave none).
    // defaultValue "''" is what the migration's ALTER TABLE uses for the old
    // rows; it must be written here too, so Room finds the same table.
    // Use tagsToText() / tagsFromText() (bottom of this file) to convert.
    @ColumnInfo(defaultValue = "''")
    val tags: String = ""
)

// ---------------------------------------------------------------------
// STEP 7 (NEW) TABLE "memory_vectors": the search vector of a memory.
//
// A vector is a list of numbers (1024 of them for bge-m3) that stands for
// the MEANING of the memory's text. Heavy search compares the vector of the
// user's question with these vectors to find the closest memories.
//
// One row per memory. The row exists only for memories that were embedded;
// the app finds the others (the ones with NO row here) and embeds them
// later. Only NEW memories are embedded, old vectors are never made again.
//
// FOREIGN KEY with ON DELETE CASCADE: when a memory is deleted (one, or
// "Delete all"), SQLite deletes its vector by itself, in the same step, and
// secure_delete wipes it from the file. A vector never outlives its memory.
// memoryId is also the PRIMARY KEY, which already makes the cascade fast
// (no extra index needed).
// ---------------------------------------------------------------------
@Entity(
    tableName = "memory_vectors",
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],            // column "id" in memories ...
            childColumns = ["memoryId"],       // ... is stored here
            onDelete = ForeignKey.CASCADE      // delete the memory = delete its vector
        )
    ]
)
class MemoryVectorEntity(
    // The memory this vector belongs to. Also the primary key: one vector
    // per memory.
    @PrimaryKey
    val memoryId: Long,

    // The name of the embedding model that made the vector (for example the
    // file name "bge-m3-q8_0"). If the user switches to another embedding
    // model, vectors of the old model must not be compared with new ones, so
    // the app only uses vectors whose model matches the current one.
    val model: String,

    // How many numbers the vector has (1024 for bge-m3).
    val dimension: Int,

    // The numbers, as bytes: every number is a 4-byte float (see
    // toVectorBytes / toVectorFloats at the bottom of this file).
    val vector: ByteArray
) {
    // A ByteArray is compared by its CONTENT, not by its address. (Kotlin
    // would warn about this in a data class, so the class is not a data
    // class and these two functions are written by hand.)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryVectorEntity) return false
        return memoryId == other.memoryId &&
                model == other.model &&
                dimension == other.dimension &&
                vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = memoryId.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimension
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

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

// ---------------------------------------------------------------------
// STEP 7 HELPERS: tags <-> text
// The database keeps the tags of a memory as ONE text, separated by commas.
// The Privacy screen shows and edits that text; the search turns it back
// into a list.
// ---------------------------------------------------------------------

/**
 * ["brother", "rahul", "kotlin"]  ->  "brother, rahul, kotlin".
 * A comma INSIDE a tag would break the list apart later, so it is replaced
 * by a space. Empty tags are dropped.
 */
fun tagsToText(tags: List<String>): String =
    tags.map { it.replace(',', ' ').trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")

/**
 * "brother, rahul, kotlin"  ->  ["brother", "rahul", "kotlin"].
 * Spaces around a tag are removed; empty parts are dropped.
 */
fun tagsFromText(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

// ---------------------------------------------------------------------
// STEP 7 HELPERS: vector <-> bytes
// The database stores a BLOB (bytes), the search works with floats.
// Every float is 4 bytes, "little endian" (the usual order on phones).
// 1024 floats = 4096 bytes.
// ---------------------------------------------------------------------

/** The numbers of a vector as bytes, ready to be saved in memory_vectors. */
fun FloatArray.toVectorBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(this.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (value in this) buffer.putFloat(value)
    return buffer.array()
}

/** The bytes of memory_vectors turned back into the numbers of the vector. */
fun ByteArray.toVectorFloats(): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(this.size / 4)
    for (i in out.indices) out[i] = buffer.getFloat()
    return out
}
