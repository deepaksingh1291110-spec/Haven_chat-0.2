package com.haven.chat

/**
 * Receives Gemma's reply piece by piece while it is written.
 * kind is ANSWER (text for the user) or THINKING (thinking text).
 * Return false to stop generating.
 */
interface ChatListener {
    fun onDelta(kind: Int, bytes: ByteArray): Boolean

    companion object {
        const val ANSWER = 0
        const val THINKING = 1
    }
}

/** A tool Gemma wants to call. argumentsJson is the arguments as JSON text. */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/**
 * Kotlin side of the native engine (haven_engine.cpp).
 * Create ONE instance and call everything from ONE background thread,
 * except stop(), which may be called from any thread.
 *
 * PHASE 3 CHANGE:
 *   New function countTokens(). It is the Kotlin side of the C++ function
 *   Java_com_haven_chat_LlamaBridge_countTokens that already exists in
 *   haven_engine.cpp. Without this line the C++ function can never be
 *   called.
 *
 * STEP 7 CHANGE (marked "STEP 7"): OVERLAPPED LOADING.
 *   Four new functions: prefetchAux, finishAux, prefetchMain, finishMain.
 *   They are the Kotlin side of the C++ functions with the same names. A
 *   "prefetch" starts loading a model on a background thread inside the
 *   engine and returns at once; the matching "finish" waits until that model
 *   is ready and makes it the active one. See the notes in haven_engine.cpp
 *   and in the "overlapped loading" part below.
 */
class LlamaBridge {

    /** Call once at app start with applicationInfo.nativeLibraryDir. */
    external fun init(nativeLibDir: String)

    /** Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create. */
    external fun loadModel(path: String, nCtx: Int, nThreads: Int): Int

    /** Change the CPU thread count without reloading the model. */
    external fun setThreads(nThreads: Int)

    external fun systemInfo(): String

    /** Start a new conversation. systemPrompt is UTF-8 bytes (may be empty). */
    external fun resetChat(systemPrompt: ByteArray)

    /**
     * Sends one user message (UTF-8 bytes) and streams Gemma's reply to listener.
     * With an empty userText nothing is added: Gemma continues after a tool result.
     * Returns a stats line, or text starting with "ERROR:".
     * Afterwards use lastAnswer(), lastThinking() and lastToolCalls().
     */
    external fun generate(
        userText: ByteArray,
        maxNew: Int,
        thinking: Boolean,
        listener: ChatListener
    ): String

    external fun stop()

    external fun unload()

    /** Last warnings and errors printed by llama.cpp. */
    external fun lastLog(): String

    // ---------------------------------------------------------------- tools and history

    /** Removes all tools. Without tools Gemma just chats. */
    external fun clearTools()

    /**
     * Adds a tool Gemma may call. parametersJson is a JSON schema, for example
     * {"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
     */
    external fun addTool(name: String, description: ByteArray, parametersJson: ByteArray)

    /** Adds an old message to the conversation without generating (role: "user" or "assistant"). */
    external fun appendMessage(role: String, content: ByteArray)

    /** Adds the result of a tool call. Then call generate with an empty text. */
    external fun addToolResult(callId: String, name: String, content: ByteArray)

    // PHASE 3 (NEW) ------------------------------------------------------------

    /**
     * Counts EXACTLY how many tokens a text has for the loaded main model
     * (Gemma's own tokenizer). Text is UTF-8 bytes.
     * Returns -1 when no model is loaded (the caller then uses a safe guess).
     * WHY: when an old chat is opened, the app adds old messages newest to
     * oldest until the context window is full. It needs exact sizes.
     * Like the other engine calls: ONE background thread only.
     */
    external fun countTokens(text: ByteArray): Int

    // END PHASE 3 --------------------------------------------------------------

    private external fun lastContent(): ByteArray
    private external fun lastReasoning(): ByteArray
    private external fun toolCallCount(): Int
    private external fun toolCallName(i: Int): String
    private external fun toolCallId(i: Int): String
    private external fun toolCallArgs(i: Int): ByteArray

