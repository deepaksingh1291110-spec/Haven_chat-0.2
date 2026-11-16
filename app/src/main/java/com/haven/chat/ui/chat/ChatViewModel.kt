// =====================================================================
// ChatViewModel.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The "brain" of the chat screen. It OWNS the data (message list,
//       model status) and the actions (send, new chat).
//       ChatScreen only draws what this class holds.
// WHY A VIEWMODEL: Android destroys and rebuilds screens (for example
//       when you rotate the phone). A ViewModel survives that, so the
//       chat is not lost.
// STATUS: TEMPORARY FAKE AI. sendMessage() waits 1.5 seconds and adds a
//       canned reply. In Phase 2 we replace ONLY that part with the real
//       llama.cpp engine. The rest of the app stays the same.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haven.chat.data.ChatMessage
import com.haven.chat.data.DummyData
import com.haven.chat.data.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    // ----- STATE (data the screen shows) -----
    // "mutableStateOf" = when this value changes, Compose redraws.
    // "private set" = only this class can change it; screens can only read.

    // The messages in the open chat. Starts with the dummy conversation.
    var messages by mutableStateOf(DummyData.messages)
        private set

    // Text of the green chip under the title.
    var modelStatus by mutableStateOf("gemma4-e2b • Ready")
        private set

    // True while the AI is "answering". Stops double sending.
    private var isGenerating = false

    // Counter to give every new message a unique id.
    // It starts after the biggest id in the dummy data.
    private var nextId: Long = (messages.maxOfOrNull { it.id } ?: 0L) + 1L

    // ----- ACTIONS (things the user can do) -----

    // Called when the user taps send.
    fun sendMessage(text: String) {
        // Ignore empty text, or if the AI is still busy.
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

        // 2) Fake the AI answer.
        // viewModelScope.launch = run in the background without freezing
        // the screen. It is cancelled automatically if the ViewModel dies.
        isGenerating = true
        modelStatus = "gemma4-e2b • Thinking..."
        viewModelScope.launch {
            delay(1500)   // pretend the AI needs 1.5 seconds

            messages = messages + ChatMessage(
                id = nextId++,
                role = Role.ASSISTANT,
                thinking = "This is a fake thinking step. The real AI will fill this later.",
                text = "This is a test reply. The real Gemma model will answer here in Phase 2.",
                tokensPerSecond = 7.4f,
                tokenCount = 18
            )
            modelStatus = "gemma4-e2b • Ready"
            isGenerating = false
        }
    }

    // Called when the user taps the pencil icon (start a fresh chat).
    fun startNewChat() {
        // Do nothing while the AI is busy, to avoid a reply landing in the wrong chat.
        if (isGenerating) return
        messages = emptyList()
    }
}
