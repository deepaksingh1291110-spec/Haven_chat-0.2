// =====================================================================
// MemoryExtractor.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4
// WHAT: Runs the WHOLE job "remember my personal things from this chat"
//       for ONE chat, from start to end:
//         1) read the chat's "last-done marker" (how far it was already read),
//         2) load the messages that are NEWER than the marker,
//         3) pair every USER message with the FINAL ANSWER Gemma gave to it,
//         4) cut the user messages into batches of up to 15,
//         5) for each batch: ask Gemma "which of these are personal?"
//            (EngineManager.classifyBatch + MemoryPrompts.kt),
//         6) copy the personal questions and their saved final answers,
//            word for word, into the memories database AND move the marker,
//            both in ONE database step (MemoryRepository.saveBatch).
//
// THE MOST IMPORTANT RULES:
//   - Gemma only DECIDES (it answers with numbers). Nothing Gemma writes is
//     ever saved. The saved question and answer are copied from the chat
//     database, so nothing can be invented or rewritten.
//   - The answer that is saved is the FINAL answer only. The thinking text
//     is stored in another column and never touched here.
//   - The marker moves ONLY after a batch was understood AND saved. If
//     anything fails (Gemma's reply is not understood, the engine reports a
//     problem, the user cancels, the app is killed), the marker stays where
//     it was, so the next run continues from there and nothing is lost.
//   - The result tells the caller clearly whether the job is COMPLETE.
//     The delete-chat flow may delete the chat ONLY when completed = true.
//
// WHICH PART OF A CHAT IS READ: everything after the marker. A chat that
//   was never read is read from its beginning. A chat that was read before
//   (or half read) is read from the first message that was not checked yet.
//   Running it twice in a row therefore finds nothing new the second time.
//
// HOW A QUESTION AND ITS ANSWER ARE PAIRED: messages are in the order they
//   were saved: user, answer, user, answer... For each user message the
//   answer is the first non-empty assistant message that follows it, before
//   the next user message. No saved answer (for example an answer that was
//   stopped before any text) = the question is saved alone.
//
// THREADS: run() is a "suspend" function: call it from a coroutine on the
//   MAIN thread (viewModelScope does that). The heavy work happens on the
//   engine's worker thread and in Room's own threads; this file only
//   waits for them.
//
// ENGINE STATE: run() starts the MEMORIZING state, keeps it for the whole
//   job and ALWAYS ends it (also after an error, a cancel or when the
//   ViewModel is destroyed), so the engine can never stay "busy" by mistake.
//
// USED BY (next steps): ChatViewModel (the typed phrase / menu item, and the
//   delete-chat flow).
// =====================================================================
package com.haven.chat.engine

import com.haven.chat.data.ChatRepository
import com.haven.chat.data.MemoryRepository
import com.haven.chat.data.NewMemory
import com.haven.chat.data.StoredMessage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

// ---------------------------------------------------------------------
// What the job reports while it runs (for a progress dialog).
//   checked  how many of YOUR messages have been checked so far
//   total    how many of your messages the job has to check in all
//   saved    how many memories were saved so far
// ---------------------------------------------------------------------
class MemoryProgress(
    val checked: Int,
    val total: Int,
    val saved: Int
)

// ---------------------------------------------------------------------
// What the job reports at the end.
//   completed  true ONLY when every message after the marker was checked
//              and everything is saved. (Nothing new to read counts as
//              completed too.) The delete flow may delete the chat only
//              when this is true.
//   cancelled  true if the user cancelled. completed is false then; what
//              was saved before the cancel stays saved.
//   saved      how many memories were added by this run (duplicates are
//              not counted).
//   error      a plain-words problem, or null. completed is false then.
// ---------------------------------------------------------------------
class MemoryJobResult(
    val completed: Boolean,
    val cancelled: Boolean,
    val saved: Int,
    val error: String?
)

