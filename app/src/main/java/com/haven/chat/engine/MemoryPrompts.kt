// =====================================================================
// MemoryPrompts.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4
// WHAT: Everything TEXT-related for the job "which of the user's messages
//       are PERSONAL?". It has three parts:
//         1) EXTRACTION_SYSTEM_PROMPT  the classifier prompt, word for word
//                                      the one the user tested (100% with
//                                      thinking, 90% without).
//         2) buildBatchMessage()       turns up to 15 user messages into the
//                                      numbered list that Gemma reads.
//         3) parsePersonalNumbers()    reads Gemma's reply (JSON like
//                                      {"personal": [2, 4]}) in a FORGIVING
//                                      way and returns the numbers.
//       It also holds the batch size and the token limits for this job.
//
// WHY ITS OWN FILE: the prompt and the reader are plain text work with no
//   Android and no engine inside. They are easy to read, to change and to
//   check, and EngineManager / MemoryExtractor just call them.
//
// HOW ONE BATCH WORKS (done by later files, this file only prepares text):
//   1) The engine conversation is reset and gets EXTRACTION_SYSTEM_PROMPT
//      as its system prompt (the user's CHAT prompt is NOT used, because
//      the system prompt matters a lot for the result).
//   2) The user message sent to Gemma is buildBatchMessage(...): only the
//      USER's messages, numbered 1, 2, 3... Gemma's old answers are never
//      sent.
//   3) Gemma answers {"personal": [numbers]}. parsePersonalNumbers()
//      reads it. Number 3 means "the 3rd message of THIS batch".
//   4) The app maps the numbers back to the real saved messages and copies
//      the question and the saved final answer word for word.
//
// IMPORTANT: Gemma only DECIDES. Nothing Gemma writes is ever saved as a
//   memory. The texts that are saved come from the database.
// =====================================================================
package com.haven.chat.engine

object MemoryPrompts {

    // -----------------------------------------------------------------
    // NUMBERS FOR THE JOB
    // -----------------------------------------------------------------

    // How many user messages go to Gemma in ONE batch. A long chat is
    // processed in several batches; the marker is saved after each batch,
    // so a cancel or a crash loses at most one batch.
    const val BATCH_SIZE = 15

    // Only for what GEMMA READS: a very long message (for example a pasted
    // file) is cut to this many characters and gets "..." at the end. This
    // keeps a batch short and fast. It does NOT change what is saved: the
    // saved question is always the full original text.
    private const val MAX_MESSAGE_CHARS_FOR_GEMMA = 500

    // Longest reply we allow (in tokens). With thinking ON, 15 messages
    // used about 580 tokens in the user's test, so 2000 leaves room for
    // longer thinking. With thinking OFF the reply is just the JSON
    // (about 40 tokens). The engine cuts both numbers down by itself if the
    // context window is too small.
    private const val MAX_NEW_TOKENS_WITH_THINKING = 2000
    private const val MAX_NEW_TOKENS_WITHOUT_THINKING = 120

    /** Token limit for one batch, depending on the "memory thinking" switch. */
    fun maxNewTokens(thinking: Boolean): Int =
        if (thinking) MAX_NEW_TOKENS_WITH_THINKING else MAX_NEW_TOKENS_WITHOUT_THINKING

