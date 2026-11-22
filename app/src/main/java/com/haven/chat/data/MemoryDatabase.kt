// =====================================================================
// MemoryDatabase.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 4
// WHAT: The database of the "fair notebook" (the memories). It joins:
//         - the TABLES   (MemoryEntity, MemoryVectorEntity, ChatMarkerEntity
//                         -> MemoryEntities.kt)
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
// SAFETY SWITCHES (turned on every time the database opens):
//   1) secure_delete = ON
//        Normally SQLite only marks deleted data as "free space" and the
//        old text can stay inside the file until it is overwritten.
//        With secure_delete ON, deleted data is overwritten with zeros at
//        once. So a memory that the user deletes (or "Delete all") is
//        really gone from the file, and so is its vector.
//   2) foreign_keys = ON   (STEP 7)
//        Version 2 has one foreign key: a vector belongs to a memory, and
//        deleting the memory must delete its vector (ON DELETE CASCADE).
//        Room normally switches this on itself; we set it again so the
//        cascade can never silently stop working (same as HavenDatabase).
//
// VERSION NUMBER: "version" is the shape of the tables. Whenever we change a
//       table, the number must go up AND we must write a "migration" that
//       moves the old data. We deliberately do NOT use
//       "fallbackToDestructiveMigration", because it would silently DELETE
//       all memories on a mistake.
//
// STEP 7: VERSION 1 -> 2 (marked "STEP 7"):
//   MIGRATION_1_2 does two things and touches NO saved memory:
//     - adds the column "tags" to memories (old rows get empty text),
//     - creates the new table memory_vectors (empty at first).
//   A phone that already has memories.db at version 1 runs the migration
//   once, the first time the new app opens it. A fresh install has no old
//   file: Room creates version 2 directly from the entity classes.
//   IMPORTANT: the SQL text below must describe EXACTLY the same columns
//   as MemoryEntities.kt. If they differ, Room refuses to open the database
//   ("Migration didn't properly handle...").
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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MemoryEntity::class, MemoryVectorEntity::class, ChatMarkerEntity::class],   // the tables
    version = 2,                                                                           // STEP 7: was 1
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

        // -------------------------------------------------------------
        // STEP 7: the migration from version 1 to version 2.
        // Runs inside one transaction: both steps happen or neither does.
        // -------------------------------------------------------------
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) The tags of a memory. Old rows get '' (no tags).
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")

                // 2) The vectors, one row per memory that was embedded.
                //    ON DELETE CASCADE: deleting a memory deletes its vector.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_vectors` (" +
                            "`memoryId` INTEGER NOT NULL, " +
                            "`model` TEXT NOT NULL, " +
                            "`dimension` INTEGER NOT NULL, " +
                            "`vector` BLOB NOT NULL, " +
                            "PRIMARY KEY(`memoryId`), " +
                            "FOREIGN KEY(`memoryId`) REFERENCES `memories`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
            }
        }

        // Runs every time the database file is opened.
        private val openCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // "query(...)" is used instead of "execSQL(...)" because a
                // PRAGMA may answer with a row, and execSQL refuses that
                // on some Android versions. We read nothing, just close.
                try {
                    db.query("PRAGMA foreign_keys = ON").close()     // STEP 7
                    db.query("PRAGMA secure_delete = ON").close()
                } catch (e: Exception) {
                    // Not fatal: the app still works without the switches.
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
                    .addMigrations(MIGRATION_1_2)   // STEP 7
                    .addCallback(openCallback)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
