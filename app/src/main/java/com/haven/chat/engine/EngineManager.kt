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
// NOT USED HERE (on purpose, Phase 2 first version):
//       Agent, tools, memory. Plain chat with Gemma only.
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

    /** Short text for the status chip, for example "Ready" or "Loading...". */
    fun statusText(): String = when (state) {
        State.NOT_LOADED -> "Not loaded"
        State.LOADING -> "Loading..."
        State.READY -> "Ready"
        State.THINKING -> if (answering) "Writing..." else "Thinking..."
        State.ERROR -> "Error"
    }

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

    // Run something on the main thread (now if we are already there).
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // Change the state from any thread.
    private fun setState(newState: State, message: String = "") {
        onMain {
            state = newState
            errorMessage = message
            // Any state other than THINKING means the answer phase is over.
            if (newState != State.THINKING) answering = false
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
     */
    fun load(
        path: String,
        contextSize: Int,
        threads: Int,
        systemPrompt: String,
        onDone: (String?) -> Unit
    ) {
        if (state == State.LOADING || state == State.THINKING) {
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
                    // Start an empty conversation with the system prompt.
                    b.resetChat(systemPrompt.toByteArray(Charsets.UTF_8))
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
    // 3) generate: ask Gemma one question and stream the answer
    // -----------------------------------------------------------------
    /**
     * onThinking / onAnswer get small pieces of text while Gemma writes
     * (on the main thread). onDone runs once at the end with the full Result.
     */
    fun generate(
        userText: String,
        maxNewTokens: Int,
        thinking: Boolean,
        onThinking: (String) -> Unit,
        onAnswer: (String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        if (state != State.READY) {
            val msg = if (state == State.THINKING) "Gemma is still answering."
                      else "No model is loaded. Open Settings and tap Load Model."
            onDone(Result("", "", null, null, false, msg))
            return
        }

        stopRequested = false
        answering = false            // a new turn starts in the "thinking" phase
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
                val stats = b.generate(userText.toByteArray(Charsets.UTF_8), maxNewTokens, thinking, listener)
                when {
                    stats.startsWith("ERROR") ->
                        Result("", "", null, null, false, stats.removePrefix("ERROR:").trim())
                    stats.startsWith("STOPPED") ->
                        Result("", "", null, null, true, null)
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

            setState(State.READY)
            onMain { onDone(result) }
        }
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
            setState(State.NOT_LOADED)
        }
    }
}
