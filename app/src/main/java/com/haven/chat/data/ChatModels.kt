// =====================================================================
// ChatModels.kt  (app/src/main/java/com/haven/chat/data/)
// WHAT: "Data classes" = simple containers that describe the THINGS in
//       our app: a chat message, a chat in the history list.
// WHY:  Every screen uses these same shapes, so UI code stays simple.
// LATER: In Phase 3 the database (SQLite) will save and load these.
// =====================================================================
package com.haven.chat.data

// Who wrote a message. An "enum" is a fixed list of allowed values.
enum class Role {
    USER,       // you (blue bubble on the right)
    ASSISTANT   // Gemma, the AI (dark bubble on the left)
}

// ONE message in a chat.
// "val" = value that never changes after creation.
// "String?" = text that may be missing (null). A "?" means "can be empty".
data class ChatMessage(
    val id: Long,                         // unique number of the message
    val role: Role,                       // USER or ASSISTANT
    val text: String,                     // the visible message text
    val thinking: String? = null,         // AI's thinking steps (only AI messages)
    val tokensPerSecond: Float? = null,   // speed, e.g. 35.4 (only AI messages)
    val tokenCount: Int? = null           // tokens produced, e.g. 311
)

// ONE chat in the side drawer's history list.
data class ChatSummary(
    val id: Long,                         // unique number of the chat
    val title: String,                    // shown in the list
    val group: String                     // section name, e.g. "Today"
)
