// =====================================================================
// EngineManager.kt  (app/src/main/java/com/haven/chat/engine/)
// WHAT: ONE shared object that owns the native llama.cpp engine
//       (LlamaBridge). Both ViewModels (chat and settings) talk to
//       THIS object and never to LlamaBridge directly.
//
// WHY AN "object": In Kotlin, "object" means there is exactly ONE copy
//       in the whole app. Good here, because the C++ engine can hold
//       only ONE model at a time (see haven_engine.cpp).
//
// THREADS (important, simple rule):
//   - The C++ engine must be called from ONE background thread only.
//     So we create one single worker thread ("worker") and run EVERY
//     engine call on it, one after another.
//   - The screen (UI) runs on the "main" thread. All callbacks that we
//     give back to the ViewModels are delivered on the main thread, so
//     they can safely change screen state.
//
// STATUS: "state" is Compose state. When it changes, any screen that
//       reads it redraws by itself (the chip under the chat title).
//
// ---------------------------------------------------------------------
// PHASE 3 CHANGES (everything new is marked "PHASE 3"):
//   1) New state NAMING: Gemma is writing a chat title in the background.
//   2) openChat(): puts an OLD chat back into Gemma's head. It adds the
//      newest messages that fit into the context window (counted exactly
//      with countTokens), and leaves a reserve for the reply.
//   3) historyProvider + load(): after the model is (re)loaded, the chat
//      that is open on screen is replayed automatically, so reloading
//      the model in Settings does not make Gemma forget the chat.
//   4) generateTitle(): the hidden title request (thinking OFF, max 16
//      tokens, output never shown). Afterwards the conversation inside
//      the engine is restored, so the title question never leaks into
//      the real chat.
//   5) contextSize is remembered after loading.
//
// PHASE 4, STEP 3 CHANGES (marked "PHASE 4"):
//   The job "find the PERSONAL messages of a chat" (memory extraction).
//   1) New state MEMORIZING: Gemma is classifying messages. It is a BUSY
//      state like NAMING (isBusy covers it, the chip says "Saving
//      memories...").
//   2) startMemorizing(): READY -> MEMORIZING. Stays MEMORIZING for the
//      WHOLE job (all batches), so the user cannot send a message or open
//      a chat in between.
//   3) classifyBatch(): ONE batch. Every attempt starts a CLEAN
//      conversation with the extraction prompt (MemoryPrompts.kt), sends
//      the numbered list of user messages and reads Gemma's JSON reply.
//      If the reply is not understood it tries ONE more time.
//      The text Gemma writes is never saved anywhere: only the NUMBERS in
//      it are used.
//   4) finishMemorizing(): puts the open chat back into Gemma's head
//      (like after a title) and sets the state back to READY.
//   5) cancelMemorizing(): stops the running batch (same as stop()).
//
// PHASE 4, STEP 6 CHANGES (marked "STEP 6"): LIGHT SEARCH (a TOOL for Gemma).
//   generate() can now give Gemma ONE tool (a ToolRunner, here: the memory
//   search_memory tool, see MemorySearch.kt). How a normal answer goes:
//     1) The tool is registered, Gemma reads the question and writes the
//        answer. Nothing else happens. (Most answers.)
//     2) Or Gemma decides it needs the tool and, instead of an answer, asks
//        for a CALL with arguments. Then the engine:
//          - tells the screen to start the bubble again (onRestart), because
//            what was streamed so far was only the lead-in to the call,
//          - runs the tool (tool.run, still on the worker thread) and gives
//            the result text back to Gemma (addToolResult),
//          - asks Gemma to continue (generate with an empty text). Gemma
//            now reads the result and writes the real answer.
//        Gemma may ask for the tool a second time (to narrow the search).
//        At most MAX_TOOL_ROUNDS calls are served per answer; if Gemma still
//        asks after that, the answer ends with a plain-words problem text.
//   - The tool is registered ONLY for this call and removed afterwards
//     (also after errors), so the other jobs (title, memory extraction)
//     never see it.
//   - searchingMemory (Compose state) is true while the tool runs; the chip
//     then says "Searching memories...".
//   - generate() has two new parameters with default values, so older
//     calls still work: tool (null = no tool) and onRestart.
//
// PHASE 4, STEP 7 CHANGES (marked "STEP 7"): TAGS FOR EVERY PERSONAL MESSAGE.
//   - classifyBatch() now gets TAGS from Gemma, not only the numbers. Its
//     BatchResult has "items": for every personal message its number and its
//     4 to 6 tags (MemoryPrompts.PersonalItem). The old "personal" list of
//     numbers is still there (it is taken from the items).
//   - classifyBatch() has a new parameter "prompt": the extraction system
//     prompt to use. The caller passes the one from Settings (the user can
//     edit it there to tune it). It has a default value, so older calls
//     still work and use the built-in prompt.
//   Everything else works as before.
// =====================================================================
package com.haven.chat.engine

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.haven.chat.ChatListener
import com.haven.chat.LlamaBridge
import java.io.File
import java.util.concurrent.Executors

