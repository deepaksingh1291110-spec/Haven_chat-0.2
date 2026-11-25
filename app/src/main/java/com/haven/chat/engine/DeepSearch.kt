// =====================================================================
// DeepSearch.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4, step 7
// WHAT: HEAVY SEARCH: the tool "deep_search_memory" that Gemma can call when
//       the quick keyword search (search_memory) finds nothing, finds too
//       much, or the question is vague. It understands MEANING, not only
//       words: "what database did I pick?" can find a memory that says
//       "I decided to use Room instead of Firebase".
//
// THE PIPELINE (the user's design, "Phase 3 retrieval" of the memory
//   architecture), done inside ONE tool call:
//     1) Gemma calls the tool with a short query in plain words.
//     2) Gemma is UNLOADED and the EMBEDDER (bge-m3) is loaded
//        (EngineManager.SearchSwap does the swapping).
//     3) The query is embedded: one vector of 1024 numbers.
//     4) The vector is compared with the saved vector of every memory
//        (plain arithmetic: a few thousand memories take milliseconds).
//        The CANDIDATES (50) closest memories are kept.
//     5) The embedder is replaced by the RERANKER. It reads the query and
//        each candidate TOGETHER and gives an exact relevance score. This is
//        the slow part (one pass per candidate).
//     6) The best TOP_RESULTS (3) memories are written as the result text
//        (same layout as the quick search) and given back to Gemma.
//     7) EngineManager frees the reranker and loads Gemma AGAIN; Gemma reads
//        the conversation again and writes the answer.
//   Nothing in the database is changed by a search.
//
// WHEN IT WORKS: only for memories that HAVE a vector (MemoryIndexer makes
//   them, using the same embedding model that is chosen now). If there are
//   none yet, Gemma is told so plainly.
//
// WHEN SOMETHING IS MISSING it still tries to help:
//   - reranker file missing or not loadable: the closest memories by vector
//     alone are used (still good, less exact);
//   - the reranking takes too long (RERANK_TIME_LIMIT_MS): it stops and
//     uses the scores it has;
//   - an embedder problem: Gemma is told the deep search is not available
//     and may use search_memory instead.
//
// TRIAL AND ERROR: the numbers that decide quality and speed are constants
//   just below (how many candidates, how many results, the time limit).
//
// THREADS: run() is called ON THE ENGINE'S WORKER THREAD (inside the tool
//   call). It blocks while the models work; that is fine there. It never
//   touches the screen. Database reads use runBlocking, which is also fine
//   on that thread.
//
// NEVER CRASHES: every problem becomes a plain-words text for Gemma.
// =====================================================================
package com.haven.chat.engine

import com.haven.chat.data.MemoryEntity
import com.haven.chat.data.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

