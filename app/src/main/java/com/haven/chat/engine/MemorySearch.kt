// =====================================================================
// MemorySearch.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4, step 6
// WHAT: The heart of "LIGHT SEARCH". When the user asks Gemma something
//       about their own past ("what did I decide about my app database?"),
//       Gemma can call a TOOL named search_memory with a few keywords.
//       This file holds everything about that tool:
//         1) the DEFINITION of the tool (name, description, parameters) that
//            Gemma gets to read,
//         2) reading the keywords out of Gemma's call (JSON),
//         3) the keyword MATCHING over the saved memories,
//         4) the RESULT TEXT that Gemma reads back: the NUMBER of matches,
//            and the matches themselves while they fit a small token budget.
//       It is plain Kotlin text work: no database, no engine inside. The
//       memories are handed in as a list, and the token counter is handed
//       in as a function, so EngineManager / ChatViewModel do the wiring.
//
// WHY "LIGHT": no search models are loaded and Gemma stays in memory. A
//   keyword match over a few hundred short memories takes milliseconds.
//   (Heavy search with bge-m3 and a reranker is only built if this turns
//   out to be not enough.)
//
// WHAT GEMMA SEES (the user's idea: "show the number of results, and if the
//   result tokens are few, Gemma can read them"):
//     Found 7 saved memories (most relevant first):
//     [1] 19 Nov 2026
//     User said: ...
//     You answered: ...
//     ...
//     Showing the 3 most relevant of 7. 4 more matched but are not shown.
//     If these do not answer the question, call search_memory again with
//     more specific keywords.
//   The budget is counted with Gemma's own tokenizer (exact), and it is kept
//   small (see tokenBudget) so the answer still has room in the context.
//
// HOW MATCHING WORKS (simple on purpose):
//   - Gemma's keywords are cut into single lowercase words. Tiny filler
//     words ("the", "my", "user"...) are dropped.
//   - A memory matches when its question or answer CONTAINS the word
//     (so "counter" also finds "counters"). With 3 or more words, at least
//     2 different words must match (this keeps the weak matches out); with
//     1 or 2 words, 1 is enough.
//   - Ranking: a word found in the question counts 2, a word found only in
//     the answer counts 1. Higher score first, newer first on a tie.
//
// NEVER CRASHES: every function returns text or a list, also for strange
//   input (broken JSON, no keywords, no memories).
// =====================================================================
package com.haven.chat.engine

