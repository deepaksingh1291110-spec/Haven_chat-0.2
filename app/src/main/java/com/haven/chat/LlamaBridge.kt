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
 * PHASE 3 CHANGE (the only change in this file):
 *   New function countTokens(). It is the Kotlin side of the C++ function
 *   Java_com_haven_chat_LlamaBridge_countTokens that already exists in
 *   haven_engine.cpp. Without this line the C++ function can never be
 *   called. Nothing else in this file was changed.
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
    // Only ONE search model is loaded at a time, and only while the main model is
    // unloaded (RAM). Order: unload() -> loadAux(embedder) -> embed(...) -> unloadAux()
    //                        -> loadAux(reranker) -> rerankScore(...) -> unloadAux()

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

    companion object {
        const val KIND_EMBEDDER = 1
        const val KIND_RERANKER = 2

        init {
            System.loadLibrary("haven-engine")
        }
    }
}
