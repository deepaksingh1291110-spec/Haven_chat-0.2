// =====================================================================
// SettingsStore.kt  (app/src/main/java/com/haven/chat/data/)   NEW in Phase 3
// WHAT: Saves the app settings on the phone and reads them back, so they
//       are still there after the app is closed. This fixes the problem
//       "settings and system prompt reset every time I start the app".
//
// HOW:  Android DataStore (Preferences). It is a small file on the phone
//       that holds "key = value" pairs, for example
//           threads = 4
//           system_prompt = "You are a helpful assistant..."
//       DataStore writes the file safely: even if the app is killed in
//       the middle of saving, the file is not broken.
//
// WHO USES IT: SettingsViewModel (next file):
//       - at startup it calls load() to get the saved settings,
//       - on "Save & Reload" / "Load Model" it calls save().
//
// WHAT IS SAVED: every field of AppSettings (see SettingsScreen.kt):
//       threads, contextSize, thinkingEnabled, modelPath, systemPrompt,
//       stopSequence and (new) autoNameChats.
//
// IF A VALUE IS MISSING (first start, or a new setting added later), the
// default from AppSettings is used. So adding new settings in future
// updates never breaks old saved data.
//
// "suspend" = a function that may wait (it reads or writes a file). It
//       must be called from a coroutine (background-friendly code).
//       SettingsViewModel does that for us.
// =====================================================================
package com.haven.chat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.haven.chat.ui.settings.AppSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import java.io.IOException

// ONE DataStore for the whole app, named "haven_settings".
// (The file on the phone is .../files/datastore/haven_settings.preferences_pb)
// It is declared at the top level, and it must exist only ONCE per app.
// That is why it is written this way and not inside the class.
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "haven_settings"
)

class SettingsStore(context: Context) {

    // We keep the APPLICATION context (it lives as long as the app does).
    // Keeping a screen's context here would leak memory.
    private val appContext: Context = context.applicationContext

    // The names ("keys") of the saved values. A typo here would silently
    // save to a wrong name, so all of them are written once, in one place.
    private object Keys {
        val THREADS = intPreferencesKey("threads")
        val CONTEXT_SIZE = intPreferencesKey("context_size")
        val THINKING = booleanPreferencesKey("thinking_enabled")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val STOP_SEQUENCE = stringPreferencesKey("stop_sequence")
        val AUTO_NAME = booleanPreferencesKey("auto_name_chats")
    }

    /**
     * Reads the saved settings. Never throws:
     *   - nothing saved yet        -> the defaults,
     *   - one value missing        -> the default for that value,
     *   - the file cannot be read  -> the defaults.
     */
    suspend fun load(): AppSettings {
        // AppSettings() = an object with all default values.
        val d = AppSettings()

        // ".data" is a Flow (a stream of values). ".first()" takes the
        // current content once. If reading fails with an IO problem we
        // continue with an empty set (= all defaults).
        val p: Preferences = appContext.settingsDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .first()

        return AppSettings(
            // coerceIn = keep the number inside the allowed range, in case
            // the file was changed by hand or by an older version.
            threads = (p[Keys.THREADS] ?: d.threads).coerceIn(1, 6),
            contextSize = (p[Keys.CONTEXT_SIZE] ?: d.contextSize).coerceIn(2048, 32768),
            thinkingEnabled = p[Keys.THINKING] ?: d.thinkingEnabled,
            modelPath = p[Keys.MODEL_PATH] ?: d.modelPath,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: d.systemPrompt,
            stopSequence = p[Keys.STOP_SEQUENCE] ?: d.stopSequence,
            autoNameChats = p[Keys.AUTO_NAME] ?: d.autoNameChats
        )
    }

    /**
     * Saves ALL settings in one go (one write = all values or none).
     * Can throw an IOException if the phone storage is full; the caller
     * ignores that (the settings then still work until the app closes).
     */
    suspend fun save(s: AppSettings) {
        appContext.settingsDataStore.edit { p ->
            p[Keys.THREADS] = s.threads
            p[Keys.CONTEXT_SIZE] = s.contextSize
            p[Keys.THINKING] = s.thinkingEnabled
            p[Keys.MODEL_PATH] = s.modelPath
            p[Keys.SYSTEM_PROMPT] = s.systemPrompt
            p[Keys.STOP_SEQUENCE] = s.stopSequence
            p[Keys.AUTO_NAME] = s.autoNameChats
        }
    }
}