object EngineManager {

    // -----------------------------------------------------------------
    // Status of the engine (what the chip shows)
    // -----------------------------------------------------------------
    enum class State {
        NOT_LOADED,   // no model in RAM
        LOADING,      // reading the GGUF file into RAM (can take a while)
        READY,        // model loaded, waiting for a question
        THINKING,     // Gemma is writing an answer right now
        NAMING,       // PHASE 3: Gemma is writing a chat title (hidden, short)
        MEMORIZING,   // PHASE 4: Gemma is finding the personal messages of a chat
        ERROR         // something failed; see errorMessage
    }

    // "private set" = only this object can change them; screens can read.
    var state by mutableStateOf(State.NOT_LOADED)
        private set

    // A plain-words problem text (empty when there is no problem).
    var errorMessage by mutableStateOf("")
        private set

    // File name of the loaded model without ".gguf", e.g. "gemma4-e2b-q4_0".
    var modelName by mutableStateOf("gemma")
        private set

    // True from the moment the FIRST piece of the real answer arrives until
    // Gemma is done. While state is THINKING, this tells the two phases
    // apart: false = still thinking, true = already writing the answer.
    // (State.THINKING itself means "busy"; it covers both phases.)
    var answering by mutableStateOf(false)
        private set

    // True from the moment the FIRST piece of thinking text arrives.
    // Together with "answering" this gives three phases while busy:
    //   neither flag  -> "Reading..."  (Gemma reads your message and the old chat)
    //   thinkingSeen  -> "Thinking..." (thinking text is arriving)
    //   answering     -> "Writing..." (the real answer is arriving)
    var thinkingSeen by mutableStateOf(false)
        private set

    // STEP 6: true while the memory search tool is running (between Gemma's
    // call and the result going back). The chip then says "Searching memories...".
    var searchingMemory by mutableStateOf(false)
        private set

    /** Short text for the status chip, for example "Ready" or "Loading...". */
    fun statusText(): String = when (state) {
        State.NOT_LOADED -> "Not loaded"
        State.LOADING -> "Loading..."
        State.READY -> "Ready"
        State.THINKING ->
            if (searchingMemory) "Searching memories..."     // STEP 6
            else if (answering) "Writing..."
            else if (thinkingSeen) "Thinking..."
            else "Reading..."
        State.NAMING -> "Naming chat..."      // PHASE 3
        State.MEMORIZING -> "Saving memories..."   // PHASE 4
        State.ERROR -> "Error"
    }

    /**
     * PHASE 3: true while the engine cannot take a new job (loading,
     * answering, naming).
     * PHASE 4: and while it is memorizing.
     */
    val isBusy: Boolean
        get() = state == State.LOADING || state == State.THINKING ||
                state == State.NAMING || state == State.MEMORIZING

    // -----------------------------------------------------------------
    // PHASE 3: one old message, handed over by the ChatViewModel
    // -----------------------------------------------------------------
    /**
     * isUser = true for your message, false for Gemma's answer.
     * text   = only the visible text (never the thinking text).
     */
    class HistoryItem(val isUser: Boolean, val text: String)

    /**
     * PHASE 3: set by ChatViewModel. It returns the messages of the chat that
     * is open on screen RIGHT NOW (oldest first). load() reads it, so that
     * after a model reload the open chat is put back into Gemma's head.
     * null = nothing to replay (a fresh chat).
     */
    @Volatile var historyProvider: (() -> List<HistoryItem>)? = null

    // -----------------------------------------------------------------
    // Result of one generate() call
    // -----------------------------------------------------------------
    class Result(
        val answer: String,               // final answer ("" if none)
        val thinking: String,             // thinking text ("" if none)
        val tokenCount: Int?,             // tokens written, or null if unknown
        val tokensPerSecond: Float?,      // writing speed, or null if unknown
        val stopped: Boolean,             // true if the user pressed stop
        val error: String?                // plain-words problem, or null if all went well
    )

