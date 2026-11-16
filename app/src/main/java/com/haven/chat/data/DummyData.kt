// =====================================================================
// DummyData.kt  (app/src/main/java/com/haven/chat/data/)
// WHAT: FAKE chats and messages, copied from your Stitch designs, so we
//       can see the screens filled with content before the AI exists.
// STATUS: TEMPORARY. In Phase 2/3 the real engine and database replace
//         this file. Delete it when they do.
// =====================================================================
package com.haven.chat.data

// "object" = a single shared instance (like a toolbox with one copy).
// Screens read the lists with DummyData.messages and DummyData.chats.
object DummyData {

    // Fake conversation for the chat screen.
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            id = 1,
            role = Role.USER,
            text = "Can gemma4-e2b run locally on my Pixel device without internet access?"
        ),
        ChatMessage(
            id = 2,
            role = Role.ASSISTANT,
            // The text inside the "Thinking process" box (collapsible).
            thinking = "Checking device capabilities. Looking for network use. " +
                    "Confirming the model runs from local storage.",
            // The visible answer.
            text = "Analyzed device capabilities, verified NPU acceleration support, " +
                    "and confirmed zero cloud telemetry. Yes, gemma4-e2b runs 100% " +
                    "locally on your device using on-chip quantization and the NNAPI " +
                    "backend. No packets leave your phone.",
            tokensPerSecond = 35.4f,
            tokenCount = 311
        ),
        ChatMessage(
            id = 3,
            role = Role.USER,
            text = "That is fantastic. Please summarize local storage usage for offline embeddings."
        )
    )

    // Fake chat history for the side drawer (group "Today").
    val chats: List<ChatSummary> = listOf(
        ChatSummary(id = 1, title = "Local NPU acceleration check", group = "Today"),
        ChatSummary(id = 2, title = "Offline embeddings storage", group = "Today"),
        ChatSummary(id = 3, title = "Android kernel optimization", group = "Today")
    )
}
