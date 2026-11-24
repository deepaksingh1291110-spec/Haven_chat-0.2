// =====================================================================
// MemoryIndexer.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4, step 7
// WHAT: Makes the SEARCH VECTORS of the memories, for heavy search.
//       A vector is a list of numbers (1024 for bge-m3) that stands for the
//       MEANING of a text. Heavy search later compares the vector of the
//       user's question with the vectors of the memories to find the
//       closest ones.
//
// WHAT ONE RUN DOES:
//   1) asks the database which memories still have NO vector from the
//      current embedding model (MemoryRepository.memoriesToEmbed). Only
//      memories that HAVE tags are listed (old memories from before step 7
//      are skipped on purpose). A memory whose text was edited on the
//      Privacy screen has lost its vector, so it is listed again.
//   2) if there is nothing to do, it stops here: no model is swapped, the
//      user notices nothing.
//   3) otherwise it makes the vectors with the embedder:
//        Gemma is unloaded, the embedder (bge-m3) is loaded, every text is
//        embedded, the embedder is unloaded and Gemma is loaded AGAIN.
//        (EngineManager.embedTexts does that swap.)
//   4) saves the vectors (MemoryRepository.saveVectors).
//
// WHAT TEXT IS EMBEDDED for a memory: the user's question, then its tags:
//        I decided to use Room instead of Firebase for my app.
//        Tags: room, firebase, database, app, decision
//   The ANSWER is not embedded: it is Gemma's text, can be long, and would
//   dilute the meaning of what the user said. (The answer is still shown to
//   Gemma when a memory is found.) If tests show that including the answer
//   works better, only textToEmbed() has to change; vectors made with the
//   old text can be rebuilt by deleting them.
//
// ONLY NEW WORK IS DONE: a memory that already has a vector for this
//   embedding model is never embedded again. One run makes at most
//   MAX_PER_RUN vectors, so a model swap never takes too long; the rest
//   are made by the next run.
//
// THE EMBEDDING MODEL'S NAME is the file name of the embedder without
//   ".gguf" (for example "bge-m3-q8_0"). It is saved with every vector. If
//   the user chooses another embedder file, its name is different, so the
//   old vectors are never compared with the new model's vectors; all
//   memories then count as "without vector" again.
//
// ENGINE STATE: like MemoryExtractor, run() starts the MEMORIZING state, keeps
//   it for the job and ALWAYS ends it (also after an error, a cancel or when
//   the ViewModel is destroyed).
//
// THREADS: run() is a "suspend" function: call it from a coroutine on the
//   MAIN thread (viewModelScope does that).
//
// USED BY (next step): ChatViewModel, right after the memory extraction, when
//   heavy search is ON and the embedder file is chosen.
// =====================================================================
package com.haven.chat.engine

import com.haven.chat.data.MemoryEntity
import com.haven.chat.data.MemoryRepository
import com.haven.chat.data.StoredVector
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

// ---------------------------------------------------------------------
// What the run reports at the end.
//   made       how many vectors were made and saved
//   cancelled  true if the user cancelled (nothing was saved by this run)
//   error      a plain-words problem, or null
// "Nothing to do" is made = 0, cancelled = false, error = null.
// ---------------------------------------------------------------------
class IndexResult(
    val made: Int,
    val cancelled: Boolean,
    val error: String?
)