    // -----------------------------------------------------------------
    // PHASE 4: result of one classifyBatch() call
    // -----------------------------------------------------------------
    /**
     * items     STEP 7: the PERSONAL messages Gemma found, each with its
     *           number (1 = first message of the batch) and its tags. An
     *           EMPTY list is a valid answer ("none is personal"). null =
     *           the reply could not be understood, even after the second
     *           try (see "error" and "cancelled" first).
     * cancelled true if the user cancelled while the batch was running.
     * error     a plain-words problem (for example "conversation too
     *           long"), or null.
     *
     * Rule for the caller: ONLY when items != null may the marker move
     * forward. In every other case the batch must be treated as NOT done.
     */
    class BatchResult(
        val items: List<MemoryPrompts.PersonalItem>?,
        val cancelled: Boolean,
        val error: String?
    ) {
        /** Only the numbers of the personal messages (no tags), or null. */
        val personal: List<Int>?
            get() = items?.map { it.number }
    }

    // -----------------------------------------------------------------
    // STEP 6: a tool that Gemma may call during generate()
    // -----------------------------------------------------------------
    /**
     * name, description, parametersJson  what Gemma reads about the tool
     *     (the parameters are a JSON schema). For the memory search see
     *     MemorySearch.kt.
     * run  does the work when Gemma calls the tool. It is called ON THE
     *     WORKER THREAD (so it may block for a moment, but must not touch
     *     the screen). It gets:
     *       argsJson     the arguments of the call, as JSON text
     *       tokenBudget  how many tokens the result text may use
     *       countTokens  counts the tokens of a text exactly (Gemma's tokenizer)
     *     and returns the result text that Gemma reads. It should never
     *     throw; if it does, Gemma is told that the tool failed.
     */
    class ToolRunner(
        val name: String,
        val description: String,
        val parametersJson: String,
        val run: (argsJson: String, tokenBudget: Int, countTokens: (String) -> Int) -> String
    )

    // -----------------------------------------------------------------
    // PHASE 3: numbers used when an old chat is put back into Gemma
    // -----------------------------------------------------------------

    // Room kept free for Gemma's NEXT reply (in tokens). Thinking needs more.
    private const val REPLY_RESERVE_THINKING = 1000
    private const val REPLY_RESERVE_NORMAL = 500

    // Room kept free for your NEXT message plus the chat-template markers.
    // (We cannot know how long your next message will be, so we guess.)
    private const val NEXT_USER_MESSAGE_ROOM = 300

    // Every message costs a few extra tokens for the template markers.
    private const val PER_MESSAGE_OVERHEAD = 8

    // The hidden title request.
    private const val TITLE_MAX_TOKENS = 16
    private const val TITLE_MAX_CHARS = 60
    private const val TITLE_PROMPT =
        "Write a short title for this conversation, at most 6 words, " +
        "in the same language as my first message. " +
        "Reply with the title only: no quotes, no full stop."

    // PHASE 4: how many times one batch is tried when Gemma's reply cannot
    // be understood (1 first try + 1 more try).
    private const val MEMORY_ATTEMPTS = 2

    // STEP 6: how many tool calls are served for ONE answer. (Gemma asks, we
    // answer: that is one round. A second round lets Gemma narrow its search.)
    private const val MAX_TOOL_ROUNDS = 2

    // -----------------------------------------------------------------
    // Private parts
    // -----------------------------------------------------------------

    // The ONE background thread that runs every engine call.
    private val worker = Executors.newSingleThreadExecutor()

    // Used to send results back to the main (screen) thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    // The native engine. It is created on the worker thread in init().
    // "@Volatile" = a change made by one thread is seen by all threads.
    @Volatile private var bridge: LlamaBridge? = null

    // True after init() was called once (so we never start twice).
    @Volatile private var started = false

    // Set to true when the user presses stop. The streaming listener
    // reads it and tells the C++ code to finish.
    @Volatile private var stopRequested = false

    // PHASE 3: the context size the model was loaded with (0 = nothing loaded).
    @Volatile private var loadedContext = 0

    // Run something on the main thread (now if we are already there).
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // Change the state from any thread.
    private fun setState(newState: State, message: String = "") {
        onMain {
            state = newState
            errorMessage = message
            searchingMemory = false          // STEP 6: a new state ends any search
            // Any state other than THINKING means the busy phases are over.
            if (newState != State.THINKING) {
                answering = false
                thinkingSeen = false
            }
        }
    }

    // -----------------------------------------------------------------
    // 1) init: start the engine (call ONCE when the app starts)
    // -----------------------------------------------------------------
    /**
     * nativeLibDir = applicationInfo.nativeLibraryDir (the folder where
     * Android put our .so files). llama.cpp loads its CPU backends from there.
     */
    fun init(nativeLibDir: String) {
        if (started) return
        started = true
        worker.execute {
            try {
                // Creating LlamaBridge loads libhaven-engine.so.
                val b = LlamaBridge()
                b.init(nativeLibDir)
                bridge = b
            } catch (t: Throwable) {
                // For example UnsatisfiedLinkError if the library is missing.
                setState(State.ERROR, "Could not start the engine: " + (t.message ?: t.javaClass.simpleName))
            }
        }
    }