class MemoryExtractor(
    private val chats: ChatRepository,
    private val memories: MemoryRepository
) {

    // One user message together with Gemma's final answer to it.
    //   message  the user's message (real database id, text, date)
    //   answer   the final answer text, or null if there is none
    //   endId    the id of the LAST message that belongs to this pair (the
    //            answer, or the question itself if it has no answer). The
    //            marker moves to this id when the pair's batch is done.
    private class Entry(
        val message: StoredMessage,
        val answer: String?,
        val endId: Long
    )

    // Set by cancel(); checked between batches.
    @Volatile private var cancelRequested = false

    /**
     * Asks the running job to stop. Safe to call any time. The batch that
     * Gemma is reading right now is interrupted; what was saved by earlier
     * batches stays saved.
     */
    fun cancel() {
        cancelRequested = true
        EngineManager.cancelMemorizing()
    }

    /**
     * Runs the job for one chat. Always returns a result; it never throws
     * for ordinary problems.
     *
     *   chatId          the chat to read
     *   memoryThinking  the "memory thinking" switch of the Settings screen
     *   onProgress      called (on the main thread) after every saved batch
     *
     * What happens to the engine when the job ends (see finishMemorizing):
     *   systemPrompt    the user's normal system prompt
     *   chatThinking    the normal thinking setting
     *   restoreHistory  gives the messages of the chat that must be in
     *                   Gemma's head afterwards, oldest first. It is called
     *                   at the END of the job on the main thread. Return an
     *                   EMPTY list when the chat is going to be deleted.
     */
    suspend fun run(
        chatId: Long,
        memoryThinking: Boolean,
        systemPrompt: String,
        chatThinking: Boolean,
        restoreHistory: () -> List<EngineManager.HistoryItem>,
        onProgress: (MemoryProgress) -> Unit
    ): MemoryJobResult {
        cancelRequested = false

        // The engine must be READY (model loaded, nothing else running).
        val problem = EngineManager.startMemorizing()
        if (problem != null) return MemoryJobResult(false, false, 0, problem)

        try {
            return runBatches(chatId, memoryThinking, onProgress)
        } finally {
            // ALWAYS give the engine back, even after an error or when the
            // coroutine itself was cancelled (NonCancellable lets this last
            // step finish anyway).
            withContext(NonCancellable) {
                finish(systemPrompt, restoreHistory(), chatThinking)
            }
        }
    }

    // -----------------------------------------------------------------
    // The batches
    // -----------------------------------------------------------------
    private suspend fun runBatches(
        chatId: Long,
        memoryThinking: Boolean,
        onProgress: (MemoryProgress) -> Unit
    ): MemoryJobResult {

        // 1) Where did the last run stop? (0 = never read)
        val marker = memories.lastMemorizedMessageId(chatId)

        // 2) The messages after the marker. null = the database could not be
        //    read. That is NOT "nothing new": report a problem, so the
        //    delete flow cannot delete the chat by mistake.
        val messages = chats.loadMessagesAfter(chatId, marker)
            ?: return fail("The chat could not be read.", 0)

        // Nothing new after the marker: the job is complete already.
        if (messages.isEmpty()) return MemoryJobResult(true, false, 0, null)

        // 3) Pair every user message with its final answer.
        val entries = pairUp(messages)

        // Only assistant messages or empty texts after the marker: there is
        // nothing to ask Gemma. Move the marker to the end, so they are not
        // looked at again.
        if (entries.isEmpty()) {
            val added = memories.saveBatch(chatId, emptyList(), messages.last().id)
            return if (added < 0) fail("The memories could not be saved.", 0)
            else MemoryJobResult(true, false, 0, null)
        }

        // 4) Batches of up to BATCH_SIZE user messages.
        val batches = entries.chunked(MemoryPrompts.BATCH_SIZE)
        var saved = 0
        var checked = 0

        for ((index, batch) in batches.withIndex()) {
            if (cancelRequested) return MemoryJobResult(false, true, saved, null)

            // 5) Ask Gemma which messages of this batch are personal.
            //    Only the user's texts are sent, never Gemma's answers.
            val batchMessage = MemoryPrompts.buildBatchMessage(batch.map { it.message.text })
            val reply = classify(batchMessage, batch.size, memoryThinking)

            if (reply.cancelled) return MemoryJobResult(false, true, saved, null)
            if (reply.error != null) return fail(reply.error, saved)

            // null = the reply was not understood, even after a second try.
            // The marker does NOT move, so nothing is lost.
            val numbers = reply.personal
                ?: return fail(
                    "Gemma's reply could not be understood. Nothing was lost; try again.",
                    saved
                )

            // 6) Copy the personal pairs word for word. Number n = the n-th
            //    message of this batch (numbers start at 1). The reader
            //    already dropped numbers outside 1..batch.size.
            val newMemories = numbers.map { n ->
                val entry = batch[n - 1]
                NewMemory(
                    question = entry.message.text,
                    answer = entry.answer,
                    questionTime = entry.message.createdAt,
                    questionMessageId = entry.message.id
                )
            }

            // The marker moves to the end of this batch. For the LAST batch
            // it moves to the very last message, so a trailing empty message
            // is not read again either.
            val markerId = if (index == batches.lastIndex) messages.last().id else batch.last().endId

            // Memories and marker in ONE database step.
            val added = memories.saveBatch(chatId, newMemories, markerId)
            if (added < 0) return fail("The memories could not be saved (storage problem?).", saved)

            saved += added
            checked += batch.size
            onProgress(MemoryProgress(checked = checked, total = entries.size, saved = saved))
        }

        return MemoryJobResult(true, false, saved, null)
    }

    // -----------------------------------------------------------------
    // Pairing: user message + the final answer that follows it
    // -----------------------------------------------------------------
    private fun pairUp(messages: List<StoredMessage>): List<Entry> {
        val entries = ArrayList<Entry>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (!m.isUser) {
                // An answer with no user message before it (the question was
                // before the marker): nothing to pair, skip it.
                i++
                continue
            }

            // Walk over the answer(s) that follow, up to the next user message.
            var j = i + 1
            var answer: String? = null
            while (j < messages.size && !messages[j].isUser) {
                if (answer == null && messages[j].text.isNotBlank()) {
                    answer = messages[j].text      // the FINAL answer, as saved
                }
                j++
            }

            // An empty user message is not worth asking about.
            if (m.text.isNotBlank()) {
                entries.add(Entry(message = m, answer = answer, endId = messages[j - 1].id))
            }
            i = j
        }
        return entries
    }

    private fun fail(text: String, saved: Int) = MemoryJobResult(false, false, saved, text)

    // -----------------------------------------------------------------
    // The engine's callbacks, turned into "suspend" functions
    // -----------------------------------------------------------------

    // Waits for the engine to finish one batch. If the coroutine is
    // cancelled meanwhile, the running batch is stopped as well.
    private suspend fun classify(
        batchMessage: String,
        batchSize: Int,
        thinking: Boolean
    ): EngineManager.BatchResult = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { EngineManager.cancelMemorizing() }
        EngineManager.classifyBatch(batchMessage, batchSize, thinking) { result ->
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
}
