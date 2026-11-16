// =====================================================================
// ChatScreen.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The full chat screen from your design:
//         top bar:  [menu]   Haven Chat   [new chat]
//         chip:     gemma4-e2b • Ready
//         list:     message bubbles (scrolls)
//         bottom:   message input bar
// DESIGN RULE: this screen is "stateless". It does NOT own any data.
//   It only DRAWS the list it is given and REPORTS taps upward
//   (onSend, onMenuClick, ...). A ViewModel (next file) owns the data.
//   This makes it easy to plug the real AI engine in during Phase 2.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.foundation.border
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
    onOptionsClick: () -> Unit          // user tapped the tune icon in the input bar
) {
    // Remembers the scroll position of the message list.
    val listState = rememberLazyListState()

    // LaunchedEffect runs its block again whenever the key changes.
    // Key = number of messages. So when a message is added, scroll down to it.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
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
