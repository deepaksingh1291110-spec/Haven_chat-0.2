// =====================================================================
// MemoryDatabase.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: The database of the "fair notebook" (the memories). It joins:
//         - the TABLES   (MemoryEntity, ChatMarkerEntity -> MemoryEntities.kt)
//         - the QUERIES  (MemoryDao                      -> MemoryDao.kt)
//       and creates ONE file on the phone, "memories.db", inside the app's
//       private storage (no other app can read it).
//
// WHY A SEPARATE FILE (and not inside haven.db):
//   - The chats live in haven.db (the "rough notebook"). Deleting a chat,
//     or later splitting the chats into one file per 4 months, must never
//     touch the memories. Two files make that impossible by design.
//   - haven.db stays at version 1: no migration, no risk for the chats.
//
// HOW IT IS USED: anywhere in the app:
//           val db = MemoryDatabase.get(context)
//           db.memoryDao().observeMemories()
//       "get" always returns the SAME database object (a "singleton"),
//       exactly like HavenDatabase.get(). Opening two copies of the same
//       file would cause trouble, so only one is ever created.
//
// SAFETY SWITCH (turned on every time the database opens):
//   secure_delete = ON
//        Normally SQLite only marks deleted data as "free space" and the
//        old text can stay inside the file until it is overwritten.
//        With secure_delete ON, deleted data is overwritten with zeros at
//        once. So a memory that the user deletes (or "Delete all") is
//        really gone from the file.
//   (foreign_keys is NOT switched on here, unlike in HavenDatabase: this
//    database has no foreign keys at all, on purpose.)
//
// VERSION NUMBER: "version = 1" is the shape of the tables. Whenever we
//       change a table later (for example vectors for heavy search), the
//       number must go up AND we must write a "migration" that moves the
//       old data. We deliberately do NOT use "fallbackToDestructiveMigration",
//       because it would silently DELETE all memories on a mistake.
//
// exportSchema = false: Room can save a copy of the table layout as a JSON
//       file for reviewing migrations. We do not need that, and false
//       avoids a build warning (same as HavenDatabase).
// =====================================================================
package com.haven.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MemoryEntity::class, ChatMarkerEntity::class],   // the tables
    version = 1,
    exportSchema = false
)
abstract class MemoryDatabase : RoomDatabase() {

    // Room writes the body of this function at build time.
    abstract fun memoryDao(): MemoryDao

    companion object {

        // The one and only database object. "@Volatile" = every thread
        // sees a change at once.
        @Volatile
        private var instance: MemoryDatabase? = null

        // Runs every time the database file is opened.
        private val openCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // "query(...)" is used instead of "execSQL(...)" because a
                // PRAGMA may answer with a row, and execSQL refuses that
                // on some Android versions. We read nothing, just close.
                try {
                    db.query("PRAGMA secure_delete = ON").close()
                } catch (e: Exception) {
                    // Not fatal: the app still works without the switch.
                }
            }
        }

        /** Returns the database. Creates it the first time. Safe from any thread. */
        fun get(context: Context): MemoryDatabase {
            // Fast path: already created.
            instance?.let { return it }

            // Slow path: only ONE thread at a time may create it.
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    // applicationContext lives as long as the app (a screen's
                    // context would leak memory).
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "memories.db"                   // file name on the phone
                )
                    .addCallback(openCallback)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
