// =====================================================================
// ChatScreen.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The full chat screen from your design:
//         top bar:  [menu]   Haven Chat   [new chat]
//         chip:     gemma4-e2b • Ready   (tap it to load the model)
//         list:     message bubbles (scrolls)
//         bottom:   message input bar
// DESIGN RULE: this screen is "stateless". It does NOT own any data.
//   It only DRAWS the list it is given and REPORTS taps upward
//   (onSend, onMenuClick, ...). A ViewModel owns the data.
//
// PHASE 2 CHANGES:
//   - The status chip can be tapped (onStatusClick). The caller decides
//     what a tap does (HavenApp loads the model when it is not loaded).
//   - The list now also scrolls DOWN while an answer is being streamed,
//     not only when a new message is added.
//   - Stop button: isGenerating and onStop are passed on to the input bar,
//     where the send button turns into a stop button while Gemma works.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatMessage
import com.haven.chat.ui.theme.StatusGreen

// CenterAlignedTopAppBar is still marked "experimental" by Google, but it
// is stable enough to use. @OptIn tells the compiler "I accept that".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,        // what to show
    modelStatus: String,                // text in the chip, e.g. "gemma4-e2b • Ready"
    onSend: (String) -> Unit,           // user sent a message
    onMenuClick: () -> Unit,            // user tapped the menu icon (open drawer)
    onNewChatClick: () -> Unit,         // user tapped the pencil icon
    onOptionsClick: () -> Unit,         // user tapped the tune icon in the input bar
    onStatusClick: () -> Unit = {},     // user tapped the status chip (default: do nothing)
    isGenerating: Boolean = false,      // true while Gemma is busy (the send button becomes "stop")
    onStop: () -> Unit = {}             // user tapped the stop button
) {
    // Remembers the scroll position of the message list.
    val listState = rememberLazyListState()

    // LaunchedEffect runs its block again whenever ANY of its keys change:
    //   - the number of messages (a message was added), or
    //   - the length of the last message's text or thinking (it is
    //     growing because Gemma is streaming the answer).
    // Then we scroll to the very end so the newest text stays visible.
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.thinking?.length) {
        if (messages.isNotEmpty()) {
            // First jump to the last message ...
            listState.scrollToItem(messages.lastIndex)
            // ... then push to the very bottom of it (a long answer is
            // taller than the screen). Scrolling "too far" is harmless:
            // the list simply stops at its end.
            listState.scrollBy(100000f)
        }
    }

    // Scaffold = standard screen layout: top bar, content, bottom bar.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,

        // ---------- TOP BAR ----------
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Haven Chat",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                    }
                },
                actions = {
                    IconButton(onClick = onNewChatClick) {
                        Icon(Icons.Outlined.Edit, contentDescription = "New chat")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },

        // ---------- BOTTOM BAR (input) ----------
        bottomBar = {
            ChatInputBar(
                onSend = onSend,
                onOptionsClick = onOptionsClick,
                // The round button shows "stop" while Gemma is busy.
                isGenerating = isGenerating,
                onStop = onStop,
                // navigationBarsPadding: stay above the phone's gesture bar.
                // imePadding: move up when the keyboard opens.
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
            )
        }
    ) { innerPadding ->
        // innerPadding = space taken by the top bar and bottom bar.
        // We must apply it, or content hides behind them.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ---------- STATUS CHIP ----------
            // Tapping it calls onStatusClick (HavenApp loads the model
            // when it is "Not loaded" or "Error").
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = modelStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusGreen,
                    modifier = Modifier
                        // clip first, so the tap ripple has rounded corners
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onStatusClick)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // ---------- MESSAGE LIST ----------
            // LazyColumn only draws the messages visible on screen,
            // so long chats stay fast and use little RAM.
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),     // take all remaining height
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // "key" gives each message a stable identity (its id),
                // so Compose does not redraw everything when one is added.
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }
    }
}
