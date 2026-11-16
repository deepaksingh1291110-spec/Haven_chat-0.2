// =====================================================================
// DrawerContent.kt  (app/src/main/java/com/haven/chat/ui/drawer/)
// WHAT: The content of the side menu (drawer) from your design:
//         [New chat] [Search chats........] [X]
//         Today
//           . Local NPU acceleration check        ...
//         Hardware Status card (CPU, Instruction Set, Threading)
//         Preferences: Settings, Model management, Privacy, Storage, About
// HOW:  It draws only the INSIDE of the menu. The sliding animation and
//       the dark overlay come from ModalNavigationDrawer (a later file).
// STATELESS: it gets data in, and reports taps out (callbacks).
// STATUS: Only "New chat", chat rows, close and Settings do something.
//         The other preference rows are visual only for now.
// =====================================================================
package com.haven.chat.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.haven.chat.data.ChatSummary
import com.haven.chat.ui.theme.DarkDrawerBackground

@Composable
fun DrawerContent(
    chats: List<ChatSummary>,            // chat history to list
    threads: Int,                        // CPU threads shown in Hardware Status
    onNewChatClick: () -> Unit,
    onChatClick: (Long) -> Unit,         // gives the id of the tapped chat
    onSettingsClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    // Text typed in the search box. Starts empty.
    var query by remember { mutableStateOf("") }

    // Filter: keep chats whose title contains the query (ignore capitals).
    val filtered = chats.filter { it.title.contains(query, ignoreCase = true) }
    // Group by section name ("Today", "Yesterday"...). Result: Map<name, list>.
    val grouped = filtered.groupBy { it.group }

    // Your design uses pure black in dark mode; light mode uses the theme color.
    val drawerColor =
        if (isSystemInDarkTheme()) DarkDrawerBackground
        else MaterialTheme.colorScheme.background

    // LazyColumn = a scrolling list. The WHOLE drawer scrolls, so it
    // still fits on small phones.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(drawerColor)
            .statusBarsPadding()                       // stay below the clock/battery bar
            .padding(horizontal = 16.dp)
    ) {
        // ---------- TOP ROW: New chat, Search, Close ----------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onNewChatClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF2F2F2),
                        contentColor = Color(0xFF1C1B1F)
                    )
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = " New chat", style = MaterialTheme.typography.labelLarge)
                }

                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)                    // take the free width
                        .padding(horizontal = 8.dp),
                    placeholder = { Text("Search chats") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = Color.Transparent,   // hide underline
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Filled.Close, contentDescription = "Close menu")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        // ---------- CHAT HISTORY, grouped by section ----------
        grouped.forEach { (groupName, groupChats) ->
            // Section title ("Today").
            item {
                Text(
                    text = groupName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                )
            }
            // One row per chat in the section.
            items(groupChats, key = { it.id }) { chat ->
                ChatRow(chat = chat, onClick = { onChatClick(chat.id) })
            }
        }

        // ---------- HARDWARE STATUS CARD ----------
        item {
            Spacer(Modifier.height(24.dp))
            HardwareCard(threads = threads)
        }

        // ---------- PREFERENCES ----------
        item {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
            PreferenceRow(Icons.Outlined.Settings, "Settings", onSettingsClick)
            PreferenceRow(Icons.Outlined.Memory, "Model management") { /* TODO later */ }
            PreferenceRow(Icons.Outlined.Shield, "Privacy & local data") { /* TODO later */ }
            PreferenceRow(Icons.Outlined.Storage, "Storage & cache") { /* TODO later */ }
            PreferenceRow(Icons.Outlined.Info, "About Haven") { /* TODO later */ }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ONE chat row: small colored dot, title, "..." button.
@Composable
private fun ChatRow(chat: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The dot: a small circle.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = chat.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp)
        )
        // "..." menu (rename/delete will come later).
        Icon(
            imageVector = Icons.Filled.MoreHoriz,
            contentDescription = "Chat options",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// The rounded card with 3 lines of hardware info.
@Composable
private fun HardwareCard(threads: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Hardware Status", style = MaterialTheme.typography.titleMedium)
        // NEON and INT8 are fixed text for now (your phone's CPU features).
        HardwareLine("CPU", "NEON")
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HardwareLine("Instruction Set", "INT8")
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        HardwareLine("Threading", "$threads Threads")
    }
}

// One "label ........ value" line inside the hardware card.
@Composable
private fun HardwareLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween   // label left, value right
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// One preference row: icon in a rounded square, then the title.
@Composable
private fun PreferenceRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        )
    }
}