    // -----------------------------------------------------------------
    // 2) load: read the GGUF file into RAM
    // -----------------------------------------------------------------
    /**
     * Loads the model. If another model is loaded, it is unloaded first.
     * onDone(null) = success, onDone("text") = the problem in plain words.
     * onDone always runs on the main thread.
     *
     * PHASE 3: after a successful load, the chat that is open on screen
     * (historyProvider) is replayed into the engine, newest messages first
     * until the context is full. "thinking" only decides how much room is
     * kept for the reply (1000 tokens with thinking, 500 without).
     * It has a default value, so older calls without it still work.
     */
    fun load(
        path: String,
        contextSize: Int,
        threads: Int,
        systemPrompt: String,
        thinking: Boolean = true,
        onDone: (String?) -> Unit
    ) {
        // PHASE 3: "isBusy" now also covers NAMING (and, PHASE 4, MEMORIZING).
        if (isBusy) {
            onDone("The engine is busy. Wait a moment and try again.")
            return
        }

        // Check the file BEFORE the heavy work, to give a clear message.
        val file = File(path)
        if (!file.exists()) {
            val msg = "Model file not found: $path"
            setState(State.ERROR, msg)
            onDone(msg)
            return
        }
        if (!file.canRead()) {
            val msg = "Cannot read the model file. Allow \"All files access\" for Haven Chat."
            setState(State.ERROR, msg)
            onDone(msg)
            return
        }

        modelName = file.nameWithoutExtension
        setState(State.LOADING)

        // PHASE 3: take a copy of the open chat NOW, on the main thread
        // (the screen's data must not be read from the worker thread).
        val history: List<HistoryItem> = try {
            historyProvider?.invoke() ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }

        worker.execute {
            val b = bridge
            if (b == null) {
                val msg = if (errorMessage.isNotEmpty()) errorMessage else "The engine is not started yet. Try again."
                setState(State.ERROR, msg)
                onMain { onDone(msg) }
                return@execute
            }
            try {
                b.unload()   // free an older model if there is one
                loadedContext = 0
                val rc = b.loadModel(path, contextSize, threads)
                if (rc != 0) {
                    val why = when (rc) {
                        1 -> "The model file could not be loaded (wrong file or not enough RAM)."
                        2 -> "The model loaded but the context could not be created. Try a smaller context size."
                        else -> "Loading failed (code $rc)."
                    }
                    val msg = why + " " + b.lastLog().takeLast(150)
                    setState(State.ERROR, msg.trim())
                    onMain { onDone(msg.trim()) }
                } else {
                    loadedContext = contextSize
                    // Start the conversation with the system prompt, and
                    // (PHASE 3) put the open chat back, as much as fits.
                    replayOnWorker(b, systemPrompt, history, thinking)
                    setState(State.READY)
                    onMain { onDone(null) }
                }
            } catch (t: Throwable) {
                val msg = "Loading failed: " + (t.message ?: t.javaClass.simpleName)
                setState(State.ERROR, msg)
                onMain { onDone(msg) }
            }
        }
    }

    // -----------------------------------------------------------------
    // PHASE 3: openChat: put an OLD chat into Gemma's head
    // -----------------------------------------------------------------
    /**
     * Call this when the user taps an old chat in the drawer.
     * history = the chat's messages, oldest first.
     *
     * What it does: starts a clean conversation with the system prompt, then
     * adds the NEWEST messages that fit into the context window. Older ones
     * stay in the database and on screen, Gemma just does not re-read them.
     *
     * onDone (main thread) gets:
     *   n >= 0 : n messages were put into Gemma's head
     *   -1     : the engine is busy, nothing was done (try again later)
     * If no model is loaded yet, nothing is done now and onDone(0) is called:
     * the chat is replayed automatically when the model is loaded
     * (see historyProvider).
     */
    fun openChat(
        systemPrompt: String,
        history: List<HistoryItem>,
        thinking: Boolean,
        onDone: (Int) -> Unit
    ) {
        when (state) {
            State.READY -> worker.execute {
                val b = bridge
                val n = try {
                    if (b != null) replayOnWorker(b, systemPrompt, history, thinking) else 0
                } catch (t: Throwable) {
                    0
                }
                onMain { onDone(n) }
            }
            State.NOT_LOADED, State.ERROR -> onDone(0)
            else -> onDone(-1)    // loading, answering, naming or memorizing
        }
    }