    /** The answer text of the last generate(). */
    fun lastAnswer(): String = String(lastContent(), Charsets.UTF_8)

    /** The thinking text of the last generate(). */
    fun lastThinking(): String = String(lastReasoning(), Charsets.UTF_8)

    /** The tools Gemma wants to call after the last generate() (usually none or one). */
    fun lastToolCalls(): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for (i in 0 until toolCallCount()) {
            out.add(ToolCall(toolCallId(i), toolCallName(i), String(toolCallArgs(i), Charsets.UTF_8)))
        }
        return out
    }

    // ---------------------------------------------------------------- search models
    // Only ONE search model is ACTIVE at a time, and only while the main model is
    // unloaded (RAM). Order: unload() -> loadAux(embedder) -> embed(...) -> unloadAux()
    //                        -> loadAux(reranker) -> rerankScore(...) -> unloadAux()
    // STEP 7: with the overlapped loading below, the NEXT model can already be
    // loading in the background while the active one works.

    /**
     * Loads a search model. kind: KIND_EMBEDDER or KIND_RERANKER.
     * Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create,
     * 3 = unknown kind, 4 = the main model is still loaded (call unload() first).
     */
    external fun loadAux(path: String, kind: Int, nThreads: Int): Int

    /** One line of facts about the loaded search model (vector size, pooling type). */
    external fun auxInfo(): String

    /** Embeds one text (UTF-8 bytes). Returns a normalized vector, or null on error. */
    external fun embed(text: ByteArray): FloatArray?

    /** Relevance score of a document for a query (UTF-8 bytes). Higher = better. -1e6 = error. */
    external fun rerankScore(query: ByteArray, doc: ByteArray): Float

    external fun unloadAux()

    // STEP 7 (NEW) -------------------------------------------------------------
    // ---------------------------------------------------------------- overlapped loading
    // Only ONE background load can run at a time. A background load that is never
    // collected is thrown away by the next loadModel() or unload().
    //
    // Typical order for a deep search (all calls from the one engine thread):
    //   unload()                               Gemma goes away (the chat is kept)
    //   prefetchAux(reranker)                  the reranker starts loading in the background
    //   loadAux(embedder) + embed(...)         the embedder works meanwhile
    //   finishAux()                            the embedder goes, the reranker is active
    //   prefetchMain(gemma)                    Gemma starts loading in the background
    //   rerankScore(...) many times            the reranker works meanwhile
    //   unloadAux() + finishMain()             the reranker goes, Gemma is the main model again

    /**
     * Starts loading a SEARCH model in the background and returns at once.
     * kind: KIND_EMBEDDER or KIND_RERANKER.
     * Returns 0 = started, 3 = unknown kind, 4 = the main model is still loaded,
     * 5 = a background load is already running.
     * The search model that is active now is not touched.
     */
    external fun prefetchAux(path: String, kind: Int, nThreads: Int): Int

    /**
     * Waits until the background-loaded search model is ready, frees the search model
     * that was active, and makes the new one the active one.
     * Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create,
     * 8 = the pending model is not a search model, 9 = nothing was started.
     */
    external fun finishAux(): Int

    /**
     * Starts loading the MAIN model (Gemma) in the background and returns at once.
     * The conversation and the tools were kept while Gemma was away.
     * Returns 0 = started, 4 = the main model is already loaded, 5 = a background
     * load is already running.
     */
    external fun prefetchMain(path: String, nCtx: Int, nThreads: Int): Int

    /**
     * Waits until the background-loaded main model is ready and makes it the main model.
     * Returns 0 = ok, 1 = model file failed to load, 2 = context failed to create,
     * 8 = the pending model is not a main model, 9 = nothing was started.
     * After 0, the next generate() reads the whole conversation again (empty KV cache).
     */
    external fun finishMain(): Int

    // END STEP 7 ---------------------------------------------------------------

    companion object {
        const val KIND_EMBEDDER = 1
        const val KIND_RERANKER = 2

        init {
            System.loadLibrary("haven-engine")
        }
    }
}