class DeepSearch(
    private val memories: MemoryRepository,
    private val embedderPath: String,
    private val rerankerPath: String
) {

    /**
     * The tool as the engine wants it. Hand this to EngineManager.generate
     * (the "tools" list) next to the quick search tool.
     */
    fun toolRunner(): EngineManager.ToolRunner = EngineManager.ToolRunner(
        name = TOOL_NAME,
        description = TOOL_DESCRIPTION,
        parametersJson = TOOL_PARAMETERS_JSON,
        runDeep = { argsJson, tokenBudget, countTokens, swap ->
            run(argsJson, tokenBudget, countTokens, swap)
        }
    )

    /**
     * One deep search. Returns the text that Gemma reads. Called by the
     * engine on its worker thread, with the swap handle.
     */
    fun run(
        argsJson: String,
        tokenBudget: Int,
        countTokens: (String) -> Int,
        swap: EngineManager.SearchSwap
    ): String {
        // ---- the query ----
        val query = parseQuery(argsJson)
        if (query.isBlank()) {
            return "No query was given. Call $TOOL_NAME again with a short query in plain words."
        }

        // ---- the saved vectors (of the CURRENT embedding model) ----
        val model = MemoryIndexer.embeddingModelName(embedderPath)
        val stored = runBlocking { memories.loadVectors(model) }
            ?: return "The saved memories could not be read right now. Tell the user you could not look."
        if (stored.isEmpty()) {
            return "The deep search has no search vectors yet, so it cannot be used now. " +
                    "Use ${MemorySearch.TOOL_NAME} instead."
        }

        // ---- 1) the embedder: embed the query ----
        val embedderProblem = swap.useEmbedder(embedderPath)
        if (embedderProblem != null) {
            return "The deep search is not available: $embedderProblem " +
                    "Use ${MemorySearch.TOOL_NAME} instead."
        }
        val queryVector = swap.embed(query)
            ?: return "The deep search could not read the query. Use ${MemorySearch.TOOL_NAME} instead."

        // ---- 2) the closest memories by vector ----
        val closest = closestByVector(queryVector, stored.map { it.memoryId to it.vector })
        if (closest.isEmpty()) {
            return "No saved memory seems related to the query. " +
                    "Tell the user you do not have this saved."
        }
        val candidates = runBlocking { memories.memoriesByIds(closest) }
            ?: return "The saved memories could not be read right now. Tell the user you could not look."
        if (candidates.isEmpty()) {
            return "No saved memory seems related to the query. " +
                    "Tell the user you do not have this saved."
        }

        // ---- 3) the reranker: score every candidate against the query ----
        val ranked = rerank(query, candidates, swap)

        // ---- 4) the result text ----
        return formatResult(ranked, tokenBudget, countTokens)
    }

    // -----------------------------------------------------------------
    // Step 2: the closest vectors
    // -----------------------------------------------------------------

    // Compares the query vector with every saved vector and returns the memory
    // ids of the CANDIDATES closest ones, closest first. Vectors are
    // normalized (length 1), so the "dot product" IS the cosine similarity:
    // 1.0 = same meaning, 0 = unrelated. A vector of another size (made by a
    // different model) is skipped.
    private fun closestByVector(
        query: FloatArray,
        stored: List<Pair<Long, FloatArray>>
    ): List<Long> {
        val scored = ArrayList<Pair<Long, Float>>(stored.size)
        for ((id, vector) in stored) {
            if (vector.size != query.size) continue
            var dot = 0f
            for (i in query.indices) dot += query[i] * vector[i]
            scored.add(id to dot)
        }
        return scored
            .sortedByDescending { it.second }
            .take(CANDIDATES)
            .map { it.first }
    }

    // -----------------------------------------------------------------
    // Step 3: the reranker
    // -----------------------------------------------------------------

    // Returns the candidates in their final order, best first, at most
    // TOP_RESULTS of them.
    //   - With a working reranker: sorted by its score.
    //   - Without (file missing, cannot load, or no pair could be scored):
    //     the order from the vector search is kept (already best first).
    private fun rerank(
        query: String,
        candidates: List<MemoryEntity>,
        swap: EngineManager.SearchSwap
    ): List<MemoryEntity> {
        if (rerankerPath.isBlank() || !File(rerankerPath).exists()) {
            return candidates.take(TOP_RESULTS)
        }
        if (swap.useReranker(rerankerPath) != null) {
            return candidates.take(TOP_RESULTS)
        }

        val started = System.nanoTime()
        val scored = ArrayList<Pair<MemoryEntity, Float>>()
        for (memory in candidates) {
            // Stop early when it takes too long: the candidates come best
            // first, so the ones read so far are the most promising ones.
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            if (scored.isNotEmpty() && elapsedMs > RERANK_TIME_LIMIT_MS) break

            val score = swap.rerank(query, rerankText(memory)) ?: continue
            scored.add(memory to score)
        }

        if (scored.isEmpty()) return candidates.take(TOP_RESULTS)
        return scored
            .sortedByDescending { it.second }
            .take(TOP_RESULTS)
            .map { it.first }
    }

    // What the reranker reads for one memory: the question, the tags, and the
    // beginning of the answer.
    private fun rerankText(memory: MemoryEntity): String {
        val out = StringBuilder(memory.question.trim().take(RERANK_QUESTION_CHARS))
        if (memory.tags.isNotBlank()) out.append("\nTags: ").append(memory.tags.trim())
        val answer = memory.answer
        if (!answer.isNullOrBlank()) out.append("\n").append(answer.trim().take(RERANK_ANSWER_CHARS))
        return out.toString()
    }

    // -----------------------------------------------------------------
    // Step 4: the result text
    // -----------------------------------------------------------------

    // The best memories as Gemma reads them (same layout as the quick
    // search), while they fit the token budget (the first one always shows).
    private fun formatResult(
        best: List<MemoryEntity>,
        tokenBudget: Int,
        countTokens: (String) -> Int
    ): String {
        val header = if (best.size == 1) "The deep search found 1 saved memory:\n"
                     else "The deep search found the ${best.size} most relevant saved memories (best first):\n"

        var used = countTokens(header) + FOOTER_RESERVE_TOKENS
        val body = StringBuilder()
        var shown = 0
        for ((index, memory) in best.withIndex()) {
            val entry = MemorySearch.entryText(index + 1, memory)
            val cost = countTokens(entry) + PER_ENTRY_OVERHEAD
            if (shown > 0 && used + cost > tokenBudget) break
            body.append(entry).append('\n')
            used += cost
            shown++
        }

        return header + body +
                "Answer using only these memories. " +
                "If they do not contain the answer, tell the user you do not have it saved."
    }

    // -----------------------------------------------------------------
    // Reading Gemma's call
    // -----------------------------------------------------------------

    // The query out of {"query": "..."}. Forgiving: other key names work,
    // a list of keywords is joined into one sentence, and text that is not
    // JSON is used as it is.
    private fun parseQuery(argsJson: String): String {
        return try {
            val obj = JSONObject(argsJson)
            for (key in listOf("query", "q", "question", "search_topic", "topic", "keywords")) {
                val value = obj.opt(key) ?: continue
                if (value == JSONObject.NULL) continue
                if (value is JSONArray) {
                    val words = ArrayList<String>()
                    for (i in 0 until value.length()) words.add(value.optString(i, ""))
                    val joined = words.joinToString(" ").trim()
                    if (joined.isNotEmpty()) return joined
                } else {
                    val text = value.toString().trim()
                    if (text.isNotEmpty()) return text
                }
            }
            ""
        } catch (e: JSONException) {
            argsJson.trim()
        }
    }

    companion object {
        // The name Gemma calls (the quick search names it in its hints).
        const val TOOL_NAME = MemorySearch.DEEP_TOOL_NAME

        // The description matters a lot for a small model: when to use it,
        // and when NOT to.
        const val TOOL_DESCRIPTION =
            "A slower, deeper search of the personal memories saved from the user's earlier " +
            "chats. It understands meaning, not only words. Use it when the user asks about " +
            "their own past or personal facts and search_memory found nothing or too much, " +
            "or when the question is vague. Do NOT use it for general knowledge questions " +
            "or normal conversation. Give a short query in plain words."

        const val TOOL_PARAMETERS_JSON =
            """{"type":"object","properties":{"query":{"type":"string","description":"what to look for, as a short phrase or sentence in plain words"}},"required":["query"]}"""

        // ---- numbers to tune by trial and error ----

        // How many memories the vector search hands to the reranker.
        const val CANDIDATES = 50

        // How many memories (best first) Gemma gets back at most.
        const val TOP_RESULTS = 3

        // The reranker stops after this many milliseconds and uses the scores
        // it has. (50 candidates can take a while on a phone CPU.)
        const val RERANK_TIME_LIMIT_MS = 90_000L

        // How much of a memory the reranker reads.
        private const val RERANK_QUESTION_CHARS = 400
        private const val RERANK_ANSWER_CHARS = 300

        // Same token bookkeeping as the quick search.
        private const val FOOTER_RESERVE_TOKENS = 80
        private const val PER_ENTRY_OVERHEAD = 8

        /**
         * Can the deep search be offered to Gemma? Both model files must
         * exist. (The vectors are checked separately, by counting them.)
         */
        fun filesReady(embedderPath: String, rerankerPath: String): Boolean =
            embedderPath.isNotBlank() && File(embedderPath).exists() &&
                    rerankerPath.isNotBlank() && File(rerankerPath).exists()
    }
}