    // Runs ON THE WORKER THREAD. Starts a new conversation and adds the
    // newest messages that fit. Returns how many messages were added.
    private fun replayOnWorker(
        b: LlamaBridge,
        systemPrompt: String,
        history: List<HistoryItem>,
        thinking: Boolean
    ): Int {
        // Always start clean (this also empties the old KV cache).
        b.resetChat(systemPrompt.toByteArray(Charsets.UTF_8))
        if (history.isEmpty()) return 0

        // How many tokens may the old messages use?
        //   context window
        //   - the system prompt
        //   - room for Gemma's next reply (1000 thinking, 500 normal)
        //   - room for the user's next message
        val reserve = if (thinking) REPLY_RESERVE_THINKING else REPLY_RESERVE_NORMAL
        var budget = loadedContext - tokensOf(b, systemPrompt) - reserve - NEXT_USER_MESSAGE_ROOM

        // Walk from the NEWEST message to the oldest. Stop at the first one
        // that does not fit (so we never skip a message and keep a gap).
        val picked = ArrayList<HistoryItem>()
        var i = history.size - 1
        while (i >= 0) {
            val cost = tokensOf(b, history[i].text) + PER_MESSAGE_OVERHEAD
            if (cost > budget) break
            budget -= cost
            picked.add(history[i])
            i--
        }
        picked.reverse()   // back to oldest first

        // Gemma's chat template wants: user, assistant, user, assistant...
        // Clean up gaps (for example a stopped answer that was never saved).
        val cleaned = ArrayList<HistoryItem>()
        for (m in picked) {
            // Two messages from the same side in a row: keep only the newer.
            if (cleaned.isNotEmpty() && cleaned[cleaned.size - 1].isUser == m.isUser) {
                cleaned.removeAt(cleaned.size - 1)
            }
            cleaned.add(m)
        }
        // Must START with a user message ...
        while (cleaned.isNotEmpty() && !cleaned[0].isUser) cleaned.removeAt(0)
        // ... and END with an answer (the next message is a user message).
        if (cleaned.isNotEmpty() && cleaned[cleaned.size - 1].isUser) {
            cleaned.removeAt(cleaned.size - 1)
        }

        for (m in cleaned) {
            b.appendMessage(if (m.isUser) "user" else "assistant", m.text.toByteArray(Charsets.UTF_8))
        }
        return cleaned.size
    }

    // Exact token count of a text (Gemma's own tokenizer). If the engine
    // says "no model" (-1), use a safe guess: 1 token per 3 characters.
    private fun tokensOf(b: LlamaBridge, text: String): Int {
        if (text.isEmpty()) return 0
        val n = b.countTokens(text.toByteArray(Charsets.UTF_8))
        return if (n >= 0) n else text.length / 3 + 1
    }

