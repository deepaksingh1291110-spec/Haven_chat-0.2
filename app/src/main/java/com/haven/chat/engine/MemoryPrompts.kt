// =====================================================================
// MemoryPrompts.kt  (app/src/main/java/com/haven/chat/engine/)   NEW in Phase 4
// WHAT: Everything TEXT-related for the job "which of the user's messages
//       are PERSONAL, and which TAGS describe each one?". Four parts:
//         1) DEFAULT_EXTRACTION_PROMPT  the classifier prompt. Its first
//                                      part is word for word the one the
//                                      user tested (100% with thinking, 90%
//                                      without). STEP 7 added a TAGS part.
//         2) buildBatchMessage()       turns up to 5 user messages into the
//                                      numbered list that Gemma reads.
//         3) parsePersonalItems()      reads Gemma's reply in a FORGIVING
//                                      way and returns, for every personal
//                                      message, its number AND its tags.
//         4) the batch size and the token limits for this job.
//
// WHY ITS OWN FILE: the prompt and the reader are plain text work with no
//   Android and no engine inside. They are easy to read, to change and to
//   check, and EngineManager / MemoryExtractor just call them.
//
// HOW ONE BATCH WORKS (done by later files, this file only prepares text):
//   1) The engine conversation is reset and gets the extraction prompt as
//      its system prompt (the user's CHAT prompt is NOT used, because the
//      system prompt matters a lot for the result).
//   2) The user message sent to Gemma is buildBatchMessage(...): only the
//      USER's messages, numbered 1, 2, 3... Gemma's old answers are never
//      sent.
//   3) Gemma answers, for example
//        {"personal": [{"n": 2, "tags": ["brother", "rahul", "kotlin", "course"]}]}
//      parsePersonalItems() reads it. Number 2 means "the 2nd message of
//      THIS batch".
//   4) The app maps the numbers back to the real saved messages and copies
//      the question and the saved final answer word for word. The TAGS are
//      the only thing taken from Gemma's reply; they are searched later.
//
// STEP 7 CHANGES (marked "STEP 7"):
//   - BATCH_SIZE is 5 (it was 15): fewer messages per batch gives Gemma
//     room to write good tags for each one.
//   - The prompt asks for 4 to 6 tags per personal message, and the reply
//     format is now {"personal": [{"n": 2, "tags": [...]}]}.
//   - The reader returns PersonalItem(number, tags). parsePersonalNumbers()
//     is kept (it now just takes the numbers from the items), so code that
//     only needs the numbers keeps working.
//   - TRIAL AND ERROR: the prompt is easy to change in one place (the
//     DEFAULT_EXTRACTION_PROMPT text below). A later step lets the user edit
//     it in Settings without rebuilding the app. Whatever the prompt says,
//     the reply MUST keep this shape, because the reader depends on it:
//       {"personal": [{"n": <number>, "tags": ["...", "..."]}]}
//
// IMPORTANT: Gemma only DECIDES and TAGS. Nothing else Gemma writes is
//   ever saved as a memory. The question and the answer that are saved come
//   from the database.
// =====================================================================
package com.haven.chat.engine

object MemoryPrompts {

    // -----------------------------------------------------------------
    // NUMBERS FOR THE JOB
    // -----------------------------------------------------------------

    // STEP 7: how many user messages go to Gemma in ONE batch (it was 15).
    // A long chat is processed in several batches; the marker is saved after
    // each batch, so a cancel or a crash loses at most one batch.
    const val BATCH_SIZE = 5

    // Only for what GEMMA READS: a very long message (for example a pasted
    // file) is cut to this many characters and gets "..." at the end. This
    // keeps a batch short and fast. It does NOT change what is saved: the
    // saved question is always the full original text.
    private const val MAX_MESSAGE_CHARS_FOR_GEMMA = 500

    // Longest reply we allow (in tokens). With thinking ON the reply used
    // about 580 tokens for 15 messages in the user's test, so 2000 leaves
    // room. With thinking OFF the reply is just the JSON: 5 messages with up
    // to 6 tags each need about 200 tokens, so 400 is plenty. (The engine
    // cuts both numbers down by itself if the context window is too small.)
    private const val MAX_NEW_TOKENS_WITH_THINKING = 2000
    private const val MAX_NEW_TOKENS_WITHOUT_THINKING = 400

