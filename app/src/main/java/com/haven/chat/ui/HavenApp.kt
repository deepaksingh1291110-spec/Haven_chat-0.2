// =====================================================================
// HavenApp.kt  (app/src/main/java/com/haven/chat/ui/)
// WHAT: The ROOT of the whole app UI. It joins everything:
//         - two pages with navigation: "chat" and "settings"
//         - the side drawer, which slides over the chat page
//         - the two ViewModels (chat data, settings data)
// WHO CALLS IT: MainActivity calls HavenApp() once.
// STORAGE PERMISSION: Only an Activity can check or ask for "All files
//   access". So MainActivity does that and hands us two things:
//   storageAllowed (true/false) and onRequestStorageAccess (a function).
//
// PHASE 2 CHANGES:
//   - Tapping the status chip in the chat loads the model when it is
//     "Not loaded" or "Error" (with the saved settings).
//   - The "Load Model" button in Settings now hands over the settings
//     that are on screen: they are saved and the model is loaded. We
//     stay on the Settings page so the button text can be watched.
//   - The Browse file picker now lives inside SettingsScreen.
//   - Stop button: ChatScreen gets isGenerating (read from the engine
//     state) and onStop (calls chatViewModel.stopGenerating()).
//
// PHASE 3 CHANGES (marked "PHASE 3"): REAL CHATS.
//   - DummyData is gone. The drawer shows the chats from the database
//     (chatViewModel.chats, a live list).
//   - Search box: the text is kept here and handed to the ViewModel,
//     which asks the database (titles and message texts).
//   - Tapping a chat opens it. "New chat" starts a fresh one. Both are
//     ignored while Gemma is busy, and then the drawer stays open so the
//     "Gemma is busy" hint can be read.
//   - The "..." menu: Rename and Delete go to the ViewModel.
//   - The Wait / Cancel popup (Auto-name chats) is connected to the chat
//     screen.
//   - The SettingsViewModel is created BEFORE the ChatViewModel, so the
//     saved settings are loaded before the chat uses them.
// =====================================================================
package com.haven.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.haven.chat.engine.EngineManager
import com.haven.chat.ui.chat.ChatScreen
import com.haven.chat.ui.chat.ChatViewModel
import com.haven.chat.ui.drawer.DrawerContent
import com.haven.chat.ui.settings.SettingsScreen
import com.haven.chat.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

// Names of our two pages. Using constants avoids typos.
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun HavenApp(
    storageAllowed: Boolean,                 // is "All files access" granted?
    onRequestStorageAccess: () -> Unit       // open the phone's permission page
) {
    // The navigator: remembers which page is open and the back stack.
    val navController = rememberNavController()

    // viewModel() gives us the SAME ViewModel every time, even after
    // rotation. Created here at the top, so both pages share them.
    // PHASE 3: settings first (they are read from the phone right here),
    // then the chat.
    val settingsViewModel: SettingsViewModel = viewModel()
    val chatViewModel: ChatViewModel = viewModel()

    // PHASE 3: the chat list for the drawer. It is a LIVE stream from the
    // database; "collectAsState" turns it into a value that redraws the
    // drawer by itself whenever a chat is added, renamed or deleted.
    val chats by chatViewModel.chats.collectAsState()

    // PHASE 3: the text in the drawer's search box. rememberSaveable keeps
    // it when the phone rotates or when we come back from Settings.
    var searchText by rememberSaveable { mutableStateOf("") }

    // NavHost = the area that shows the current page.
    NavHost(navController = navController, startDestination = ROUTE_CHAT) {

        // ================= PAGE 1: CHAT (with the drawer) =================
        composable(ROUTE_CHAT) {
            // Drawer state: open or closed.
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            // A "scope" lets us start open/close animations from a tap.
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    // ModalDrawerSheet = the sliding panel container.
                    // windowInsets = 0: DrawerContent already pads itself
                    // below the status bar, so we avoid double padding.
                    ModalDrawerSheet(
                        drawerContainerColor = MaterialTheme.colorScheme.background,
                        windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
                    ) {
                        DrawerContent(
                            chats = chats,                                   // PHASE 3: real chats
                            currentChatId = chatViewModel.currentChatId,     // PHASE 3: highlight
                            query = searchText,
                            onQueryChange = { text ->
                                searchText = text
                                chatViewModel.setSearchQuery(text)           // the database searches
                            },
                            engineBusy = EngineManager.isBusy,
                            threads = settingsViewModel.settings.threads,
                            onNewChatClick = {
                                // While Gemma is busy the ViewModel ignores
                                // this, so we keep the drawer open (it shows
                                // the "busy" hint).
                                if (!EngineManager.isBusy) {
                                    chatViewModel.startNewChat()
                                    scope.launch { drawerState.close() }
                                }
                            },
                            onChatClick = { chatId ->
                                if (!EngineManager.isBusy) {
                                    chatViewModel.openChat(chatId)           // PHASE 3
                                    scope.launch { drawerState.close() }
                                }
                            },
                            onRenameChat = { chatId, title ->
                                chatViewModel.renameChat(chatId, title)      // PHASE 3
                            },
                            onDeleteChat = { chatId ->
                                chatViewModel.deleteChat(chatId)             // PHASE 3
                            },
                            onSettingsClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(ROUTE_SETTINGS)
                            },
                            onCloseClick = {
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            ) {
                // This block is the page BEHIND the drawer: the chat screen.
                ChatScreen(
                    messages = chatViewModel.messages,
                    modelStatus = chatViewModel.modelStatus,
                    onSend = { text -> chatViewModel.sendMessage(text) },
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onNewChatClick = { chatViewModel.startNewChat() },
                    // The tune icon in the input bar opens Settings for now.
                    onOptionsClick = { navController.navigate(ROUTE_SETTINGS) },
                    // Tap on the status chip: load the model, but ONLY when
                    // it is not loaded (or the last load failed). In the
                    // states Loading, Ready, Thinking and Naming a tap does nothing.
                    onStatusClick = {
                        val s = EngineManager.state
                        if (s == EngineManager.State.NOT_LOADED || s == EngineManager.State.ERROR) {
                            settingsViewModel.loadModel()
                        }
                    },
                    // Stop button: while Gemma is busy (reading, thinking or
                    // writing) the send button turns into "stop".
                    isGenerating = EngineManager.state == EngineManager.State.THINKING,
                    onStop = { chatViewModel.stopGenerating() },
                    // PHASE 3: the Wait / Cancel popup (Auto-name chats).
                    namingDialogVisible = chatViewModel.namingDialogVisible,
                    onWaitNaming = { chatViewModel.waitForNaming() },
                    onCancelNaming = { chatViewModel.cancelNaming() }
                )
            }
        }

        // ================= PAGE 2: SETTINGS =================
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                initial = settingsViewModel.settings,
                storageAllowed = storageAllowed,
                onBack = { navController.popBackStack() },
                onSaveAndReload = { newSettings ->
                    settingsViewModel.saveSettings(newSettings)
                    navController.popBackStack()   // go back to the chat after saving
                },
                // "Load Model": save what is on screen, then load it.
                // (saveSettings stores the settings AND loads the model.)
                // We stay on this page so the button can show "Loading...".
                onLoadModel = { draft -> settingsViewModel.saveSettings(draft) },
                onStorageClick = onRequestStorageAccess
            )
        }
    }
}