class MemoryIndexer(
    private val memories: MemoryRepository
) {

    // Set by cancel().
    @Volatile private var cancelRequested = false

    /**
     * Asks the running job to stop. Safe to call any time. The embedding in
     * progress is interrupted and Gemma is loaded again; nothing of this run
     * is saved (the memories stay "without vector" and are done next time).
     */
    fun cancel() {
        cancelRequested = true
        EngineManager.cancelMemorizing()
    }

    /**
     * Makes the missing vectors. Always returns a result; it never throws for
     * ordinary problems.
     *
     *   embedderPath    the bge-m3 GGUF file (from Settings)
     *   onProgress      called on the main thread: (vectors done, vectors to make)
     *
     * What happens to the engine when the job ends (see finishMemorizing):
     *   systemPrompt    the user's normal system prompt
     *   chatThinking    the normal thinking setting
     *   restoreHistory  gives the messages of the chat that must be in Gemma's
     *                   head afterwards, oldest first (called at the END, on
     *                   the main thread). EMPTY list = start a fresh chat.
     */
    suspend fun run(
        embedderPath: String,
        systemPrompt: String,
        chatThinking: Boolean,
        restoreHistory: () -> List<EngineManager.HistoryItem>,
        onProgress: (done: Int, total: Int) -> Unit
    ): IndexResult {
        cancelRequested = false

        // The file must exist, otherwise the swap would unload Gemma for nothing.
        if (embedderPath.isBlank() || !File(embedderPath).exists()) {
            return IndexResult(0, false, "The embedder file was not found. Choose it in Settings.")
        }

        // 1) Which memories need a vector? (null = the database could not be read)
        val model = embeddingModelName(embedderPath)
        val needed = memories.memoriesToEmbed(model)
            ?: return IndexResult(0, false, "The memories could not be read.")

        // 2) Nothing to do: no model swap at all.
        if (needed.isEmpty()) return IndexResult(0, false, null)

        // 3) The engine must be READY (model loaded, nothing else running).
        val problem = EngineManager.startMemorizing()
        if (problem != null) return IndexResult(0, false, problem)

        try {
            return embedAndSave(needed.take(MAX_PER_RUN), embedderPath, model, onProgress)
        } finally {
            // ALWAYS give the engine back (also after an error, a cancel, or
            // when the coroutine itself was cancelled).
            withContext(NonCancellable) {
                finish(systemPrompt, restoreHistory(), chatThinking)
            }
        }
    }

    // -----------------------------------------------------------------
    // The work
    // -----------------------------------------------------------------
    private suspend fun embedAndSave(
        batch: List<MemoryEntity>,
        embedderPath: String,
        model: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): IndexResult {
        val texts = batch.map { textToEmbed(it) }

        // Gemma out, embedder in, embed everything, embedder out, Gemma in.
        val result = embed(embedderPath, texts) { done -> onProgress(done, texts.size) }

        if (result.cancelled || cancelRequested) return IndexResult(0, true, null)

        val vectors = result.vectors
            ?: return IndexResult(0, false, result.error ?: "The search vectors could not be made.")
        if (vectors.size != batch.size) {
            return IndexResult(0, false, "The search vectors do not match the memories.")
        }

        // Save all vectors in one step (all or nothing).
        val stored = batch.indices.map { i -> StoredVector(batch[i].id, vectors[i]) }
        val saved = memories.saveVectors(model, stored)
        return if (saved) IndexResult(vectors.size, false, null)
        else IndexResult(0, false, "The search vectors could not be saved (storage problem?).")
    }

    // -----------------------------------------------------------------
    // The engine's callbacks, turned into "suspend" functions
    // -----------------------------------------------------------------

    // Waits for the engine to embed all texts. If the coroutine is cancelled
    // meanwhile, the job is stopped as well.
    private suspend fun embed(
        embedderPath: String,
        texts: List<String>,
        onEach: (Int) -> Unit
    ): EngineManager.EmbedResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { EngineManager.cancelMemorizing() }
        EngineManager.embedTexts(embedderPath, texts, onEach) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    // Waits for the engine to put the chat back and become READY again.
    private suspend fun finish(
        systemPrompt: String,
        history: List<EngineManager.HistoryItem>,
        thinking: Boolean
    ) = suspendCancellableCoroutine<Unit> { cont ->
        EngineManager.finishMemorizing(systemPrompt, history, thinking) {
            if (cont.isActive) cont.resume(Unit)
        }
    }

    companion object {
        // At most this many vectors per run, so a model swap stays short.
        // The rest are made by the next run.
        const val MAX_PER_RUN = 60

        // The longest question text that is embedded (characters). The
        // embedding model reads at most 2048 tokens anyway; a very long
        // pasted text would only slow it down.
        private const val MAX_QUESTION_CHARS = 800

        /** The embedding model's name: the file name without ".gguf". */
        fun embeddingModelName(embedderPath: String): String =
            File(embedderPath).nameWithoutExtension

        /**
         * The text that is embedded for a memory: the question, a new line,
         * then "Tags: a, b, c". Public, because deep search must embed the
         * user's QUESTION in a way that fits (it uses the question alone,
         * but the memory side is made here).
         */
        fun textToEmbed(memory: MemoryEntity): String {
            val question = memory.question.trim().let {
                if (it.length > MAX_QUESTION_CHARS) it.take(MAX_QUESTION_CHARS).trimEnd() + "..." else it
            }
            val tags = memory.tags.trim()
            return if (tags.isEmpty()) question else question + "\nTags: " + tags
        }
    }
}
