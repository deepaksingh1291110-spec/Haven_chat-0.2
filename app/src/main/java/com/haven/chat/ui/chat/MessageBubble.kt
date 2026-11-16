// =====================================================================
// MessageBubble.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: Draws ONE chat message.
//       - USER message  -> blue bubble on the RIGHT
//       - AI message    -> dark bubble on the LEFT, with an optional
//                          collapsible "Thinking process" box and a
//                          small stats line (e.g. "35.4 t/s • 311 tokens")
// USED BY: ChatScreen (a later file) calls MessageBubble() once per message.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatMessage
import com.haven.chat.data.Role
import com.haven.chat.ui.theme.OnUserBubble
import com.haven.chat.ui.theme.StatsTextStyle
import com.haven.chat.ui.theme.UserBubbleBlue

// PUBLIC entry point. The chat screen calls only this function.
// It looks at who wrote the message and picks the right bubble.
@Composable
fun MessageBubble(message: ChatMessage) {
    if (message.role == Role.USER) {
        UserBubble(message)
    } else {
        AssistantBubble(message)
    }
}

// ---------------------------------------------------------------------
// USER bubble: blue, pushed to the right edge.
// ---------------------------------------------------------------------
@Composable
private fun UserBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End      // push content to the right
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyLarge,
            color = OnUserBubble,
            modifier = Modifier
                .widthIn(max = 300.dp)                // bubble never gets too wide
                .clip(RoundedCornerShape(24.dp))      // round the corners...
                .background(UserBubbleBlue)           // ...then paint blue
                .padding(horizontal = 20.dp, vertical = 14.dp) // space inside
        )
    }
}

// ---------------------------------------------------------------------
// AI bubble: dark card on the left. Contains up to 3 parts:
//   1) Thinking process box (only if the AI has thinking text)
//   2) The answer text
//   3) Stats line (only if speed and token count are known)
// ---------------------------------------------------------------------
@Composable
private fun AssistantBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Start     // keep content on the left
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            // Part 1: thinking box. "?.let" means: run only if thinking is not null.
            message.thinking?.let { thinkingText ->
                ThinkingBox(thinkingText)
                // Small gap under the box (a Column child with padding).
                Text(text = "", modifier = Modifier.padding(top = 4.dp))
            }

            // Part 2: the answer.
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Part 3: stats line, only when both numbers exist.
            val speed = message.tokensPerSecond
            val count = message.tokenCount
            if (speed != null && count != null) {
                Text(
                    // "%.1f".format(35.4f) gives "35.4" (one decimal place).
                    text = "${"%.1f".format(speed)} t/s • $count tokens",
                    style = StatsTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// The collapsible "Thinking process" box. Tap the header to open/close.
// ---------------------------------------------------------------------
@Composable
private fun ThinkingBox(thinkingText: String) {
    // "remember" keeps the value while the screen is on.
    // "mutableStateOf" makes Compose REDRAW when the value changes.
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Header row: title on the left, arrow on the right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }   // tap = flip open/closed
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Thinking process",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                              else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        // The thinking text slides in only when expanded is true.
        AnimatedVisibility(visible = expanded) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}
