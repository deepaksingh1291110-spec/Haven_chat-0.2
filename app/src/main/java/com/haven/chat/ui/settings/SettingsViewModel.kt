// =====================================================================
// SettingsViewModel.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: Holds the app's saved settings (AppSettings) so they survive
//       screen changes (rotation, opening and closing the drawer).
//       SettingsScreen edits a draft and calls saveSettings() to commit.
// STATUS: Settings live in MEMORY only, so they reset when the app is
//         fully closed. Phase 3 will save them to the phone (DataStore
//         or SQLite). Only this file changes for that.
// =====================================================================
package com.haven.chat.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class SettingsViewModel : ViewModel() {

    // The settings currently in use. Screens can read, only we can change.
    // (AppSettings is defined in SettingsScreen.kt, same package.)
    var settings by mutableStateOf(AppSettings())
        private set

    // Called when the user taps "Save & Reload".
    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings

        // PHASE 2 TODO: tell the engine to unload and reload the model
        // with the new threads / context size, because those settings
        // only take effect when the model is loaded again.
    }

    // Called when the user taps "Load Model".
    fun loadModel() {
        // PHASE 2 TODO: load the GGUF file at settings.modelPath with the
        // llama.cpp engine, then set the chat chip to "Ready".
    }
}
