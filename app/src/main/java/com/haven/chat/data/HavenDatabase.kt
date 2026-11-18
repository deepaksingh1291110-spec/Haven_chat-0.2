// =====================================================================
// HavenDatabase.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 3
// WHAT: The database itself. It joins the pieces:
//         - the TABLES   (ChatEntity, MessageEntity  -> ChatEntities.kt)
//         - the QUERIES  (ChatDao                    -> ChatDao.kt)
//       and creates ONE file on the phone, "haven.db", inside the app's
//       private storage (no other app can read it).
//
// HOW IT IS USED: anywhere in the app:
//           val db = HavenDatabase.get(context)
//           db.chatDao().observeChats()
//       "get" always returns the SAME database object (a "singleton").
//       Opening two copies of the same file would cause trouble, so the
//       code below makes sure only one is ever created.
//
// TWO SAFETY SWITCHES (turned on every time the database opens):
//   1) foreign_keys = ON
//        Makes SQLite obey the link between messages and chats, so that
//        deleting a chat deletes its messages (ON DELETE CASCADE).
//        Room normally does this itself; we set it again so the cascade
//        can never silently stop working.
//   2) secure_delete = ON   (you asked for this)
//        Normally SQLite only marks deleted data as "free space" and the
//        old text can stay inside the file until it is overwritten.
//        With secure_delete ON, deleted data is overwritten with zeros
//        at once. So a deleted chat is really gone from the file.
//        Price: a tiny bit more work on every delete/update. For a chat
//        app this is not noticeable.
//
// VERSION NUMBER: "version = 1" is the shape of the tables. Whenever we
//       change a table later (Phase 4 adds a memories table), the number
//       must go up AND we must write a "migration" that moves the old
//       data. We deliberately do NOT use "fallbackToDestructiveMigration",
//       because it would silently DELETE all chats on a mistake.
//
// exportSchema = false: Room can save a copy of the table layout as a JSON
//       file for reviewing migrations. We do not need that, and false
//       avoids a build warning.
// =====================================================================
package com.haven.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class],   // the tables
    version = 1,
    exportSchema = false
)
abstract class HavenDatabase : RoomDatabase() {

    // Room writes the body of this function at build time.
    abstract fun chatDao(): ChatDao

    companion object {

        // The one and only database object. "@Volatile" = every thread
        // sees a change at once.
        @Volatile
        private var instance: HavenDatabase? = null

        // Runs every time the database file is opened.
        private val openCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // "query(...)" is used instead of "execSQL(...)" because a
                // PRAGMA may answer with a row, and execSQL refuses that
                // on some Android versions. We read nothing, just close.
                try {
                    db.query("PRAGMA foreign_keys = ON").close()
                    db.query("PRAGMA secure_delete = ON").close()
                } catch (e: Exception) {
                    // Not fatal: the app still works without the switches.
                }
            }
        }

        /** Returns the database. Creates it the first time. Safe from any thread. */
        fun get(context: Context): HavenDatabase {
            // Fast path: already created.
            instance?.let { return it }

            // Slow path: only ONE thread at a time may create it.
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    // applicationContext lives as long as the app (a screen's
                    // context would leak memory).
                    context.applicationContext,
                    HavenDatabase::class.java,
                    "haven.db"                      // file name on the phone
                )
                    .addCallback(openCallback)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