    // -----------------------------------------------------------------
    // 1) THE SYSTEM PROMPT (the user's tested prompt, word for word)
    // -----------------------------------------------------------------
    // A Kotlin "raw string" (three quotes) keeps line breaks and quote
    // marks exactly as written. trimIndent() removes the spaces that only
    // exist because the text is indented inside this file.
    // Do NOT put a dollar sign in this text: Kotlin would treat it as a
    // variable.
    val EXTRACTION_SYSTEM_PROMPT: String = """
        You are an intent and personal-context classifier. Your task is to read numbered user messages and determine which ones reveal real, personal facts about the speaker.

        CORE DECISION TEST (Apply to every message):
        Ask yourself: "Does this message describe a specific, real-world event, condition, item, relationship, or plan belonging to the person speaking—OR is it asking about a general rule, theory, or hypothetical situation?"

        1. MARK AS PERSONAL IF:
        - The speaker states or implies a real-world fact about their own life, family, health, work, education, location, devices, projects, habits, or future plans.
        - This applies EVEN IF the speaker uses broken English, drops pronouns like "I" or "my" (e.g., "bought new phone...", "brother has fever...", "moving next week..."), or combines the personal fact with a complex technical or general question.

        2. MARK AS GENERAL IF:
        - The message asks about universal facts, how things work, troubleshooting theory, or general advice without anchoring the problem to the speaker's own life.
        - The message uses abstract, plural, or hypothetical framing (e.g., "why do some devices...", "when a person...", "suppose someone...", "if a developer..."). Even if the topic sounds relatable, abstract framing means no personal fact was revealed.

        3. ISOLATION RULE:
        - Evaluate each numbered message completely independently. Do not let the topic of one message influence how you classify another.

        OUTPUT FORMAT:
        - Return ONLY raw, valid JSON matching this exact structure: {"personal": [numbers]}
        - Do NOT include markdown formatting (no ```json), explanations, or extra text.
        - If none of the messages are personal, return: {"personal": []}
    """.trimIndent()

    // -----------------------------------------------------------------
    // 2) THE BATCH MESSAGE (what Gemma reads as the "user" message)
    // -----------------------------------------------------------------

    /**
     * Turns the user's messages of ONE batch into a numbered list:
     *     1. first message
     *     2. second message
     *     ...
     * The numbers start at 1 and match the order of [texts]. Each message is
     * put on ONE line (line breaks become spaces) so the numbering stays
     * clear, and a very long message is cut (see MAX_MESSAGE_CHARS_FOR_GEMMA).
     * Only what Gemma reads is changed; the saved texts stay as they were.
     */
    fun buildBatchMessage(texts: List<String>): String {
        val out = StringBuilder()
        for ((index, raw) in texts.withIndex()) {
            // One line: any run of spaces and line breaks becomes one space.
            var oneLine = raw.trim().replace(Regex("\\s+"), " ")
            if (oneLine.length > MAX_MESSAGE_CHARS_FOR_GEMMA) {
                oneLine = oneLine.take(MAX_MESSAGE_CHARS_FOR_GEMMA).trimEnd() + "..."
            }
            if (index > 0) out.append('\n')
            out.append(index + 1).append(". ").append(oneLine)
        }
        return out.toString()
    }

    // -----------------------------------------------------------------
    // 3) THE FORGIVING REPLY READER
    // -----------------------------------------------------------------

    /**
     * Reads Gemma's reply and returns the numbers that are PERSONAL.
     *
     *   reply      Gemma's FINAL answer text (never the thinking text)
     *   batchSize  how many messages the batch had (valid numbers: 1..batchSize)
     *
     * Result:
     *   a list (may be EMPTY)  the reply was understood. An empty list means
     *                          "none of the messages is personal" and is a
     *                          valid answer.
     *   null                   the reply could NOT be understood (no
     *                          "personal" word, no brackets, cut off in the
     *                          middle...). The caller then tries ONE more
     *                          time, and if it fails again it does NOT move
     *                          the marker, so nothing is lost.
     *
     * FORGIVING means: we do not need perfect JSON. A small model may wrap
     * the answer in ```json fences, add a sentence before it, or use odd
     * spaces. So we simply look for the word "personal", then the first "["
     * after it and the next "]", and pick the whole numbers in between.
     * Numbers outside 1..batchSize and repeated numbers are dropped.
     * The result is sorted (smallest first).
     */
    fun parsePersonalNumbers(reply: String, batchSize: Int): List<Int>? {
        // The key word. Case does not matter ("Personal" is fine too).
        val keyAt = reply.indexOf("personal", ignoreCase = true)
        if (keyAt < 0) return null

        // The list brackets after the key word.
        val open = reply.indexOf('[', keyAt)
        if (open < 0) return null
        val close = reply.indexOf(']', open)
        if (close < 0) return null   // cut off before the closing bracket

        // Everything between the brackets: pick every whole number.
        val inside = reply.substring(open + 1, close)
        val numbers = Regex("\\d+").findAll(inside)
            .mapNotNull { it.value.toIntOrNull() }   // too-long digit runs are dropped
            .filter { it in 1..batchSize }
            .distinct()
            .sorted()
            .toList()
        return numbers
    }
}
