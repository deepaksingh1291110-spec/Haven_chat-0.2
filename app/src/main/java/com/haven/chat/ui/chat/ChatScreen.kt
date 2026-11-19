// =====================================================================
// ChatScreen.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The full chat screen from your design:
//         top bar:  [menu]   Haven Chat   [remember] [new chat]
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
//
// PHASE 3 CHANGES (marked "PHASE 3"):
//   1) GEMINI-STYLE FADE (your UI fix). Before, the top bar and the chip
//      were solid and the list started BELOW them, so messages were cut
//      off with a hard edge. Now:
//        - the top bar is transparent,
//        - the message list fills the whole screen and scrolls BEHIND the
//          top bar and the chip,
//        - a soft gradient (background color -> transparent) lies over
//          that area, so text fades out gently as it nears the top.
//      When you are at the top of a chat, the first message still starts
//      below the chip (the list has extra space on top for that).
//      The strength of the fade is set by the constants just below.
//   2) WAIT / CANCEL POPUP. While Gemma writes a chat title (Auto-name
//      chats), a message sent by the user is held back and this popup is
//      shown:  [Cancel]  [Wait].  The ViewModel decides what they do.
//      Closing the popup by tapping outside counts as "Wait".
//
// CRASH FIX (after Phase 3, with the copy feature):
//   Selecting text in the answer that is still being written crashed the
//   app. Now the LAST bubble is told "selectable = false" while Gemma is
//   busy (isGenerating). See MessageBubble.kt.
//
// PHASE 4 CHANGES (marked "PHASE 4"):
//   1) REMEMBER BUTTON. A small bookmark icon in the top bar (between the
//      title and the pencil). Tapping it calls onRememberClick, which
//      starts "remember my personal things from this chat" (the same job
//      as typing the phrase).
//   2) MEMORY DIALOG. While the job runs, a dialog shows the progress
//      ("Checked 15 of 40 of your messages. 3 saved so far.") with a
//      progress bar and a Cancel button. It cannot be closed by tapping
//      outside. When the job is over, the same dialog shows the short
//      result text and an OK button. The ViewModel (ChatViewModel,
//      memoryJob) decides what is shown; this screen only draws it.
// =====================================================================
package com.haven.chat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatMessage
import com.haven.chat.ui.theme.StatusGreen

// ---------------------------------------------------------------------
// PHASE 3: numbers for the fade. Change them to taste.
// ---------------------------------------------------------------------

// Height of the status-chip area: chip (36 dp) + 8 dp above and below.
private val ChipAreaHeight = 52.dp

// How far the fade reaches BELOW the chip area. Bigger = softer, longer fade.
private val FadeExtraHeight = 32.dp

// How strong the cover is at the very top (1.0 = fully solid, 0.0 = none).
// A little below 1.0 lets faint text show through under the top bar, like Gemini.
private const val FadeAlphaTop = 0.96f

