// =====================================================================
// MessageBubble.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: Draws ONE chat message.
//       - USER message  -> blue bubble on the RIGHT
//       - AI message    -> dark bubble on the LEFT, with an optional
//                          collapsible "Thinking process" box and a
//                          small stats line (e.g. "35.4 t/s • 311 tokens")
// USED BY: ChatScreen calls MessageBubble() once per message.
//
// PHASE 2 CHANGES:
//   - The AI bubble now leaves free space at the RIGHT side, like in
//     your design (before, it ran to the screen edge).
//   - The AI answer AND the thinking text are drawn as real Markdown
//     (bold, lists, code, ...) by MarkdownText (MarkdownText.kt). The
//     text is redrawn while it streams in, so the formatting appears
//     live.
//   - While Gemma has not written anything yet, the bubble shows "...".
//   - The empty Text that was used as a spacer is now a real Spacer.
//
// COPY FIX (after Phase 3, marked "COPY"):
//   Before, long-press did nothing, because Compose text is NOT
//   selectable by default. Now:
//   1) The answer text, the thinking text and your own messages are
//      wrapped in a SelectionContainer. Long-press a word, drag the two
//      handles to choose more, and Android's normal "Copy" button
//      appears. Selection works across paragraphs, lists and code inside
//      ONE bubble (not across two bubbles).
//   2) A small copy icon sits at the end of the stats line of every
//      finished answer. One tap copies the WHOLE answer, and the icon
//      turns into a check mark for 1.5 seconds. What is copied is the
//      answer as Gemma wrote it, in Markdown (so ** and ``` stay in it,
//      which is what you want when you paste code).
//   3) Code blocks get their own "Copy" button in MarkdownText.kt.
//
// CRASH FIX (marked "CRASH FIX"):
//   Selecting text in the answer that Gemma is STILL WRITING crashed the
//   app: the text changes many times a second and the selection points
//   into text that no longer matches. Now MessageBubble() has a flag
//   "selectable". ChatScreen sets it to false only for the LAST message
//   while Gemma is busy. That bubble (answer and thinking text) is drawn
//   without a SelectionContainer, so nothing can be selected and nothing
//   can crash. All older messages stay selectable during streaming, and
//   the new answer becomes selectable as soon as it is finished.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatMessage
import com.haven.chat.data.Role
import com.haven.chat.ui.theme.OnUserBubble
import com.haven.chat.ui.theme.StatsTextStyle
import com.haven.chat.ui.theme.UserBubbleBlue
import kotlinx.coroutines.delay

// PUBLIC entry point. The chat screen calls only this function.
// It looks at who wrote the message and picks the right bubble.
//
// CRASH FIX: "selectable" is false ONLY for the answer that Gemma is still
// writing (ChatScreen decides). While text streams in, a selection would
// point into text that keeps changing, and Compose can crash. When the
// answer is finished, ChatScreen passes true and the text can be selected.
@Composable
fun MessageBubble(message: ChatMessage, selectable: Boolean = true) {
    if (message.role == Role.USER) {
        UserBubble(message)
    } else {
        AssistantBubble(message, selectable)
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
        // COPY: SelectionContainer makes the text selectable (long-press).
        SelectionContainer {
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
}

// ---------------------------------------------------------------------
// AI bubble: dark card on the left. Contains up to 3 parts:
//   1) Thinking process box (only if the AI has thinking text)
//   2) The answer text (drawn as Markdown, selectable)
//   3) Stats line (only if speed and token count are known), with the
//      "copy whole answer" icon at its end
// ---------------------------------------------------------------------
@Composable
private fun AssistantBubble(message: ChatMessage, selectable: Boolean) {
    Row(
        // start = 16 dp on the left, end = 48 dp on the RIGHT: this is
        // the free space your design has next to the AI bubble.
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 48.dp, top = 6.dp, bottom = 6.dp),
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
                ThinkingBox(thinkingText, selectable)
                // Small gap under the box.
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Part 2: the answer.
            if (message.text.isEmpty() && message.thinking == null) {
                // Nothing has arrived yet: show a dim "..." so the bubble is not empty.
                Text(
                    text = "...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // MarkdownText turns **bold**, lists, `code` ... into real formatting.
                val answerContent: @Composable () -> Unit = {
                    MarkdownText(
                        text = message.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                // COPY: SelectionContainer makes everything inside selectable:
                // paragraphs, list items, code blocks.
                // CRASH FIX: but NOT while this answer is still being written
                // (selectable = false): then it is drawn without a container.
                if (selectable) {
                    SelectionContainer { answerContent() }
                } else {
                    answerContent()
                }
            }

            // Part 3: stats line, only when both numbers exist.
            val speed = message.tokensPerSecond
            val count = message.tokenCount
            if (speed != null && count != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        // "%.1f".format(35.4f) gives "35.4" (one decimal place).
                        text = "${"%.1f".format(speed)} t/s • $count tokens",
                        style = StatsTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // COPY: one tap copies the whole answer.
                    CopyAnswerButton(text = message.text)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// COPY: the small "copy whole answer" button.
// Tap = the text goes to the phone's clipboard, and the icon shows a
// check mark for 1.5 seconds so you know it worked.
// ---------------------------------------------------------------------
@Composable
private fun CopyAnswerButton(text: String) {
    // The phone's clipboard.
    val clipboard = LocalClipboardManager.current

    // true for 1.5 seconds after a tap.
    var copied by remember { mutableStateOf(false) }

    // When "copied" becomes true: wait 1.5 s, then turn it back to false.
    // (Tapping again while it waits restarts the wait.)
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) "Copied" else "Copy answer",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

// ---------------------------------------------------------------------
// The collapsible "Thinking process" box. Tap the header to open/close.
// ---------------------------------------------------------------------
@Composable
private fun ThinkingBox(thinkingText: String, selectable: Boolean) {
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
        // It is drawn as Markdown too, so its lists and bold text look right.
        // COPY: it is selectable as well (only while it is open), and
        // CRASH FIX: not while it is still being written (selectable = false).
        AnimatedVisibility(visible = expanded) {
            val thinkingContent: @Composable () -> Unit = {
                MarkdownText(
                    text = thinkingText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                )
            }
            if (selectable) {
                SelectionContainer { thinkingContent() }
            } else {
                thinkingContent()
            }
        }
    }
}