    // -----------------------------------------------------------------
    // 3) generate: ask Gemma one question and stream the answer
    // -----------------------------------------------------------------
    /**
     * onThinking / onAnswer get small pieces of text while Gemma writes
     * (on the main thread). onDone runs once at the end with the full Result.
     *
     * STEP 6 (both new parameters have default values):
     *   tool       a tool Gemma may call during this answer (the memory
     *              search), or null for none. See the notes at the top.
     *   onRestart  called (main thread) when Gemma has asked for the tool
     *              and the answer starts again after the tool result. The
     *              caller should throw away the thinking and answer pieces
     *              it collected so far: they were only the lead-in to the
     *              call. The final texts come in the Result.
     */
    fun generate(
        userText: String,
        maxNewTokens: Int,
        thinking: Boolean,
        onThinking: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onDone: (Result) -> Unit,
        tool: ToolRunner? = null,
        onRestart: () -> Unit = {}
    ) {
        if (state != State.READY) {
            val msg = when (state) {
                State.THINKING -> "Gemma is still answering."
                State.NAMING -> "Gemma is still naming the chat. Try again in a moment."   // PHASE 3
                State.MEMORIZING -> "Gemma is still saving memories. Try again in a moment."   // PHASE 4
                else -> "No model is loaded. Open Settings and tap Load Model."
            }
            onDone(Result("", "", null, null, false, msg))
            return
        }

        stopRequested = false
        answering = false            // a new turn starts in the "reading" phase
        thinkingSeen = false
        setState(State.THINKING)

        worker.execute {
            val b = bridge
            if (b == null) {
                setState(State.ERROR, "The engine is not started.")
                onMain { onDone(Result("", "", null, null, false, "The engine is not started.")) }
                return@execute
            }

            // Receives the pieces from C++ (called on the worker thread).
            val listener = object : ChatListener {
                override fun onDelta(kind: Int, bytes: ByteArray): Boolean {
                    val piece = String(bytes, Charsets.UTF_8)
                    onMain {
                        if (kind == ChatListener.THINKING) {
                            // First thinking text: the chip changes to "Thinking...".
                            thinkingSeen = true
                            onThinking(piece)
                        } else {
                            // First answer text: the chip changes to "Writing...".
                            answering = true
                            onAnswer(piece)
                        }
                    }
                    // Returning false tells C++ to stop writing.
                    return !stopRequested
                }
            }

            val result: Result = try {
                // STEP 6: the tool is registered only for this answer. First
                // remove anything left over (the tool list is global in C++).
                b.clearTools()
                if (tool != null) {
                    b.addTool(
                        tool.name,
                        tool.description.toByteArray(Charsets.UTF_8),
                        tool.parametersJson.toByteArray(Charsets.UTF_8)
                    )
                }

                var stats = b.generate(userText.toByteArray(Charsets.UTF_8), maxNewTokens, thinking, listener)

                // STEP 6: the tool loop. As long as Gemma ends its turn with a
                // CALL (and not with an answer), run the tool, give Gemma the
                // result and let it continue.
                var rounds = 0
                var gaveUp = false
                while (true) {
                    if (tool == null || stopRequested ||
                        stats.startsWith("ERROR") || stats.startsWith("STOPPED")
                    ) break

                    val calls = b.lastToolCalls()
                    if (calls.isEmpty()) break          // a normal answer: done

                    if (rounds >= MAX_TOOL_ROUNDS) {    // Gemma keeps asking: give up
                        gaveUp = true
                        break
                    }
                    rounds++

                    // The pieces streamed so far were only the lead-in to the
                    // call. Tell the screen to start the bubble again, and show
                    // "Searching memories..." while the tool runs.
                    onMain {
                        answering = false
                        thinkingSeen = false
                        searchingMemory = true
                        onRestart()
                    }

                    val budget = MemorySearch.tokenBudget(loadedContext)
                    for (call in calls) {
                        val text = try {
                            tool.run(call.argumentsJson, budget) { t -> tokensOf(b, t) }
                        } catch (t: Throwable) {
                            "The search failed. Tell the user you could not look."
                        }
                        b.addToolResult(call.id, call.name, text.toByteArray(Charsets.UTF_8))
                    }

                    onMain { searchingMemory = false }

                    // An EMPTY text: nothing new is added, Gemma reads the tool
                    // result and continues.
                    stats = b.generate(ByteArray(0), maxNewTokens, thinking, listener)
                }

                when {
                    stats.startsWith("ERROR") ->
                        Result("", "", null, null, false, stats.removePrefix("ERROR:").trim())
                    stats.startsWith("STOPPED") ->
                        Result("", "", null, null, true, null)
                    gaveUp ->
                        Result(
                            "", "", null, null, false,
                            "Gemma kept searching your memories without giving an answer. Try asking again."
                        )
                    else -> {
                        // stats looks like:
                        // "prompt 20 new tokens (0 reused) 15.2 t/s | answer 85 tokens 7.4 t/s"
                        val m = Regex("answer (\\d+) tokens ([0-9.]+) t/s").find(stats)
                        Result(
                            answer = b.lastAnswer().trim(),
                            thinking = b.lastThinking().trim(),
                            tokenCount = m?.groupValues?.get(1)?.toIntOrNull(),
                            tokensPerSecond = m?.groupValues?.get(2)?.toFloatOrNull(),
                            stopped = stopRequested,
                            error = null
                        )
                    }
                }
            } catch (t: Throwable) {
                Result("", "", null, null, false, "Generation failed: " + (t.message ?: t.javaClass.simpleName))
            }

            // STEP 6: take the tool away again, so the other jobs never see it.
            try {
                b.clearTools()
            } catch (t: Throwable) {
                // nothing to do
            }

            setState(State.READY)
            onMain { onDone(result) }
        }
    }