// How strong it is at the middle of the gradient.
private const val FadeAlphaMiddle = 0.85f

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
    onStop: () -> Unit = {},            // user tapped the stop button
    namingDialogVisible: Boolean = false, // PHASE 3: show the Wait / Cancel popup
    onWaitNaming: () -> Unit = {},        // PHASE 3: user chose "Wait" (or closed the popup)
    onCancelNaming: () -> Unit = {},      // PHASE 3: user chose "Cancel"
    onRememberClick: () -> Unit = {},     // PHASE 4: user tapped the bookmark icon
    memoryJob: MemoryJobUi? = null,       // PHASE 4: what the memory dialog shows (null = no dialog)
    onCancelMemory: () -> Unit = {},      // PHASE 4: user tapped Cancel while the job runs
    onDismissMemory: () -> Unit = {}      // PHASE 4: user tapped OK after the job
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

    // The color the fade blends into: the screen background.
    val background = MaterialTheme.colorScheme.background

    // Scaffold = standard screen layout: top bar, content, bottom bar.
    Scaffold(
        containerColor = background,

        // ---------- TOP BAR ----------
        // PHASE 3: the container is TRANSPARENT now, so the messages (and
        // the fade) can be seen behind the title and the icons.
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
                    // PHASE 4: "remember my personal things from this chat".
                    IconButton(onClick = onRememberClick) {
                        Icon(Icons.Outlined.Bookmark, contentDescription = "Remember personal things")
                    }
                    IconButton(onClick = onNewChatClick) {
                        Icon(Icons.Outlined.Edit, contentDescription = "New chat")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
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
        // PHASE 3: we use only the BOTTOM part as normal padding (so the
        // list ends above the input bar). The TOP part is used below, as
        // space INSIDE the list, so messages can slide up behind the bar.
        val topBarSpace = innerPadding.calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // ---------- MESSAGE LIST ----------
            // LazyColumn only draws the messages visible on screen,
            // so long chats stay fast and use little RAM.
            // PHASE 3: it fills the WHOLE box (also behind the top bar). The
            // top contentPadding keeps the first message clear of the bar
            // and the chip when the chat is scrolled to the top.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = topBarSpace + ChipAreaHeight + 8.dp,
                    bottom = 8.dp
                )
            ) {
                // "key" gives each message a stable identity (its id),
                // so Compose does not redraw everything when one is added.
                items(messages, key = { it.id }) { message ->
                    // CRASH FIX: the LAST bubble is not selectable while Gemma
                    // is busy writing it (its text changes many times a second,
                    // and a selection on it crashed the app). Older messages
                    // stay selectable, and so does the new answer once it is done.
                    val isStreamingBubble = isGenerating && message.id == last?.id
                    MessageBubble(message, selectable = !isStreamingBubble)
                }
            }

            // ---------- PHASE 3: THE FADE ----------
            // A gradient that starts almost solid at the very top and ends
            // fully transparent. It lies over the list, under the title and
            // the chip. It does not catch touches: the list can still be
            // scrolled through it.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(topBarSpace + ChipAreaHeight + FadeExtraHeight)
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to background.copy(alpha = FadeAlphaTop),
                                0.55f to background.copy(alpha = FadeAlphaMiddle),
                                1f to Color.Transparent
                            )
                        )
                    )
            )

            // ---------- STATUS CHIP ----------
            // Tapping it calls onStatusClick (HavenApp loads the model
            // when it is "Not loaded" or "Error").
            // PHASE 3: it now sits on top of the list (drawn after it),
            // just below the top bar.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = topBarSpace)
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
        }
    }

    // ---------- PHASE 3: WAIT / CANCEL POPUP ----------
    // Shown while Gemma is writing a chat title and the user has sent a
    // message in the meantime. The message is held back by the ViewModel.
    //   Wait   -> the message is sent when the title is ready (a few seconds).
    //   Cancel -> the naming stops, the chat keeps its quick title, and the
    //             message is sent right away.
    // Tapping outside the popup (or Back) counts as "Wait".
    if (namingDialogVisible) {
        AlertDialog(
            onDismissRequest = onWaitNaming,
            title = { Text("Gemma is naming this chat") },
            text = {
                Text(
                    "Your message is ready to send. Wait a few seconds for the " +
                            "title, or cancel the naming and send it now."
                )
            },
            confirmButton = {
                TextButton(onClick = onWaitNaming) { Text("Wait") }
            },
            dismissButton = {
                TextButton(onClick = onCancelNaming) { Text("Cancel") }
            }
        )
    }

    // ---------- PHASE 4: MEMORY DIALOG ----------
    // Shown for the whole memory job ("remember my personal things from
    // this chat"). memoryJob == null means: no dialog.
    //   RUNNING  : progress text + progress bar + [Cancel]. Tapping outside
    //              or pressing Back does NOT close it (the job is running;
    //              only Cancel stops it).
    //   FINISHED : the result text + [OK]. Tapping outside counts as OK.
    // The numbers and texts come from ChatViewModel (memoryJob).
    if (memoryJob != null) {
        val job = memoryJob
        if (job.running) {
            AlertDialog(
                onDismissRequest = { /* not closable while the job runs */ },
                title = { Text("Saving personal memories") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (job.total > 0) {
                            // We know how many messages there are: show the real progress.
                            Text(
                                "Checked ${job.checked} of ${job.total} of your messages.\n" +
                                        "${job.saved} saved so far."
                            )
                            LinearProgressIndicator(
                                progress = { job.checked.toFloat() / job.total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // The first batch is still being read: no numbers yet.
                            Text(
                                "Gemma is reading your messages. " +
                                        "The first step can take a minute."
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = { /* nothing: only Cancel is offered while it runs */ },
                dismissButton = {
                    TextButton(onClick = onCancelMemory) { Text("Cancel") }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = onDismissMemory,
                title = { Text("Memories") },
                text = { Text(job.message ?: "") },
                confirmButton = {
                    TextButton(onClick = onDismissMemory) { Text("OK") }
                }
            )
        }
    }
}
