// =====================================================================
// SettingsViewModel.kt  (app/src/main/java/com/haven/chat/ui/settings/)
// WHAT: Holds the app's settings (AppSettings) so they survive screen
//       changes (rotation, opening and closing the drawer).
//       SettingsScreen edits a draft and calls saveSettings() to commit.
//
// PHASE 2: this file also LOADS THE MODEL. Both buttons end up in
//       EngineManager (the shared owner of the llama.cpp engine):
//         - "Load Model"     -> saveSettings() -> loadModel()
//         - "Save & Reload"  -> saveSettings() -> loadModel()
//       Threads and context size only take effect when the model is
//       loaded again, which is why saving reloads it.
//
// PHASE 3 CHANGES (marked "PHASE 3"): SETTINGS ARE NOW SAVED ON THE PHONE.
//   - At startup the saved settings are read from DataStore (through
//     SettingsStore), so the app opens with your last threads, context,
//     model path, SYSTEM PROMPT, stop sequence, thinking and
//     auto-name values. This fixes "settings reset when I start the app".
//   - Every saveSettings() also writes the settings to the phone.
//   - The class is now an "AndroidViewModel": it receives the Application,
//     which SettingsStore needs to find its file. HavenApp still creates
//     it with viewModel(), nothing to change there.
//   - loadModel() tells the engine whether thinking is on, so the engine
//     keeps the right reply reserve when it puts the open chat back
//     (1000 tokens with thinking, 500 without).
//   - "Engine busy" now also covers NAMING (Gemma writing a chat title).
// =====================================================================
package com.haven.chat.ui.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.haven.chat.data.SettingsStore
import com.haven.chat.engine.EngineManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    // PHASE 3: the object that reads and writes the settings file.
    private val store = SettingsStore(application)

    // PHASE 3: a background "worker scope" used only for saving.
    // It is NOT tied to the screen, so a save that has just started is
    // not cancelled if the screen closes right away.
    // SupervisorJob = one failed save does not break later saves.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The settings currently in use. Screens can read, only we can change.
    // (AppSettings is defined in SettingsScreen.kt, same package.)
    // Starts with defaults; init (below) replaces them with the saved ones
    // BEFORE any screen reads them.
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
        // thinkingEnabled, autoNameChats and the system prompt from here.
        @Volatile
        var active: AppSettings = AppSettings()
            private set

        /** The system prompt with "{today}" replaced by today's date. */
        fun systemPromptOf(s: AppSettings): String =
            s.systemPrompt.replace("{today}", LocalDate.now().toString())
    }

    // PHASE 3: runs once when this ViewModel is created (app start).
    init {
        // runBlocking = wait here until the file is read. The file is tiny
        // (a few milliseconds), and waiting guarantees that the Settings
        // screen and the chat never see the default values by mistake.
        // If anything goes wrong we simply continue with the defaults.
        val saved: AppSettings = try {
            runBlocking { store.load() }
        } catch (t: Throwable) {
            AppSettings()
        }
        settings = saved
        active = saved
    }

    // Called when the user taps "Save & Reload" (and by "Load Model").
    fun saveSettings(newSettings: AppSettings) {
        settings = newSettings
        active = newSettings

        // PHASE 3: write to the phone (in the background).
        // On a write problem (for example storage full) we ignore it: the
        // settings still work until the app is closed.
        saveScope.launch {
            try {
                store.save(newSettings)
            } catch (t: Throwable) {
                // ignore
            }
        }

        // New threads / context size / prompt only count after a reload.
        loadModel()
    }

    // Called when the user taps "Load Model" (and by the status chip).
    fun loadModel() {
        // Do not touch the engine while it is loading, answering or
        // naming a chat (PHASE 3: isBusy also covers NAMING).
        if (EngineManager.isBusy) {
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
            systemPrompt = systemPromptOf(s),
            thinking = s.thinkingEnabled      // PHASE 3: decides the reply reserve
        ) { problem ->
            loadMessage = problem ?: ""
        }
    }
}
