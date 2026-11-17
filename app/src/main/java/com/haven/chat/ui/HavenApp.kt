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
//   - The Browse file picker now lives inside SettingsScreen, so the
//     onBrowseClick line is gone.
// =====================================================================
package com.haven.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.haven.chat.data.DummyData
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
    val chatViewModel: ChatViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()

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
                            chats = DummyData.chats,   // TODO Phase 3: real chats
                            threads = settingsViewModel.settings.threads,
                            onNewChatClick = {
                                chatViewModel.startNewChat()
                                scope.launch { drawerState.close() }
                            },
                            onChatClick = { _ ->
                                // TODO Phase 3: load the tapped chat's messages.
                                scope.launch { drawerState.close() }
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
                    // states Loading, Ready and Thinking a tap does nothing.
                    onStatusClick = {
                        val s = EngineManager.state
                        if (s == EngineManager.State.NOT_LOADED || s == EngineManager.State.ERROR) {
                            settingsViewModel.loadModel()
                        }
                    }
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
