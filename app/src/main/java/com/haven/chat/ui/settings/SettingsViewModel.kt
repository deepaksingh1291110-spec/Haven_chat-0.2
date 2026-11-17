// =====================================================================
// SettingsViewModel.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: Holds the app's settings (AppSettings) so they survive screen
//       changes (rotation, opening and closing the drawer).
//       SettingsScreen edits a draft and calls saveSettings() to commit.
//
// PHASE 2: this file now also LOADS THE MODEL. Both buttons end up in
//       EngineManager (the shared owner of the llama.cpp engine):
//         - "Load Model"     -> loadModel()
//         - "Save & Reload"  -> saveSettings() -> loadModel()
//       Threads and context size only take effect when the model is
//       loaded again, which is why saving reloads it.
//
// STATUS: Settings still live in MEMORY only (lost when the app is
//         fully closed). Phase 3 will save them with DataStore.
// =====================================================================
package com.haven.chat.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.haven.chat.engine.EngineManager
import java.time.LocalDate

class SettingsViewModel : ViewModel() {

    // The settings currently in use. Screens can read, only we can change.
    // (AppSettings is defined in SettingsScreen.kt, same package.)
    var settings by mutableStateOf(AppSettings())
        private set

    // Result text of the last "Load Model": empty = nothing to say,
    // otherwise a plain-words problem. (No screen shows it yet; the
    // status chip in the chat shows "Error" and we can show this later.)
    var loadMessage by mutableStateOf("")
        private set

    // ----- Shared copy for ChatViewModel -----
    // ChatViewModel is a different class and cannot see this ViewModel,
    // so the settings in use are ALSO kept here, in one shared place.
    companion object {
        // The settings the engine was last given. ChatViewModel reads
        // thinkingEnabled and the system prompt from here.
        @Volatile
        var active: AppSettings = AppSettings()
            private set

        /** The system prompt with "{today}" replaced by today's date. */
        fun systemPromptOf(s: AppSettings): String =
            s.systemPrompt.replace("{today}", LocalDate.now().toString())
    }

    // Called when the user taps "Save & Reload".
    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        active = newSettings
        // New threads / context size / prompt only count after a reload.
        loadModel()
    }

    // Called when the user taps "Load Model".
    fun loadModel() {
        // Do not touch the engine while it is loading or answering.
        if (EngineManager.state == EngineManager.State.LOADING ||
            EngineManager.state == EngineManager.State.THINKING
        ) {
            loadMessage = "The engine is busy. Try again in a moment."
            return
        }

        val s = settings
        active = s
        loadMessage = ""

        // EngineManager loads on a background thread and calls us back
        // on the main thread. null = success, text = the problem.
        EngineManager.load(
            path = s.modelPath,
            contextSize = s.contextSize,
            threads = s.threads,
            systemPrompt = systemPromptOf(s)
        ) { problem ->
            loadMessage = problem ?: ""
        }
    }
}