    // STEP 7: tags. The most tags we keep for one memory, and the longest tag.
    const val MAX_TAGS = 6
    private const val MAX_TAG_LENGTH = 40

    /** Token limit for one batch, depending on the "memory thinking" switch. */
    fun maxNewTokens(thinking: Boolean): Int =
        if (thinking) MAX_NEW_TOKENS_WITH_THINKING else MAX_NEW_TOKENS_WITHOUT_THINKING

    // -----------------------------------------------------------------
    // 1) THE SYSTEM PROMPT
    // -----------------------------------------------------------------
    // Sections 1 to 3 are the user's tested prompt, word for word.
    // STEP 7 added section 4 (TAGS), an example, and a new OUTPUT FORMAT.
    // A Kotlin "raw string" (three quotes) keeps line breaks and quote
    // marks exactly as written. trimIndent() removes the spaces that only
    // exist because the text is indented inside this file.
    // Do NOT put a dollar sign in this text: Kotlin would treat it as a
    // variable.
    val DEFAULT_EXTRACTION_PROMPT: String = """
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

        4. TAGS (only for the messages you marked PERSONAL):
        - Write 4 to 6 short tags for each PERSONAL message. A tag is one or two lowercase words, in English.
        - The tags are search keywords: someone will use them later to find this message again, so choose the words they would most likely search with.
        - Include the specific names and things mentioned: people, places, devices, apps, projects, subjects.
        - Include the kind of personal fact, for example: plan, problem, decision, preference, habit, health, family, work, study, purchase.
        - Do NOT use empty tags like "user", "message", "question", "personal" or "info". Do NOT invent details that are not in the message.

        EXAMPLE:
        Messages:
        1. What is the boiling point of water?
        2. My brother Rahul starts a Kotlin course next week, how can I help him?
        3. Explain what a mutex is.
        Reply:
        {"personal": [{"n": 2, "tags": ["brother", "rahul", "kotlin", "course", "family", "plan"]}]}

        OUTPUT FORMAT:
        - Return ONLY raw, valid JSON matching this exact structure: {"personal": [{"n": 2, "tags": ["tag1", "tag2", "tag3", "tag4"]}]}
        - "n" is the message number. Write one object for every PERSONAL message, and nothing for GENERAL messages.
        - Do NOT include markdown formatting (no ```json), explanations, or extra text.
        - If none of the messages are personal, return: {"personal": []}
    """.trimIndent()

    // The name the rest of the app already uses. For now it is the default
    // prompt; a later step lets the user's own edited prompt (saved in
    // Settings) take its place.
    val EXTRACTION_SYSTEM_PROMPT: String
        get() = DEFAULT_EXTRACTION_PROMPT

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
     * STEP 7 (NEW): one personal message found by Gemma.
     *   number  which message of the batch (1 = the first one)
     *   tags    the tags Gemma wrote for it (lowercase, at most MAX_TAGS).
     *           EMPTY when Gemma gave none (see the notes of
     *           parsePersonalItems): the memory is then still saved, but it
     *           cannot be found by tag search.
     */
    class PersonalItem(val number: Int, val tags: List<String>)

    // Finds one item like  {"n": 2, "tags": ["a", "b"]}  inside the reply.
    // The quotes around n and tags are optional, spaces do not matter, and
    // the tags list may not contain a "]" (tags are plain words).
    private val ITEM_REGEX = Regex(
        "\\{\\s*\"?n\"?\\s*:\\s*\"?(\\d+)\"?\\s*,\\s*\"?tags\"?\\s*:\\s*\\[([^\\]]*)\\]",
        RegexOption.IGNORE_CASE
    )

    // A piece of text between two double quotes.
    private val QUOTED = Regex("\"([^\"]*)\"")

