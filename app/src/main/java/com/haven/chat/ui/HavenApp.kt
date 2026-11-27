// =====================================================================
// HavenApp.kt  (app/src/main/java/com/haven/chat/ui/)
// WHAT: The ROOT of the whole app UI. It joins everything:
//         - four pages with navigation: "chat", "settings", "privacy" and
//           "models"
//         - the side drawer, which slides over the chat page
//         - the ViewModels (chat data, settings data, memories data, models)
// WHO CALLS IT: MainActivity calls HavenApp() once.
//
// PHASE 2 CHANGES:
//   - Tapping the status chip in the chat loads the model when it is
//     "Not loaded" or "Error" (with the saved settings).
//   - The "Load Model" button in Settings now hands over the settings
//     that are on screen: they are saved and the model is loaded. We
//     stay on the Settings page so the button text can be watched.
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
//
// PHASE 4 CHANGES (marked "PHASE 4"): REMEMBER PERSONAL THINGS.
//   Step 3:
//   - The chat screen gets four new connections: the bookmark button
//     (onRememberClick), the memory dialog state (memoryJob) and its two
//     buttons (onCancelMemory, onDismissMemory). They all go straight to
//     ChatViewModel.
//   - While the memory job runs, EngineManager.isBusy is true (state
//     MEMORIZING), so the drawer ignores "New chat" and chat taps and
//     shows its "Gemma is busy" hint, as it does for naming.
//   Step 4 (marked "PHASE 4 / DELETE"):
//   - The drawer's delete dialog has a new choice, "Save personal things,
//     then delete". It calls chatViewModel.deleteChatSavingMemories(id)
//     and CLOSES the drawer, so the memory dialog (progress, Cancel, then
//     the result) is visible on the chat screen. "Delete without saving"
//     still calls chatViewModel.deleteChat(id), as before.
//   Step 5 (marked "PHASE 4 / PRIVACY"):
//   - A third page "privacy" (PrivacyScreen). The drawer row "Privacy &
//     local data" closes the drawer and opens it. The page has its own
//     ViewModel (PrivacyViewModel), created only when the page is opened:
//     it shows the saved memories (live list) and edits or deletes them.
//     The back arrow returns to the chat.
//   Step 7 (marked "STEP 7"):
//   - The privacy page now also edits the TAGS of a memory:
//     onUpdateMemory gives (id, question, answer, tags) and goes straight
//     to PrivacyViewModel.updateMemory.
//
// PRIVATE MODELS (marked "MODELS"): NO MORE "ALL FILES ACCESS".
//   The app no longer reads models from a normal phone folder. They live in
//   the app's private space (ModelStore) and are managed on a new page:
//   - A fourth page "models" (ModelsScreen). The drawer row "Model
//     management" closes the drawer and opens it. The page has its own
//     ViewModel (ModelsViewModel), created when the page is opened. It is
//     told about the settings with bind(settingsViewModel), because the
//     roles (main model, embedder, reranker) are saved in the settings.
//   - The two storage-permission parameters of HavenApp are gone
//     (storageAllowed, onRequestStorageAccess): MainActivity does not ask
//     for the permission any more.
//   - FIRST START: when the chosen main model file does not exist (a new
//     install, or the old path from the older version), the models page is
//     opened ONCE automatically, so the user can add a model right away.
//     (After that the user goes there from the drawer.)
//   - The status chip: when the model is not loaded and its file does not
//     exist, tapping the chip opens the models page instead of showing an
//     error.
//   - The Settings page gets onManageModels (a button there opens the models
//     page) and no longer gets the storage parameters.
// =====================================================================
package com.haven.chat.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.haven.chat.ui.models.ModelsScreen
import com.haven.chat.ui.models.ModelsViewModel
import com.haven.chat.ui.privacy.PrivacyScreen
import com.haven.chat.ui.privacy.PrivacyViewModel
import com.haven.chat.ui.settings.SettingsScreen
import com.haven.chat.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch
import java.io.File

// Names of our pages. Using constants avoids typos.
private const val ROUTE_CHAT = "chat"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_PRIVACY = "privacy"      // PHASE 4 / PRIVACY
private const val ROUTE_MODELS = "models"        // MODELS