import com.haven.chat.data.MemoryEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object MemorySearch {

    // -----------------------------------------------------------------
    // 1) THE TOOL DEFINITION (what Gemma reads)
    // -----------------------------------------------------------------

    const val TOOL_NAME = "search_memory"

    // The description matters a lot for a small model: it must say WHEN to
    // use the tool and when NOT to.
    const val TOOL_DESCRIPTION =
        "Searches the personal memories saved from the user's earlier chats: " +
        "things the user told you about their own life, family, work, projects, " +
        "devices, preferences and plans. Use it ONLY when the user asks about their " +
        "own past or personal facts (for example \"what did I decide about...\", " +
        "\"remind me...\", \"what is my...\"). Do NOT use it for general knowledge " +
        "questions or for normal conversation. Give 2 to 5 short keywords."

    // The parameters, as a JSON schema (the format the chat template wants).
    const val TOOL_PARAMETERS_JSON =
        """{"type":"object","properties":{"keywords":{"type":"array","items":{"type":"string"},"description":"2 to 5 short keywords, single words are best, that would appear in the saved memory"}},"required":["keywords"]}"""

    // -----------------------------------------------------------------
    // NUMBERS
    // -----------------------------------------------------------------

    // The most tokens one result may use, and the part of the context window
    // it may use at most (1/6), so a small context window still has room for
    // the answer.
    private const val MAX_RESULT_TOKENS = 700
    private const val MIN_RESULT_TOKENS = 150

    // Room kept for the closing lines (count, "call again..." hint).
    private const val FOOTER_RESERVE_TOKENS = 120

    // Every entry costs a few extra tokens for line breaks and markers.
    private const val PER_ENTRY_OVERHEAD = 8

    // One memory is cut to these lengths when it is shown to Gemma. (The
    // saved memory itself is never changed.)
    private const val MAX_QUESTION_CHARS = 300
    private const val MAX_ANSWER_CHARS = 600

    // Keywords: how many words at most, and the shortest word we keep.
    private const val MAX_WORDS = 12
    private const val MIN_WORD_LENGTH = 2

    // Tiny filler words that would match almost every memory.
    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "my", "me", "i", "is", "are", "was", "were", "to",
        "in", "on", "for", "and", "or", "did", "do", "does", "what", "when", "how",
        "user", "memory", "memories", "saved", "about", "with", "that", "this"
    )

    // Anything that is not a letter, a mark (for example a Hindi vowel sign)
    // or a digit separates two words.
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    // The date shown in front of a memory, for example "19 Nov 2026".
    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /**
     * How many tokens a search result may use, for a model that was loaded
     * with [contextSize] tokens of context: at most 700, at most 1/6 of the
     * context, at least 150.
     */
    fun tokenBudget(contextSize: Int): Int =
        minOf(MAX_RESULT_TOKENS, contextSize / 6).coerceAtLeast(MIN_RESULT_TOKENS)

    // -----------------------------------------------------------------
    // 2) READING GEMMA'S CALL
    // -----------------------------------------------------------------

    /**
     * Reads the keywords out of the arguments of Gemma's tool call, for
     * example {"keywords": ["counter", "timing error"]}, and returns single
     * lowercase words: ["counter", "timing", "error"].
     * Forgiving: a plain text instead of an array, other key names
     * ("query", "topic"...) and even text that is not JSON all work.
     * Returns an EMPTY list when no usable word is left.
     */
    fun parseKeywords(argsJson: String): List<String> {
        val raw = ArrayList<String>()
        try {
            val obj = JSONObject(argsJson)
            for (key in listOf("keywords", "keyword", "query", "search_topic", "topic")) {
                val value = obj.opt(key) ?: continue
                if (value == JSONObject.NULL) continue
                if (value is JSONArray) {
                    for (i in 0 until value.length()) raw.add(value.optString(i, ""))
                } else {
                    raw.add(value.toString())
                }
            }
        } catch (e: JSONException) {
            raw.add(argsJson)      // not JSON: use the whole text
        }

        return raw
            .flatMap { it.lowercase().split(WORD_SPLIT) }
            .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }
            .distinct()
            .take(MAX_WORDS)
    }

    // -----------------------------------------------------------------
    // 3) MATCHING
    // -----------------------------------------------------------------

    /** One matching memory and its score (higher = more relevant). */
    class Hit(val memory: MemoryEntity, val score: Int)

    /**
     * Finds the memories that match the [words] and returns them best first.
     * See the notes at the top for the matching and ranking rules.
     */
    fun search(all: List<MemoryEntity>, words: List<String>): List<Hit> {
        if (words.isEmpty()) return emptyList()

        // With 3 or more words, 2 different words must match.
        val needed = if (words.size >= 3) 2 else 1

        val hits = ArrayList<Hit>()
        for (memory in all) {
            val question = memory.question.lowercase()
            val answer = (memory.answer ?: "").lowercase()

            var distinct = 0
            var score = 0
            for (word in words) {
                if (question.contains(word)) {
                    distinct++
                    score += 2                       // found in the question
                } else if (answer.contains(word)) {
                    distinct++
                    score += 1                       // found only in the answer
                }
            }
            if (distinct >= needed) hits.add(Hit(memory, score))
        }

        // Best score first; on a tie the newer memory first.
        return hits.sortedWith(
            compareByDescending<Hit> { it.score }.thenByDescending { it.memory.createdAt }
        )
    }

    // -----------------------------------------------------------------
    // 4) THE RESULT TEXT (what Gemma reads back)
    // -----------------------------------------------------------------

    /**
     * Builds the text that is given back to Gemma as the tool result.
     *   hits         the matches, best first (from search())
     *   words        the keywords that were used (shown when nothing matched)
     *   tokenBudget  how many tokens the text may use (see tokenBudget())
     *   countTokens  counts the tokens of a text with Gemma's own tokenizer
     *
     * It always says HOW MANY memories matched. It then adds the memories,
     * best first, while they fit the budget (at least the first one is
     * always shown). If some are left out, it says how many and tells Gemma
     * it may search again with narrower keywords.
     */
    fun formatResult(
        hits: List<Hit>,
        words: List<String>,
        tokenBudget: Int,
        countTokens: (String) -> Int
    ): String {
        val total = hits.size

        // Nothing matched.
        if (total == 0) {
            return "No saved memory matched the keywords (" + words.joinToString(", ") + "). " +
                    "Tell the user you do not have this saved, or call search_memory again " +
                    "with different keywords."
        }

        val header = if (total == 1) "Found 1 saved memory:\n"
                     else "Found $total saved memories (most relevant first):\n"

        var used = countTokens(header) + FOOTER_RESERVE_TOKENS
        val body = StringBuilder()
        var shown = 0

        for ((index, hit) in hits.withIndex()) {
            val entry = entryText(index + 1, hit.memory)
            val cost = countTokens(entry) + PER_ENTRY_OVERHEAD
            // Stop when the next one does not fit (the first one is always shown).
            if (shown > 0 && used + cost > tokenBudget) break
            body.append(entry).append('\n')
            used += cost
            shown++
        }

        val footer = StringBuilder()
        if (shown < total) {
            footer.append("Showing the ").append(shown).append(" most relevant of ").append(total)
                .append(". ").append(total - shown)
                .append(" more matched but are not shown. If these do not answer the question, ")
                .append("call search_memory again with more specific keywords.\n")
        } else if (total > 1) {
            footer.append("That is all ").append(total).append(".\n")
        }
        footer.append("Answer using only these memories. ")
            .append("If they do not contain the answer, tell the user you do not have it saved.")

        return header + body + footer
    }

    /**
     * The whole job in one call: reads the keywords, searches, builds the
     * result text. [allMemories] is null when the database could not be read.
     */
    fun run(
        allMemories: List<MemoryEntity>?,
        argsJson: String,
        tokenBudget: Int,
        countTokens: (String) -> Int
    ): String {
        if (allMemories == null) {
            return "The saved memories could not be read right now. " +
                    "Tell the user you could not look."
        }
        val words = parseKeywords(argsJson)
        if (words.isEmpty()) {
            return "No usable keywords were given. " +
                    "Call search_memory again with 2 to 5 short keywords."
        }
        return formatResult(search(allMemories, words), words, tokenBudget, countTokens)
    }

    // -----------------------------------------------------------------
    // SMALL HELPERS
    // -----------------------------------------------------------------

    // One memory as Gemma reads it:
    //   [1] 19 Nov 2026
    //   User said: ...
    //   You answered: ...
    private fun entryText(number: Int, memory: MemoryEntity): String {
        val out = StringBuilder()
        out.append('[').append(number).append("] ").append(dateText(memory.createdAt)).append('\n')
        out.append("User said: ").append(cut(memory.question, MAX_QUESTION_CHARS)).append('\n')
        val answer = memory.answer
        if (!answer.isNullOrBlank()) {
            out.append("You answered: ").append(cut(answer, MAX_ANSWER_CHARS)).append('\n')
        }
        return out.toString()
    }

    // Cuts a text to [max] characters and adds "..." when something was cut.
    private fun cut(text: String, max: Int): String {
        val t = text.trim()
        return if (t.length > max) t.take(max).trimEnd() + "..." else t
    }

    // "milliseconds since 1970" as text, in the phone's time zone.
    private fun dateText(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)
}