    // -----------------------------------------------------------------
    // PHASE 3: generateTitle: the hidden "give this chat a name" request
    // -----------------------------------------------------------------
    /**
     * Call this AFTER the first answer of a chat is finished.
     *   firstUserText = the user's first message
     *   firstAnswer   = Gemma's first answer (visible text only)
     *   systemPrompt  = the system prompt in use
     *
     * What happens:
     *   - The state becomes NAMING (the input bar can lock itself).
     *   - Gemma gets a special title question: thinking OFF, max 16 tokens.
     *     Nothing is shown on screen; the listener only watches for "stop".
     *   - When done, the engine's conversation is RESTORED to exactly
     *     [system prompt, first message, first answer]. So the title
     *     question never becomes part of the real chat.
     *   - onDone (main thread) gets the cleaned title, or null when it was
     *     cancelled, failed or came out empty. The caller keeps a fallback
     *     title in that case.
     *
     * To cancel the naming: call cancelNaming() (or stop()).
     */
    fun generateTitle(
        systemPrompt: String,
        firstUserText: String,
        firstAnswer: String,
        onDone: (String?) -> Unit
    ) {
        if (state != State.READY) {
            onDone(null)
            return
        }

        stopRequested = false
        setState(State.NAMING)

        worker.execute {
            val b = bridge
            var title: String? = null

            if (b != null) {
                try {
                    // The listener IGNORES the text. It only answers
                    // "keep going" (true) or "stop" (false).
                    val listener = object : ChatListener {
                        override fun onDelta(kind: Int, bytes: ByteArray): Boolean = !stopRequested
                    }
                    val stats = b.generate(
                        TITLE_PROMPT.toByteArray(Charsets.UTF_8),
                        TITLE_MAX_TOKENS,
                        false,                       // thinking OFF
                        listener
                    )
                    if (!stats.startsWith("ERROR") && !stats.startsWith("STOPPED") && !stopRequested) {
                        title = cleanTitle(b.lastAnswer())
                    }
                } catch (t: Throwable) {
                    // Naming is optional: on any problem we just have no title.
                    title = null
                }

                // RESTORE the real conversation (also after cancel or error).
                // After the FIRST answer the chat has only this one exchange,
                // so putting it back is quick.
                try {
                    b.resetChat(systemPrompt.toByteArray(Charsets.UTF_8))
                    b.appendMessage("user", firstUserText.toByteArray(Charsets.UTF_8))
                    if (firstAnswer.isNotEmpty()) {
                        b.appendMessage("assistant", firstAnswer.toByteArray(Charsets.UTF_8))
                    }
                } catch (t: Throwable) {
                    // The next generate() reports real problems.
                }
            }

            setState(State.READY)
            onMain { onDone(title) }
        }
    }

    /** PHASE 3: cancels a running title request (same as stop). Safe any time. */
    fun cancelNaming() {
        stop()
    }

    // Turns Gemma's raw title text into a clean one-line title.
    // Returns null if nothing usable is left.
    private fun cleanTitle(raw: String): String? {
        // First non-empty line only.
        var t = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        // Remove quotes, markdown stars and hashes around it.
        t = t.trim('"', '\'', '`', '*', '#', ' ', '\u201C', '\u201D')
        // Remove a leading "Title:".
        if (t.startsWith("title:", ignoreCase = true)) t = t.substring(6).trim()
        t = t.trim('"', '\'', '`', '*', ' ', '\u201C', '\u201D')
        // No full stop at the end.
        t = t.trimEnd('.', '!', ' ')
        if (t.length > TITLE_MAX_CHARS) t = t.take(TITLE_MAX_CHARS).trimEnd()
        return if (t.isEmpty()) null else t
    }

    // -----------------------------------------------------------------
    // PHASE 4: memory extraction (find the PERSONAL messages of a chat)
    // -----------------------------------------------------------------
    // The job has THREE parts, called one after another by MemoryExtractor:
    //   startMemorizing()      once, at the start  (READY -> MEMORIZING)
    //   classifyBatch(...)     once per batch of up to 5 user messages
    //   finishMemorizing(...)  once, at the end    (MEMORIZING -> READY)
    // The state stays MEMORIZING the whole time, so the rest of the app
    // sees the engine as busy (no sending, opening or deleting meanwhile).
    // The caller MUST always call finishMemorizing(), also after an error
    // or a cancel, otherwise the engine stays "busy".

    /**
     * Starts the memory job. Call it on the main thread.
     * Returns null when it started, or a plain-words problem text (for
     * example "No model is loaded...") when it could not start.
     */
    fun startMemorizing(): String? {
        return when (state) {
            State.READY -> {
                stopRequested = false
                setState(State.MEMORIZING)
                null
            }
            State.NOT_LOADED, State.ERROR ->
                "No model is loaded. Open Settings and tap Load Model."
            else ->
                "Gemma is busy. Try again in a moment."
        }
    }

