// =====================================================================
// ModelStore.kt  (app/src/main/java/com/haven/chat/data/)   NEW (private models)
// WHAT: The app's OWN folder for model files (.gguf), and everything the
//       app does with it:
//         - list the models that are in the folder,
//         - IMPORT a model: copy a file the user picked (with Android's
//           file picker) into the folder, with progress,
//         - RENAME a model,
//         - DELETE a model,
//         - tell how much free space the phone has.
//
// WHY: Before, the app read models from a normal phone folder and needed
//   the permission "All files access". The Play Store does not like that.
//   Now the models live in the app's PRIVATE storage:
//       /data/data/com.haven.chat/files/models/
//   The app can read and write there WITHOUT any permission, and no other
//   app can see it. Price: the file is COPIED, so it uses space twice until
//   the user deletes the original (in the phone's file manager).
//
// HOW THE USER GETS A MODEL IN:
//   The user taps "Add model", Android's file picker opens, the user picks a
//   .gguf file, and importModel(uri, ...) copies it. The picker gives a
//   "Uri" (an address like content://...), NOT a path, so we open it with
//   the ContentResolver and copy the bytes.
//
// SAFE COPY (important):
//   - The bytes are first written to a file called "name.gguf.part". Only
//     when the WHOLE file is copied, the file is renamed to "name.gguf".
//     So a copy that is cancelled, fails, or is killed in the middle never
//     leaves a half model that looks real. A ".part" file is never listed
//     as a model, and cleanLeftovers() deletes old ones.
//   - Free space is checked BEFORE the copy (size + a safety margin).
//   - The first 4 bytes must be "GGUF" (the magic word of the format), so a
//     wrong file (a photo, a zip...) is refused after the first block.
//   - The copy can be cancelled: cancel the coroutine that runs it. The
//     ".part" file is deleted.
//
// NAMES: the file name is what the user sees as the model's name. Rename
//   changes the file name. Rules: letters, digits, space, dot, dash,
//   underscore and brackets only (anything else becomes "_"), at most 80
//   characters, always ends with ".gguf". Two models never share a name:
//   "Gemma.gguf" imported twice becomes "Gemma (2).gguf".
//
// NOTE FOR THE CALLER: a model's PATH is saved in the settings (main model,
//   embedder, reranker). Renaming or deleting a model changes or removes its
//   path, so the caller (ModelsViewModel) must update the settings, and must
//   never rename or delete the model that is loaded right now.
//   The embedding model's NAME is the file name (see MemoryIndexer): renaming
//   the embedder makes its saved search vectors "old", and they are made
//   again at the next memory job.
//
// THREADS: the functions that touch files are "suspend" and run on
//   Dispatchers.IO (a background thread pool made for file work), so they
//   are safe to call from the main thread inside a coroutine.
// =====================================================================
package com.haven.chat.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

// ---------------------------------------------------------------------
// One model in the private folder.
//   fileName     for example "gemma4-e2b-q4_0.gguf"
//   displayName  the same without ".gguf" (what the screens show)
//   path         the full path (what the engine and the settings use)
//   sizeBytes    the file size
// ---------------------------------------------------------------------
data class StoredModel(
    val fileName: String,
    val displayName: String,
    val path: String,
    val sizeBytes: Long
)

// ---------------------------------------------------------------------
// Results. Exactly one of model / error is set.
//   model  the model that was imported or renamed
//   error  a plain-words problem
// ---------------------------------------------------------------------
class ModelResult(val model: StoredModel?, val error: String?)

class ModelStore(context: Context) {

    // The APPLICATION context lives as long as the app (a screen's context
    // would leak memory).
    private val appContext: Context = context.applicationContext

    // The private folder. It is created on first use. No permission needed.
    private val dir: File = File(appContext.filesDir, "models").also { it.mkdirs() }

    /** The folder's path (for showing it to the user, if wanted). */
    val folderPath: String
        get() = dir.absolutePath

    // -----------------------------------------------------------------
    // LIST / SPACE
    // -----------------------------------------------------------------