    /**
     * Reads Gemma's reply and returns the PERSONAL messages with their tags.
     *
     *   reply      Gemma's FINAL answer text (never the thinking text)
     *   batchSize  how many messages the batch had (valid numbers: 1..batchSize)
     *
     * Result:
     *   a list (may be EMPTY)  the reply was understood. An empty list means
     *                          "none of the messages is personal" and is a
     *                          valid answer. The list is sorted by number.
     *   null                   the reply could NOT be understood (no
     *                          "personal" word, no list, the list is cut off
     *                          in the middle, strange content...). The caller
     *                          then tries ONE more time, and if it fails
     *                          again it does NOT move the marker, so nothing
     *                          is lost.
     *
     * FORGIVING means: we do not need perfect JSON. A small model may wrap
     * the answer in ```json fences, add a sentence before it, or use odd
     * spaces. So we look for the word "personal", then find the list that
     * follows it by COUNTING brackets (so tags inside the list do not end it
     * early). If the list never closes, the reply was cut off: null, so a
     * half-read batch can never be saved as if it were complete.
     * Inside the list every {"n": ..., "tags": [...]} item is picked out.
     * Numbers outside 1..batchSize and repeated numbers are dropped.
     *
     * OLD FORMAT: if Gemma only writes numbers, like {"personal": [2, 4]},
     * they are accepted with EMPTY tags (the personal messages are still
     * found; only the tags are missing).
     */
    fun parsePersonalItems(reply: String, batchSize: Int): List<PersonalItem>? {
        // The key word. Case does not matter ("Personal" is fine too).
        val keyAt = reply.indexOf("personal", ignoreCase = true)
        if (keyAt < 0) return null

        // The whole list after the key word, found by counting brackets.
        val range = outerListRange(reply, keyAt) ?: return null
        val inner = reply.substring(range.first + 1, range.last)

        // "personal": []  = nothing personal. A valid answer.
        if (inner.isBlank()) return emptyList()

        // Pick out every {"n": ..., "tags": [...]} item.
        val items = ArrayList<PersonalItem>()
        val seen = HashSet<Int>()
        for (match in ITEM_REGEX.findAll(inner)) {
            val number = match.groupValues[1].toIntOrNull() ?: continue
            if (number !in 1..batchSize || !seen.add(number)) continue
            items.add(PersonalItem(number, cleanTags(match.groupValues[2])))
        }
        if (items.isNotEmpty()) return items.sortedBy { it.number }

        // The old format: only numbers, like [2, 4]. Accepted without tags.
        if (inner.all { it.isDigit() || it == ',' || it.isWhitespace() }) {
            return Regex("\\d+").findAll(inner)
                .mapNotNull { it.value.toIntOrNull() }
                .filter { it in 1..batchSize }
                .distinct()
                .sorted()
                .map { PersonalItem(it, emptyList()) }
                .toList()
        }

        // Something else: not understood.
        return null
    }

    /**
     * The numbers of the personal messages only (no tags). Kept for code
     * that only needs to know WHICH messages are personal. Same rules and
     * same null as parsePersonalItems().
     */
    fun parsePersonalNumbers(reply: String, batchSize: Int): List<Int>? =
        parsePersonalItems(reply, batchSize)?.map { it.number }

    // Finds the "[ ... ]" list that follows position [from]: from its "[" to
    // the "]" that closes it. Brackets inside quoted text do not count.
    // Returns the positions of that "[" and "]", or null when there is no
    // list or it is never closed (the reply was cut off).
    private fun outerListRange(text: String, from: Int): IntRange? {
        val open = text.indexOf('[', from)
        if (open < 0) return null

        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++              // skip the escaped character
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return open..i
                    }
                }
            }
            i++
        }
        return null     // never closed
    }

    // Turns the inside of a tags list ("a", "b c", "d") into clean tags:
    // lowercase, one line, no empty ones, no repeats, not too long, at most
    // MAX_TAGS. If there are no quote marks at all, the text is split at commas.
    private fun cleanTags(raw: String): List<String> {
        val quoted = QUOTED.findAll(raw).map { it.groupValues[1] }.toList()
        val parts = if (quoted.isNotEmpty()) quoted else raw.split(',')
        return parts
            .map { it.trim().lowercase().replace(Regex("\\s+"), " ").trim('"', '\'', '#', ' ') }
            .filter { it.isNotEmpty() && it.length <= MAX_TAG_LENGTH }
            .distinct()
            .take(MAX_TAGS)
    }
}
