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
//   4) finishMemorizing(): puts the open chat back into Gemma's head
//      (like after a title) and sets the state back to READY.
//   5) cancelMemorizing(): stops the running batch (same as stop()).
//
// PHASE 4, STEP 6 CHANGES (marked "STEP 6"): LIGHT SEARCH (a TOOL for Gemma).
//   generate() can give Gemma a TOOL (a ToolRunner, here: the memory
//   search_memory tool, see MemorySearch.kt). How a normal answer goes:
//     1) The tool is registered, Gemma reads the question and writes the
//        answer. Nothing else happens. (Most answers.)
//     2) Or Gemma decides it needs the tool and, instead of an answer, asks
//        for a CALL with arguments. Then the engine:
//          - tells the screen to start the bubble again (onRestart), because
//            what was streamed so far was only the lead-in to the call,
//          - runs the tool (still on the worker thread) and gives the result
//            text back to Gemma (addToolResult),
//          - asks Gemma to continue (generate with an empty text). Gemma
//            now reads the result and writes the real answer.
//        Gemma may ask for a tool again (to narrow the search). At most
//        MAX_TOOL_ROUNDS calls are served per answer; if Gemma still asks
//        after that, the answer ends with a plain-words problem text.
//   - The tools are registered ONLY for this call and removed afterwards
//     (also after errors), so the other jobs (title, memory extraction)
//     never see them.
//   - searchingMemory (Compose state) is true while a tool runs; the chip
//     then says "Searching memories...".
//
// PHASE 4, STEP 7 CHANGES (marked "STEP 7"):
//   A) TAGS: classifyBatch() returns the tags Gemma wrote (BatchResult.items)
//      and takes the extraction prompt as a parameter (the user can edit it
//      in Settings).
//   B) MODEL SWAP FOR HEAVY SEARCH. The engine holds ONE model at a time (RAM).
//      To use the two search models (embedder bge-m3, reranker
//      bge-reranker-v2-m3) Gemma must be UNLOADED first and loaded AGAIN
//      afterwards. What the engine now offers:
//        - the engine remembers how Gemma was loaded (path, context,
//          threads): loadedPath / loadedContext / loadedThreads,
//        - class SearchSwap: the handle that a deep search tool gets. It
//          frees Gemma, loads the embedder or the reranker (one at a time),
//          embeds a text or scores a document, and when it is closed it
//          loads Gemma AGAIN. The conversation (what the chat said so far,
//          including Gemma's tool call) is KEPT by the C++ engine while the
//          model is out; only the model's memory of it (the KV cache) is
//          lost, so Gemma reads the conversation again afterwards.
//        - ToolRunner.runDeep: a tool that needs the swap (the light tool
//          keeps using "run").
//        - generate(tools = ...): several tools at once (for example the
//          light search AND the deep search). Gemma picks one by name.
//        - embedTexts(): the same swap for MAKING VECTORS after memories were
//          saved (state MEMORIZING): frees Gemma, loads the embedder, embeds
//          the texts, loads Gemma again.
//      If Gemma cannot be loaded again (for example not enough RAM), the
//      engine goes to State.ERROR with a plain-words text; tapping the chip
//      loads the model again.
//      While Gemma is out, exact token counting is not possible; a safe
//      guess (1 token per 3 characters) is used (see tokensOf).
//
// AFTER STEP 7 (marked "STOP FIX"): STOP DURING A TOOL SEARCH.
//   Problem: the Stop button was LOST while a memory search ran. The reason:
//   after the tool, generate() was called again, and the C++ generate()
//   clears its stop flag when it starts. So Gemma re-read the whole prompt
//   and wrote a full answer although the user had pressed Stop.
//   Fix (three small parts):
//     1) After the tools have run, the engine checks stopRequested. If the
//        user pressed Stop, it does NOT call generate() again. The answer
//        ends as "stopped" (the screen shows "(stopped)", nothing is saved).
//     2) A tool call that has not started yet when Stop is pressed is not
//        run; Gemma is just told "the user pressed Stop" (the tool result
//        is still added, so the conversation stays well-formed).
//     3) SearchSwap has a new property isStopRequested, so a deep search
//        (DeepSearch.kt) can end its slow loop (the reranker) at once.
//   What Stop CANNOT skip: the swap still ends by loading Gemma AGAIN. The
//   app needs the model back, so that step always runs.
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

    // STEP 6: true while a memory search tool is running (between Gemma's
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
    // STEP 7: result of embedTexts()
    // -----------------------------------------------------------------
    /**
     * vectors   one vector per text, in the same order, when ALL texts were
     *           embedded. null when the job did not finish (see below).
     * cancelled true if the user cancelled.
     * error     a plain-words problem, or null. (Also used when Gemma could
     *           not be loaded again: then the engine state is ERROR.)
     *
     * Vectors are only handed over when the whole job worked, so the caller
     * never saves half a batch.
     */
    class EmbedResult(
        val vectors: List<FloatArray>?,
        val cancelled: Boolean,
        val error: String?
    )

    // -----------------------------------------------------------------
    // STEP 7: the model swap handle (used by deep search and embedTexts)
    // -----------------------------------------------------------------
    /**
     * The handle that a deep search gets for the time of ONE tool call. It
     * lives ONLY on the engine's worker thread and ONLY while the tool runs.
     *
     * What it does, in order:
     *   1) useEmbedder(path) / useReranker(path): frees Gemma (the first
     *      time), frees the other search model if one is loaded, and loads
     *      the one asked for. Only ONE model is in RAM at any time.
     *      Returns null when it worked, or a plain-words problem text.
     *   2) embed(text) / rerank(query, document): use the loaded search model.
     *   3) When the tool returns, EngineManager closes the swap: the search
     *      model is freed and Gemma is loaded AGAIN (the conversation is
     *      still there; see the notes at the top of this file).
     * The tool never has to load or free Gemma itself.
     */
    class SearchSwap internal constructor(
        private val b: LlamaBridge,
        private val threads: Int,
        private val reloadMain: () -> String?
    ) {
        // Gemma has been freed by this swap (so it must be loaded again).
        private var mainFreed = false

        // A search model is loaded right now.
        private var auxLoaded = false

        /**
         * STOP FIX: true when the user has pressed Stop. A slow tool (the deep
         * search) checks this between its steps and ends early. The swap is
         * still closed normally afterwards (Gemma is loaded again).
         */
        val isStopRequested: Boolean
            get() = stopRequested

        // Frees what is loaded and loads the search model [path] of this [kind]
        // (LlamaBridge.KIND_EMBEDDER or KIND_RERANKER). Returns null = ok.
        private fun loadAux(path: String, kind: Int): String? {
            if (!mainFreed) {
                b.unload()                      // frees Gemma, KEEPS the conversation
                mainFreed = true
            }
            if (auxLoaded) {
                b.unloadAux()
                auxLoaded = false
            }
            if (path.isBlank() || !File(path).exists()) {
                return "Search model file not found: $path"
            }
            return when (val rc = b.loadAux(path, kind, threads)) {
                0 -> {
                    auxLoaded = true
                    null
                }
                1 -> "The search model file could not be loaded (wrong file or not enough RAM)."
                2 -> "The search model loaded but its context could not be created."
                3 -> "Unknown search model kind."
                4 -> "Gemma is still loaded, so the search model could not be loaded."
                else -> "Loading the search model failed (code $rc)."
            }
        }

        /** Loads the embedder (bge-m3), freeing Gemma first. null = ok, else the problem. */
        fun useEmbedder(path: String): String? = loadAux(path, LlamaBridge.KIND_EMBEDDER)

        /** Loads the reranker, freeing Gemma or the embedder first. null = ok, else the problem. */
        fun useReranker(path: String): String? = loadAux(path, LlamaBridge.KIND_RERANKER)

        /**
         * The vector of [text] (normalized, 1024 numbers for bge-m3), or null
         * when no embedder is loaded or the text could not be embedded.
         */
        fun embed(text: String): FloatArray? {
            if (!auxLoaded) return null
            return b.embed(text.toByteArray(Charsets.UTF_8))
        }

        /**
         * How relevant [document] is for [query] (higher = more relevant), or
         * null when no reranker is loaded or the pair could not be scored.
         */
        fun rerank(query: String, document: String): Float? {
            if (!auxLoaded) return null
            val score = b.rerankScore(query.toByteArray(Charsets.UTF_8), document.toByteArray(Charsets.UTF_8))
            return if (score <= -1e5f) null else score      // -1e6 is the engine's "error"
        }

        // Called by EngineManager when the tool is done: frees the search
        // model and loads Gemma again. Returns null = ok, or the problem.
        internal fun close(): String? {
            if (auxLoaded) {
                try {
                    b.unloadAux()
                } catch (t: Throwable) {
                    // nothing to do
                }
                auxLoaded = false
            }
            if (!mainFreed) return null
            mainFreed = false
            return reloadMain()
        }
    }

    // -----------------------------------------------------------------
    // STEP 6 / STEP 7: a tool that Gemma may call during generate()
    // -----------------------------------------------------------------
    /**
     * name, description, parametersJson  what Gemma reads about the tool
     *     (the parameters are a JSON schema). For the light search see
     *     MemorySearch.kt.
     * run  (light tools) does the work when Gemma calls the tool. It is
     *     called ON THE WORKER THREAD (so it may block for a moment, but
     *     must not touch the screen). It gets:
     *       argsJson     the arguments of the call, as JSON text
     *       tokenBudget  how many tokens the result text may use
     *       countTokens  counts the tokens of a text (exact with Gemma's
     *                    tokenizer; a safe guess while Gemma is unloaded)
     *     and returns the result text that Gemma reads. It should never
     *     throw; if it does, Gemma is told that the tool failed.
     * runDeep  STEP 7: for a tool that needs the search models. The same,
     *     plus a SearchSwap (see above): Gemma is unloaded while the tool
     *     uses the swap, and loaded again afterwards. If a tool has runDeep,
     *     it is used and "run" is ignored.
     * A tool must have run or runDeep.
     */
    class ToolRunner(
        val name: String,
        val description: String,
        val parametersJson: String,
        val run: ((argsJson: String, tokenBudget: Int, countTokens: (String) -> Int) -> String)? = null,
        val runDeep: ((argsJson: String, tokenBudget: Int, countTokens: (String) -> Int, swap: SearchSwap) -> String)? = null
    )

    // What running one tool gave: the text for Gemma, and a problem text if
    // Gemma could not be loaded again afterwards.
    private class ToolOutcome(val text: String, val reloadError: String?)

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

    // STEP 7: how Gemma was loaded, so it can be loaded AGAIN after a model
    // swap. (loadedContext is above.)
    @Volatile private var loadedPath = ""
    @Volatile private var loadedThreads = 4

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
     *
     * STEP 7: the path and the thread count are remembered, so Gemma can be
     * loaded again after a model swap.
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
                    loadedPath = path                 // STEP 7: remembered for a reload
                    loadedThreads = threads           // STEP 7
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
    // says "no model" (-1, for example while Gemma is unloaded for a model
    // swap), use a safe guess: 1 token per 3 characters.
    private fun tokensOf(b: LlamaBridge, text: String): Int {
        if (text.isEmpty()) return 0
        val n = b.countTokens(text.toByteArray(Charsets.UTF_8))
        return if (n >= 0) n else text.length / 3 + 1
    }

    // STEP 7: loads Gemma AGAIN after a model swap, with the same path,
    // context and threads as before. Runs ON THE WORKER THREAD.
    // The C++ engine kept the conversation while the model was out, so
    // nothing else has to be put back. Returns null = ok, or a plain-words
    // problem text.
    private fun reloadMainOnWorker(b: LlamaBridge): String? {
        return try {
            val rc = b.loadModel(loadedPath, loadedContext, loadedThreads)
            if (rc == 0) {
                null
            } else {
                "Gemma could not be loaded again after the search (code $rc). " +
                        b.lastLog().takeLast(150).trim()
            }
        } catch (t: Throwable) {
            "Gemma could not be loaded again after the search: " + (t.message ?: t.javaClass.simpleName)
        }
    }

    // STEP 7: runs ONE tool call. Runs ON THE WORKER THREAD.
    // A light tool just runs. A deep tool gets a SearchSwap, and after it
    // returns the swap is closed: the search model is freed and Gemma is
    // loaded again (reloadError tells if that failed).
    private fun runToolOnWorker(
        b: LlamaBridge,
        runner: ToolRunner,
        argsJson: String,
        tokenBudget: Int
    ): ToolOutcome {
        val counter: (String) -> Int = { text -> tokensOf(b, text) }

        val deep = runner.runDeep
        if (deep != null) {
            val swap = SearchSwap(b, loadedThreads) { reloadMainOnWorker(b) }
            val text = try {
                deep(argsJson, tokenBudget, counter, swap)
            } catch (t: Throwable) {
                "The deep search failed. Tell the user you could not look."
            }
            val reloadError = swap.close()
            return ToolOutcome(text, reloadError)
        }

        val light = runner.run
        val text = try {
            if (light != null) light(argsJson, tokenBudget, counter) else "This tool cannot run."
        } catch (t: Throwable) {
            "The search failed. Tell the user you could not look."
        }
        return ToolOutcome(text, null)
    }

    // -----------------------------------------------------------------
    // 3) generate: ask Gemma one question and stream the answer
    // -----------------------------------------------------------------
    /**
     * onThinking / onAnswer get small pieces of text while Gemma writes
     * (on the main thread). onDone runs once at the end with the full Result.
     *
     * STEP 6 / STEP 7 (the new parameters have default values):
     *   tool       a tool Gemma may call during this answer, or null for none.
     *   tools      STEP 7: more tools (for example the deep search) next to
     *              [tool]. Gemma picks one by its name.
     *   onRestart  called (main thread) when Gemma has asked for a tool and
     *              the answer starts again after the tool result. The caller
     *              should throw away the thinking and answer pieces it
     *              collected so far: they were only the lead-in to the
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
        onRestart: () -> Unit = {},
        tools: List<ToolRunner> = emptyList()
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

            // STEP 7: all the tools of this answer, and a problem text if
            // Gemma could not be loaded again after a deep search.
            val allTools = listOfNotNull(tool) + tools
            var swapFailure: String? = null

            val result: Result = try {
                // STEP 6: the tools are registered only for this answer. First
                // remove anything left over (the tool list is global in C++).
                b.clearTools()
                for (t in allTools) {
                    b.addTool(
                        t.name,
                        t.description.toByteArray(Charsets.UTF_8),
                        t.parametersJson.toByteArray(Charsets.UTF_8)
                    )
                }

                var stats = b.generate(userText.toByteArray(Charsets.UTF_8), maxNewTokens, thinking, listener)

                // STEP 6: the tool loop. As long as Gemma ends its turn with a
                // CALL (and not with an answer), run the tool, give Gemma the
                // result and let it continue.
                var rounds = 0
                var gaveUp = false
                while (true) {
                    if (allTools.isEmpty() || stopRequested ||
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
                        val runner = allTools.firstOrNull { it.name == call.name }
                        val text: String
                        if (runner == null) {
                            text = "There is no tool with this name. Answer without it."
                        } else if (stopRequested) {
                            // STOP FIX: the user pressed Stop before this call
                            // started (for example during an earlier call of
                            // the same round). Do not run it. The tool result
                            // is still added below, so the conversation stays
                            // well-formed.
                            text = "The user pressed Stop. Do not search any more."
                        } else {
                            // STEP 7: a deep tool frees Gemma and loads it again.
                            val outcome = runToolOnWorker(b, runner, call.argumentsJson, budget)
                            text = outcome.text
                            if (outcome.reloadError != null) swapFailure = outcome.reloadError
                        }
                        // If Gemma could not be loaded again, there is no one
                        // to give the result to.
                        if (swapFailure == null) {
                            b.addToolResult(call.id, call.name, text.toByteArray(Charsets.UTF_8))
                        }
                    }

                    onMain { searchingMemory = false }

                    if (swapFailure != null) break

                    // STOP FIX: the user pressed Stop while the tool ran. Do NOT
                    // call generate() again: the C++ generate() clears its stop
                    // flag when it starts, so Gemma would read the whole prompt
                    // again and write a full answer anyway. End the answer as
                    // "stopped" instead.
                    if (stopRequested) {
                        stats = "STOPPED while searching memories"
                        break
                    }

                    // An EMPTY text: nothing new is added, Gemma reads the tool
                    // result and continues. (After a deep search Gemma reads
                    // the whole conversation again: its KV cache was lost.)
                    stats = b.generate(ByteArray(0), maxNewTokens, thinking, listener)
                }

                val failure = swapFailure
                when {
                    failure != null ->
                        Result("", "", null, null, false, failure)
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

            // STEP 6: take the tools away again, so the other jobs never see them.
            // (Harmless if Gemma is not loaded.)
            try {
                b.clearTools()
            } catch (t: Throwable) {
                // nothing to do
            }

            // STEP 7: if Gemma could not be loaded again, the engine is in
            // ERROR (tapping the chip loads it again); otherwise READY.
            val failureText = swapFailure
            if (failureText != null) setState(State.ERROR, failureText) else setState(State.READY)
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
    // STEP 7: between the last batch and finishMemorizing(), embedTexts()
    // may run (making the search vectors of the new memories).
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
     * STEP 7: makes the search vectors of [texts] with the embedder model.
     * Call it while the state is MEMORIZING (after the batches, before
     * finishMemorizing). What it does, on the worker thread:
     *   1) frees Gemma and loads the embedder ([embedderPath]),
     *   2) embeds the texts one by one (the user can cancel between two),
     *   3) frees the embedder and loads Gemma AGAIN.
     *   embedderPath  the bge-m3 GGUF file (from Settings)
     *   texts         what to embed, one text per memory
     *   onProgress    called on the main thread after each text with how many
     *                 are done so far
     *   onDone        called on the main thread with an EmbedResult. The
     *                 vectors come back only when ALL texts were embedded.
     *
     * If Gemma cannot be loaded again, the engine state becomes ERROR and
     * the result has an error text.
     */
    fun embedTexts(
        embedderPath: String,
        texts: List<String>,
        onProgress: (Int) -> Unit,
        onDone: (EmbedResult) -> Unit
    ) {
        if (state != State.MEMORIZING) {
            onDone(EmbedResult(null, false, "The memory job was not started."))
            return
        }
        if (texts.isEmpty()) {
            onDone(EmbedResult(emptyList(), false, null))
            return
        }

        worker.execute {
            val b = bridge
            if (b == null) {
                onMain { onDone(EmbedResult(null, false, "The engine is not started.")) }
                return@execute
            }

            val swap = SearchSwap(b, loadedThreads) { reloadMainOnWorker(b) }
            val vectors = ArrayList<FloatArray>()
            var error: String? = null
            var cancelled = false

            try {
                error = swap.useEmbedder(embedderPath)
                if (error == null) {
                    for ((index, text) in texts.withIndex()) {
                        if (stopRequested) {
                            cancelled = true
                            break
                        }
                        val vector = swap.embed(text)
                        if (vector == null) {
                            error = "The embedding model could not read one of the memories."
                            break
                        }
                        vectors.add(vector)
                        onMain { onProgress(index + 1) }
                    }
                }
            } catch (t: Throwable) {
                error = "Making the search vectors failed: " + (t.message ?: t.javaClass.simpleName)
            }

            // ALWAYS: free the embedder and load Gemma again.
            val reloadError = swap.close()
            if (reloadError != null) {
                setState(State.ERROR, reloadError)
                onMain { onDone(EmbedResult(null, cancelled, reloadError)) }
                return@execute
            }

            val ok = error == null && !cancelled
            onMain { onDone(EmbedResult(if (ok) vectors else null, cancelled, error)) }
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