@Composable
fun HavenApp() {
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

    // MODELS: has the app already sent the user to the models page on its
    // own? rememberSaveable keeps the answer when the phone rotates, so the
    // page is not opened a second time.
    var askedForModel by rememberSaveable { mutableStateOf(false) }

    // MODELS: FIRST START. Runs once when the app UI appears. If the chosen
    // main model file does not exist (new install, or the old path of the
    // version that needed "All files access"), open the models page so the
    // user can add a model right away.
    LaunchedEffect(Unit) {
        if (!askedForModel && !File(settingsViewModel.settings.modelPath).exists()) {
            askedForModel = true
            navController.navigate(ROUTE_MODELS)
        }
    }

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
                            // "Delete without saving": deletes at once (as before).
                            onDeleteChat = { chatId ->
                                chatViewModel.deleteChat(chatId)             // PHASE 3
                            },
                            // PHASE 4 / DELETE: "Save personal things, then
                            // delete". The drawer closes so the memory dialog
                            // (progress, Cancel, result) is visible.
                            onSaveAndDeleteChat = { chatId ->
                                chatViewModel.deleteChatSavingMemories(chatId)
                                scope.launch { drawerState.close() }
                            },
                            onSettingsClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(ROUTE_SETTINGS)
                            },
                            // PHASE 4 / PRIVACY: open the memories screen.
                            onPrivacyClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(ROUTE_PRIVACY)
                            },
                            // MODELS: open the model management screen.
                            onModelsClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate(ROUTE_MODELS)
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
                    // states Loading, Ready, Thinking, Naming and Memorizing
                    // a tap does nothing.
                    // MODELS: when the chosen model file does not exist, there
                    // is nothing to load: open the models page instead.
                    onStatusClick = {
                        val s = EngineManager.state
                        if (s == EngineManager.State.NOT_LOADED || s == EngineManager.State.ERROR) {
                            if (File(settingsViewModel.settings.modelPath).exists()) {
                                settingsViewModel.loadModel()
                            } else {
                                navController.navigate(ROUTE_MODELS)
                            }
                        }
                    },
                    // Stop button: while Gemma is busy (reading, thinking or
                    // writing) the send button turns into "stop".
                    isGenerating = EngineManager.state == EngineManager.State.THINKING,
                    onStop = { chatViewModel.stopGenerating() },
                    // PHASE 3: the Wait / Cancel popup (Auto-name chats).
                    namingDialogVisible = chatViewModel.namingDialogVisible,
                    onWaitNaming = { chatViewModel.waitForNaming() },
                    onCancelNaming = { chatViewModel.cancelNaming() },
                    // PHASE 4: the bookmark button starts "remember my
                    // personal things from this chat"; the dialog shows
                    // its progress and result (also for "save, then delete").
                    onRememberClick = { chatViewModel.rememberPersonalThings() },
                    memoryJob = chatViewModel.memoryJob,
                    onCancelMemory = { chatViewModel.cancelMemoryJob() },
                    onDismissMemory = { chatViewModel.dismissMemoryJob() }
                )
            }
        }

        // ================= PAGE 2: SETTINGS =================
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                initial = settingsViewModel.settings,
                onBack = { navController.popBackStack() },
                onSaveAndReload = { newSettings ->
                    settingsViewModel.saveSettings(newSettings)
                    navController.popBackStack()   // go back to the chat after saving
                },
                // "Load Model": save what is on screen, then load it.
                // (saveSettings stores the settings AND loads the model.)
                // We stay on this page so the button can show "Loading...".
                onLoadModel = { draft -> settingsViewModel.saveSettings(draft) },
                // MODELS: the "Manage models" button in Settings. (Unsaved
                // edits on the Settings page are not kept when leaving it.)
                onManageModels = { navController.navigate(ROUTE_MODELS) }
            )
        }

        // ================= PAGE 3: PRIVACY & LOCAL DATA =================
        // PHASE 4 / PRIVACY: the saved memories. The ViewModel is created
        // here, when the page is opened (it belongs to this page only).
        composable(ROUTE_PRIVACY) {
            val privacyViewModel: PrivacyViewModel = viewModel()

            // The memories are a LIVE list (null = still loading), so the
            // screen redraws by itself after every edit or delete.
            val memories by privacyViewModel.memories.collectAsState()

            PrivacyScreen(
                memories = memories,
                problem = privacyViewModel.problem,
                onBack = { navController.popBackStack() },
                // STEP 7: the edit now includes the tags.
                onUpdateMemory = { id, question, answer, tags ->
                    privacyViewModel.updateMemory(id, question, answer, tags)
                },
                onDeleteMemory = { id -> privacyViewModel.deleteMemory(id) },
                onDeleteAll = { privacyViewModel.deleteAllMemories() },
                onClearProblem = { privacyViewModel.clearProblem() }
            )
        }

        // ================= PAGE 4: MODEL MANAGEMENT =================
        // MODELS: the models in the app's private space. The ViewModel is
        // created here, when the page is opened (it belongs to this page
        // only). bind() tells it where the settings are, because the roles
        // (main model, embedder, reranker) are saved there. Calling bind()
        // again on a redraw is harmless: it only stores the reference.
        composable(ROUTE_MODELS) {
            val modelsViewModel: ModelsViewModel = viewModel()
            modelsViewModel.bind(settingsViewModel)

            ModelsScreen(
                vm = modelsViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