    /**
     * Gemma reads ONE batch and says which messages are personal, and which
     * tags describe each one.
     *   batchMessage  the numbered list from MemoryPrompts.buildBatchMessage()
     *   batchSize     how many messages the list has (valid numbers 1..batchSize)
     *   thinking      the "memory thinking" switch of the Settings screen
     *   prompt        STEP 7: the extraction system prompt to use (the one the
     *                 user may have edited in Settings). Default: the built-in
     *                 prompt of MemoryPrompts.
     * onDone runs on the main thread with a BatchResult (see its notes).
     *
     * Every attempt first starts a CLEAN conversation with the extraction
     * system prompt, so Gemma never sees the user's chat or an earlier
     * batch. (The price: the system prompt is read again for every batch.)
     * Nothing Gemma writes is shown or saved; only the numbers and the tags
     * in its reply are used.
     */
    fun classifyBatch(
        batchMessage: String,
        batchSize: Int,
        thinking: Boolean,
        prompt: String = MemoryPrompts.EXTRACTION_SYSTEM_PROMPT,
        onDone: (BatchResult) -> Unit
    ) {
        if (state != State.MEMORIZING) {
            onDone(BatchResult(null, false, "The memory job was not started."))
            return
        }

        worker.execute {
            val b = bridge
            if (b == null) {
                onMain { onDone(BatchResult(null, false, "The engine is not started.")) }
                return@execute
            }

            val result: BatchResult = try {
                var items: List<MemoryPrompts.PersonalItem>? = null
                var error: String? = null
                var cancelled = false
                var attempt = 0

                // Try again only when the reply was not understood.
                while (attempt < MEMORY_ATTEMPTS && items == null && error == null && !cancelled) {
                    attempt++

                    // The user may have pressed Cancel between two batches.
                    if (stopRequested) {
                        cancelled = true
                        break
                    }

                    // Clean conversation with the extraction prompt.
                    b.resetChat(prompt.toByteArray(Charsets.UTF_8))

                    // The listener IGNORES the text. It only answers
                    // "keep going" (true) or "stop" (false).
                    val listener = object : ChatListener {
                        override fun onDelta(kind: Int, bytes: ByteArray): Boolean = !stopRequested
                    }
                    val stats = b.generate(
                        batchMessage.toByteArray(Charsets.UTF_8),
                        MemoryPrompts.maxNewTokens(thinking),
                        thinking,
                        listener
                    )

                    when {
                        stopRequested || stats.startsWith("STOPPED") ->
                            cancelled = true
                        stats.startsWith("ERROR") ->
                            error = stats.removePrefix("ERROR:").trim()
                        else ->
                            // lastAnswer() = the FINAL answer only (no thinking text).
                            items = MemoryPrompts.parsePersonalItems(b.lastAnswer(), batchSize)
                    }
                }
                BatchResult(items, cancelled, error)
            } catch (t: Throwable) {
                BatchResult(null, false, "Reading the messages failed: " + (t.message ?: t.javaClass.simpleName))
            }

            onMain { onDone(result) }
        }
    }

    /**
     * Ends the memory job. ALWAYS call it (after success, error or cancel).
     * It puts the chat that should be open back into Gemma's head and sets
     * the state back to READY.
     *   systemPrompt  the user's normal system prompt
     *   history       the messages of the chat that stays open (oldest
     *                 first, same list as for openChat). Give an EMPTY
     *                 list when the chat is going away (it was deleted):
     *                 Gemma then starts a fresh chat.
     *   thinking      the normal thinking setting (reply reserve, as in openChat)
     * onDone runs on the main thread.
     */
    fun finishMemorizing(
        systemPrompt: String,
        history: List<HistoryItem>,
        thinking: Boolean,
        onDone: () -> Unit
    ) {
        if (state != State.MEMORIZING) {
            onDone()
            return
        }
        worker.execute {
            val b = bridge
            try {
                if (b != null) replayOnWorker(b, systemPrompt, history, thinking)
            } catch (t: Throwable) {
                // The next generate() reports real problems.
            }
            setState(State.READY)
            onMain { onDone() }
        }
    }

    /** PHASE 4: cancels the running batch (same as stop). Safe any time. */
    fun cancelMemorizing() {
        stop()
    }

    // -----------------------------------------------------------------
    // 4) stop / newChat / unload
    // -----------------------------------------------------------------

    /** Asks the running generate() to finish. Safe to call any time. */
    fun stop() {
        stopRequested = true
        bridge?.stop()   // native stop() only sets a flag, safe from any thread
    }

    /** Starts a fresh conversation inside the engine (the model stays loaded). */
    fun newChat(systemPrompt: String) {
        worker.execute {
            try {
                bridge?.resetChat(systemPrompt.toByteArray(Charsets.UTF_8))
            } catch (t: Throwable) {
                // Nothing to do: the next generate() reports real problems.
            }
        }
    }

    /** Frees the model from RAM. */
    fun unload() {
        worker.execute {
            try {
                bridge?.unload()
            } catch (t: Throwable) {
                // ignore
            }
            loadedContext = 0
            setState(State.NOT_LOADED)
        }
    }
}
