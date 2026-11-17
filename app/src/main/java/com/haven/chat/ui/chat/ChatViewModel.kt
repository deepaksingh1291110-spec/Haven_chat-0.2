// =====================================================================
// ChatViewModel.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The "brain" of the chat screen. It OWNS the data (message list,
//       model status) and the actions (send, new chat).
//       ChatScreen only draws what this class holds.
// WHY A VIEWMODEL: Android destroys and rebuilds screens (for example
//       when you rotate the phone). A ViewModel survives that, so the
//       chat is not lost.
//
// PHASE 2: the FAKE AI is gone. sendMessage() now asks the real Gemma
//       model through EngineManager and STREAMS the reply: the answer
//       bubble grows word by word, the "Thinking process" box fills
//       up, and at the end the speed line (tokens, tokens/second) is
//       filled with the real numbers.
//
// STILL TEMPORARY:
//   - The chat starts with the DUMMY conversation. Gemma does not know
//     it. Tap "New chat" (pencil) to start a clean chat.
//   - Chats are not saved yet (Phase 3).
//   - No Agent and no long-term memory yet (Phase 4).
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.haven.chat.data.ChatMessage
import com.haven.chat.data.DummyData
import com.haven.chat.data.Role
import com.haven.chat.engine.EngineManager
import com.haven.chat.ui.settings.SettingsViewModel

class ChatViewModel : ViewModel() {

    // ----- STATE (data the screen shows) -----
    // "mutableStateOf" = when this value changes, Compose redraws.
    // "private set" = only this class can change it; screens can only read.

    // The messages in the open chat. Starts with the dummy conversation.
    var messages by mutableStateOf(DummyData.messages)
        private set

    // Text of the chip under the title, for example "gemma4-e2b-q4_0 • Ready".
    // It is a "getter": it is calculated every time the screen reads it.
    // EngineManager.state is Compose state, so when the engine status
    // changes (Loading, Ready, Thinking...) the chip redraws by itself.
    val modelStatus: String
        get() = EngineManager.modelName + " \u2022 " + EngineManager.statusText()

    // Longest answer we allow (in tokens). Phase 5 can make it a setting.
    private val maxNewTokens = 1024

    // Counter to give every new message a unique id.
    // It starts after the biggest id in the dummy data.
    private var nextId: Long = (messages.maxOfOrNull { it.id } ?: 0L) + 1L

    // True while Gemma is writing (read from the engine, the single truth).
    private val isGenerating: Boolean
        get() = EngineManager.state == EngineManager.State.THINKING

    // ----- ACTIONS (things the user can do) -----

    // Called when the user taps send.
    fun sendMessage(text: String) {
        // Ignore empty text, or if Gemma is still busy.
        if (text.isBlank() || isGenerating) return

        // 1) Add the user's message to the list.
        // "messages + x" makes a NEW list with x at the end.
        // (Compose notices a new list; changing an old list in place
        //  would NOT redraw.)
        messages = messages + ChatMessage(
            id = nextId++,
            role = Role.USER,
            text = text
        )

        // 2) Add an EMPTY answer bubble. The streamed text is poured
        //    into it while Gemma writes. We remember its id.
        val answerId = nextId++
        messages = messages + ChatMessage(
            id = answerId,
            role = Role.ASSISTANT,
            text = ""
        )

        // 3) Collect the pieces that arrive while Gemma writes.
        val answerSoFar = StringBuilder()
        val thinkingSoFar = StringBuilder()

        // The thinking switch comes from the Settings screen.
        val thinkingOn = SettingsViewModel.active.thinkingEnabled

        // 4) Ask the real engine. All three callbacks run on the main
        //    thread, so changing "messages" here is safe.
        EngineManager.generate(
            userText = text.trim(),
            maxNewTokens = maxNewTokens,
            thinking = thinkingOn,

            // A piece of THINKING text arrived.
            onThinking = { piece ->
                thinkingSoFar.append(piece)
                updateMessage(answerId) { it.copy(thinking = thinkingSoFar.toString()) }
            },

            // A piece of ANSWER text arrived.
            onAnswer = { piece ->
                answerSoFar.append(piece)
                updateMessage(answerId) { it.copy(text = answerSoFar.toString()) }
            },

            // Gemma finished (or was stopped, or failed).
            onDone = { result ->
                updateMessage(answerId) { old ->
                    when {
                        // A problem: show it in plain words inside the bubble.
                        result.error != null ->
                            old.copy(text = result.error)

                        // Stopped by the user and nothing was written.
                        result.answer.isEmpty() && result.stopped ->
                            old.copy(text = "(stopped)")

                        // Normal end (also covers "stopped after some text").
                        else -> old.copy(
                            text = result.answer.ifEmpty { old.text },
                            thinking = result.thinking.ifEmpty { null },
                            tokensPerSecond = result.tokensPerSecond,
                            tokenCount = result.tokenCount
                        )
                    }
                }
            }
        )
    }

    // Called when the user wants to stop the answer. (No button for it
    // in the design yet; Phase 5 can add one.)
    fun stopGenerating() {
        EngineManager.stop()
    }

    // Called when the user taps the pencil icon (start a fresh chat).
    fun startNewChat() {
        // Do nothing while Gemma is busy, to avoid a reply landing in the wrong chat.
        if (isGenerating) return
        messages = emptyList()
        // Also make the engine forget the old conversation.
        EngineManager.newChat(SettingsViewModel.systemPromptOf(SettingsViewModel.active))
    }

    // Stop writing if this screen is destroyed for good.
    override fun onCleared() {
        EngineManager.stop()
        super.onCleared()
    }

    // ----- HELPER -----

    // Changes ONE message (found by id) and keeps all the others.
    // It builds a NEW list, so Compose redraws.
    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        messages = messages.map { if (it.id == id) change(it) else it }
    }
}