    /**
     * All models in the folder, sorted by name. ".part" files (unfinished
     * copies) are NOT listed. Never throws.
     */
    suspend fun list(): List<StoredModel> = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: return@withContext emptyList()
        files
            .filter { it.isFile && it.name.endsWith(GGUF_EXT, ignoreCase = true) }
            .map { toStored(it) }
            .sortedBy { it.fileName.lowercase(Locale.ROOT) }
    }

    /** Free space on the phone's storage (where the models live), in bytes. */
    fun freeSpaceBytes(): Long = try {
        dir.usableSpace
    } catch (e: Exception) {
        0L
    }

    /** The model with this file name, or null if it does not exist (any more). */
    fun find(fileName: String): StoredModel? {
        val f = File(dir, fileName)
        return if (f.isFile && f.name.endsWith(GGUF_EXT, ignoreCase = true)) toStored(f) else null
    }

    /**
     * Deletes unfinished copies (".part" files) that an earlier import left
     * behind (for example the app was killed during a copy). Call it when no
     * import is running, for example when the screen opens.
     */
    suspend fun cleanLeftovers() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach {
            if (it.isFile && it.name.endsWith(PART_EXT)) it.delete()
        }
    }

    // -----------------------------------------------------------------
    // IMPORT (copy a picked file into the private folder)
    // -----------------------------------------------------------------

    /**
     * Copies the file behind [uri] (from Android's file picker) into the
     * models folder.
     *
     *   onProgress  called about 4 times a second with (bytes copied, total
     *               bytes). The total is 0 when the picker did not tell the
     *               size. It runs on a background thread.
     *
     * Returns a ModelResult: the new model, or a plain-words problem.
     * To CANCEL, cancel the coroutine that calls this function: the copy
     * stops, the ".part" file is deleted and CancellationException goes up.
     */
    suspend fun importModel(
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit
    ): ModelResult = withContext(Dispatchers.IO) {

        // 1) What is the file called, and how big is it?
        val info = queryNameAndSize(uri)
        val pickedName = info.first
        val total = info.second     // -1 = unknown

        if (!pickedName.endsWith(GGUF_EXT, ignoreCase = true)) {
            return@withContext ModelResult(null, "That is not a .gguf file.")
        }

        // 2) Enough free space? (the size plus a safety margin)
        if (total > 0) {
            val needed = total + SPACE_MARGIN_BYTES
            val free = freeSpaceBytes()
            if (free < needed) {
                return@withContext ModelResult(
                    null,
                    "Not enough free space. This model needs " + formatSize(needed) +
                            ", the phone has " + formatSize(free) + " free."
                )
            }
        }

        // 3) A free name (never overwrite another model).
        val finalName = uniqueFileName(sanitizeFileName(pickedName))
        val partFile = File(dir, finalName + PART_EXT)
        val finalFile = File(dir, finalName)

        // 4) Copy into the ".part" file.
        var done = false                 // true only when everything worked
        var problem: String? = null
        try {
            val input = appContext.contentResolver.openInputStream(uri)
            if (input == null) {
                problem = "The file could not be opened."
            } else {
                input.use { source ->
                    FileOutputStream(partFile).use { target ->
                        val buffer = ByteArray(COPY_BLOCK_BYTES)
                        var copied = 0L
                        var lastReport = 0L
                        var firstBlock = true

                        while (true) {
                            // Cancelled? Then stop here (the finally block
                            // below deletes the ".part" file).
                            ensureActive()

                            val n = source.read(buffer)
                            if (n < 0) break

                            // The first block must start with the word GGUF.
                            if (firstBlock) {
                                firstBlock = false
                                val magicOk = n >= 4 &&
                                        buffer[0] == 'G'.code.toByte() &&
                                        buffer[1] == 'G'.code.toByte() &&
                                        buffer[2] == 'U'.code.toByte() &&
                                        buffer[3] == 'F'.code.toByte()
                                if (!magicOk) {
                                    problem = "This is not a valid GGUF model file."
                                    break
                                }
                            }

                            target.write(buffer, 0, n)
                            copied += n

                            // Report progress, but not more than 4 times a second.
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= REPORT_EVERY_MS) {
                                lastReport = now
                                onProgress(copied, if (total > 0) total else 0L)
                            }
                        }

                        if (problem == null) {
                            target.fd.sync()                 // really write it to the disk
                            onProgress(copied, if (total > 0) total else copied)

                            // A size that does not match means a broken copy.
                            if (total > 0 && copied != total) {
                                problem = "The copy is incomplete. Try again."
                            }
                        }
                    }
                }
            }

            // 5) The copy is complete: give it its real name.
            if (problem == null) {
                if (partFile.renameTo(finalFile)) {
                    done = true
                } else {
                    problem = "The model could not be saved."
                }
            }
        } catch (e: CancellationException) {
            throw e                       // the finally block cleans up
        } catch (e: IOException) {
            // The usual reason on a phone: no space left while copying.
            problem = if (freeSpaceBytes() < SPACE_MARGIN_BYTES) {
                "The phone ran out of free space while copying."
            } else {
                "Copying failed: " + (e.message ?: "unknown problem")
            }
        } catch (e: SecurityException) {
            problem = "Android did not allow reading this file."
        } finally {
            // Whatever happened: no half file stays behind.
            if (!done) partFile.delete()
        }

        if (done) ModelResult(toStored(finalFile), null) else ModelResult(null, problem)
    }

    // -----------------------------------------------------------------
    // RENAME / DELETE
    // -----------------------------------------------------------------

    /**
     * Gives a model a new name. [newName] may be written with or without
     * ".gguf". Returns the renamed model (its path changed!) or a problem.
     * The caller must NOT rename the model that is loaded right now.
     */
    suspend fun rename(fileName: String, newName: String): ModelResult = withContext(Dispatchers.IO) {
        val old = File(dir, fileName)
        if (!old.isFile) return@withContext ModelResult(null, "The model was not found.")

        val clean = sanitizeFileName(newName)
        if (clean.removeSuffix(GGUF_EXT).isBlank() ||
            clean.removeSuffix(GGUF_EXT).equals(".", ignoreCase = true)
        ) {
            return@withContext ModelResult(null, "Please type a name.")
        }

        // Same name (also when only the capitals differ): nothing to do.
        if (clean == old.name) return@withContext ModelResult(toStored(old), null)

        val target = File(dir, clean)
        if (target.exists() && !clean.equals(old.name, ignoreCase = true)) {
            return@withContext ModelResult(null, "A model with this name already exists.")
        }
        if (!old.renameTo(target)) {
            return@withContext ModelResult(null, "The model could not be renamed.")
        }
        ModelResult(toStored(target), null)
    }

    /**
     * Deletes a model file. Returns true when it is gone. The caller must NOT
     * delete the model that is loaded right now.
     */
    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(dir, fileName)
        !f.exists() || f.delete()
    }

    // -----------------------------------------------------------------
    // SMALL HELPERS
    // -----------------------------------------------------------------

    private fun toStored(f: File): StoredModel = StoredModel(
        fileName = f.name,
        displayName = f.name.removeSuffix(GGUF_EXT).let {
            // removeSuffix is case-sensitive: handle ".GGUF" as well.
            if (f.name.endsWith(GGUF_EXT, ignoreCase = true)) f.name.dropLast(GGUF_EXT.length) else it
        },
        path = f.absolutePath,
        sizeBytes = f.length()
    )

    // Asks the picker for the file's name and size. Both can be missing:
    // then the name comes from the Uri and the size is -1 (unknown).
    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            // Not fatal: we use the fallbacks below.
        }
        return Pair(name ?: uri.lastPathSegment ?: "model.gguf", size)
    }

    // A "this name is free" file name: "a.gguf" -> "a.gguf", or
    // "a (2).gguf", "a (3).gguf"... when it is taken (also by a ".part").
    private fun uniqueFileName(name: String): String {
        if (!File(dir, name).exists() && !File(dir, name + PART_EXT).exists()) return name
        val base = name.dropLast(GGUF_EXT.length)
        var n = 2
        while (true) {
            val candidate = "$base ($n)$GGUF_EXT"
            if (!File(dir, candidate).exists() && !File(dir, candidate + PART_EXT).exists()) return candidate
            n++
        }
    }

    companion object {
        const val GGUF_EXT = ".gguf"
        private const val PART_EXT = ".part"

        // Copy block: 1 MB at a time.
        private const val COPY_BLOCK_BYTES = 1 shl 20

        // Progress is reported at most this often.
        private const val REPORT_EVERY_MS = 250L

        // Free space kept as a margin on top of the model's size (64 MB).
        private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

        // The longest file name (without the extension) we accept.
        private const val MAX_NAME_CHARS = 80

        /**
         * Makes any text a safe model file name: only letters, digits, space,
         * dot, dash, underscore and brackets (the rest becomes "_"), no
         * leading dot, at most 80 characters, and it ends with ".gguf".
         * "/" and "\" are removed first, so a name can never point to another
         * folder.
         */
        fun sanitizeFileName(raw: String): String {
            var base = raw.trim()
            if (base.endsWith(GGUF_EXT, ignoreCase = true)) base = base.dropLast(GGUF_EXT.length)

            val sb = StringBuilder()
            for (ch in base) {
                when {
                    ch == '/' || ch == '\\' -> { /* dropped */ }
                    ch.isLetterOrDigit() || ch == ' ' || ch == '.' || ch == '-' ||
                            ch == '_' || ch == '(' || ch == ')' -> sb.append(ch)
                    else -> sb.append('_')
                }
            }
            var cleaned = sb.toString().trim().trimStart('.')
            if (cleaned.length > MAX_NAME_CHARS) cleaned = cleaned.take(MAX_NAME_CHARS).trimEnd()
            if (cleaned.isEmpty()) cleaned = "model"
            return cleaned + GGUF_EXT
        }

        /** "3.1 GB", "812 MB"... for showing a size to the user. */
        fun formatSize(bytes: Long): String {
            val gb = bytes / (1024.0 * 1024.0 * 1024.0)
            if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
            val mb = bytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
