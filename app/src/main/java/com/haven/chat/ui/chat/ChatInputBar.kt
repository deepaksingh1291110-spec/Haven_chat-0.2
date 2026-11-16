// =====================================================================
// ChatInputBar.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The rounded message box at the bottom of the chat screen:
//         [ tune icon ] [ Message Haven... ] [ round send button ]
// HOW:  It keeps the typed text inside itself. When you tap send, it
//       gives the text to the caller (onSend) and clears the box.
// USED BY: ChatScreen (a later file).
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.haven.chat.ui.theme.SendButtonBlue

@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,          // called with the text when user taps send
    onOptionsClick: () -> Unit,        // called when user taps the tune icon
    modifier: Modifier = Modifier
) {
    // The text the user is typing. "remember" keeps it alive while the
    // screen is shown; "mutableStateOf" redraws the bar when it changes.
    var text by remember { mutableStateOf("") }

    // Send is allowed only when there is real text (not just spaces).
    val canSend = text.isNotBlank()

    // Order of modifiers matters: outer space, round the corners,
    // paint the background, then inner space.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: tune icon (opens quick options later).
        IconButton(onClick = onOptionsClick) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = "Options",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        // MIDDLE: the text field. weight(1f) = take all free width.
        TextField(
            value = text,
            onValueChange = { text = it },          // store every typed letter
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "Message Haven...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            maxLines = 5,                            // grows up to 5 lines, then scrolls
            // Make the field invisible so only our rounded bar shows:
            // transparent background and no underline.
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent
            )
        )

        // RIGHT: round send button. Grayed out when there is no text.
        FilledIconButton(
            onClick = {
                onSend(text.trim())                  // give text to the caller
                text = ""                            // clear the box
            },
            enabled = canSend,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = SendButtonBlue,
                contentColor = Color(0xFF062E6F),
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = "Send"
            )
        }
    }
}
