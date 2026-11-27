// =====================================================================
// ModelsViewModel.kt  (app/src/main/java/com/haven/chat/ui/models/)   NEW
// WHAT: The "brain" of the Model management screen. It holds the list of
//       the models in the app's private folder (ModelStore) and does what
//       the user asks there:
//         - show the models, and which one has which ROLE,
//         - ADD a model (copy a picked file in, with progress and Cancel),
//         - choose the MAIN model (the chat model Gemma runs) and load it,
//         - choose the EMBEDDER and the RERANKER (the two search models of
//           heavy search) or clear them,
//         - RENAME a model,
//         - DELETE a model.
//       ModelsScreen (next file) only DRAWS what this class holds.
//
// ROLES: a model file can be used in three roles. The roles are not saved
//   in the file; they are the three PATHS in the settings:
//       settings.modelPath     = the MAIN model (chat)
//       settings.embedderPath  = the EMBEDDER (bge-m3)
//       settings.rerankerPath  = the RERANKER (bge-reranker-v2-m3)
//   Choosing a role = writing that path into the settings. The Settings
//   screen reads the same three paths, so both screens always agree.
//
// HOW IT TALKS TO THE SETTINGS: the screen calls bind(settingsViewModel)
//   once. This class reads settingsViewModel.settings and changes them with
//   settingsViewModel.applySettings(new, reload). "reload = true" loads the
//   main model again (only needed when the MAIN path changes).
//   (applySettings is a small new function in SettingsViewModel.kt.)
//
// RULES THAT PROTECT THE USER (the engine holds the model files open):
//   - A role cannot be changed while the engine is busy (loading,
//     answering, naming or saving memories).
//   - The model that is LOADED now can be renamed: the file is renamed, the
//     settings get the new path, and the model is loaded again (the engine
//     remembers the path it loaded from).
//   - The model that is LOADED now can be deleted: it is unloaded first.
//     The chat then says "Not loaded" until another model is chosen.
//   - The embedder and the reranker can only be renamed or deleted while
//     the engine is not busy (a deep search or a memory job may be using
//     them).
//   - Renaming the EMBEDDER changes the name its saved search vectors are
//     filed under (see MemoryIndexer.embeddingModelName), so those vectors
//     are made again at the next memory job. The user is told.
//
// AUTO-PICK: when a model is added and the main model path points to a file
//   that does not exist (first start, or the old path from the version that
//   needed "All files access"), the new model becomes the MAIN model and is
//   loaded at once. A file whose name looks like a search model (bge,
//   rerank, embed) is never picked as the main model automatically.
//
// LEAVING THE SCREEN: this ViewModel belongs to the Models page. If the user
//   leaves the page while a model is being copied, the copy is CANCELLED
//   (ModelStore deletes the unfinished ".part" file). The screen says so.
//
// NEVER CRASHES: every problem becomes a plain-words text in "message".
// =====================================================================
package com.haven.chat.ui.models

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.haven.chat.data.ModelStore
import com.haven.chat.data.StoredModel
import com.haven.chat.engine.EngineManager
import com.haven.chat.ui.settings.AppSettings
import com.haven.chat.ui.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

// ---------------------------------------------------------------------
// What the screen shows while a model is being copied.
//   copied  bytes copied so far
//   total   size of the file in bytes, or 0 when it is not known
// ---------------------------------------------------------------------
class ImportUi(val copied: Long, val total: Long)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    // The one place that works with the private models folder.
    private val store = ModelStore(application)

    // The settings (set by bind). Null until the screen has called bind().
    private var settingsVm: SettingsViewModel? = null

    // ----- STATE (data the screen shows) -----

    // The models in the folder, sorted by name.
    var models by mutableStateOf<List<StoredModel>>(emptyList())
        private set

    // false until the folder was read once (so the screen does not flash
    // "No models yet" for a moment when it opens).
    var listLoaded by mutableStateOf(false)
        private set

    // The copy that is running, or null.
    var importUi by mutableStateOf<ImportUi?>(null)
        private set

    // A plain-words problem or notice for a small dialog, or null.
    var message by mutableStateOf<String?>(null)
        private set

    // The running copy (so Cancel can stop it).
    private var importJob: Job? = null

    init {
        // Delete unfinished copies of an earlier run, then read the folder.
        viewModelScope.launch {
            store.cleanLeftovers()
            reloadList()
        }
    }

    // ----- CONNECTION TO THE SETTINGS -----

    /** Called once by the screen / HavenApp. */
    fun bind(settingsViewModel: SettingsViewModel) {
        settingsVm = settingsViewModel
    }

    // The settings in use (Compose state, so the screen redraws when a role
    // changes). Falls back to the shared copy before bind().
    private val settings: AppSettings
        get() = settingsVm?.settings ?: SettingsViewModel.active

    // Writes new settings (saved on the phone). reload = true loads the main
    // model again.
    private fun apply(newSettings: AppSettings, reload: Boolean) {
        val vm = settingsVm
        if (vm == null) {
            message = "The settings are not ready yet. Try again."
            return
        }
        vm.applySettings(newSettings, reload)
    }

    // ----- ROLE QUESTIONS (the screen asks these for every model) -----

    fun isMain(model: StoredModel): Boolean = settings.modelPath == model.path
    fun isEmbedder(model: StoredModel): Boolean = settings.embedderPath == model.path
    fun isReranker(model: StoredModel): Boolean = settings.rerankerPath == model.path

    /** Free space on the phone, as text, for the screen ("12.3 GB"). */
    val freeSpaceText: String
        get() = ModelStore.formatSize(store.freeSpaceBytes())

    // True when a model is in RAM (or loading): then the file is in use.
    private fun engineHoldsModel(): Boolean =
        EngineManager.state != EngineManager.State.NOT_LOADED &&
                EngineManager.state != EngineManager.State.ERROR

    // ----- ACTIONS -----

    /** Reads the folder again. */
    private suspend fun reloadList() {
        models = store.list()
        listLoaded = true
    }

    /**
     * ADD a model: copies the file behind [uri] (from Android's file picker)
     * into the private folder. Progress is in importUi; problems are in
     * message. Only one copy can run at a time.
     */
    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importUi = ImportUi(copied = 0, total = 0)

        importJob = viewModelScope.launch {
            try {
                // The progress callback runs on a background thread. Writing a
                // Compose state from there is allowed.
                val result = store.importModel(uri) { copied, total ->
                    importUi = ImportUi(copied, total)
                }
                val added = result.model
                if (added != null) {
                    reloadList()
                    autoPickMain(added)
                } else {
                    message = result.error ?: "The model could not be added."
                }
            } catch (e: CancellationException) {
                throw e                    // cancelled: ModelStore already cleaned up
            } catch (e: Exception) {
                message = "The model could not be added: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                importUi = null
            }
        }
    }

    /** The Cancel button of the copy dialog. */
    fun cancelImport() {
        importJob?.cancel()
    }

    // The new model becomes the MAIN model when there is no usable main model
    // yet (see the notes at the top).
    private fun autoPickMain(added: StoredModel) {
        if (File(settings.modelPath).exists()) return
        if (looksLikeSearchModel(added.fileName)) return
        if (EngineManager.isBusy) return
        apply(settings.copy(modelPath = added.path), reload = true)
    }

    private fun looksLikeSearchModel(fileName: String): Boolean {
        val n = fileName.lowercase()
        return n.contains("bge") || n.contains("rerank") || n.contains("embed")
    }

    /** Make [model] the MAIN model and load it now. */
    fun setMain(model: StoredModel) {
        if (isMain(model) && engineHoldsModel()) return           // already running
        if (EngineManager.isBusy) {
            message = BUSY_TEXT
            return
        }
        apply(settings.copy(modelPath = model.path), reload = true)
    }

    /** Make [model] the EMBEDDER, or clear the role with null. */
    fun setEmbedder(model: StoredModel?) {
        apply(settings.copy(embedderPath = model?.path ?: ""), reload = false)
    }

    /** Make [model] the RERANKER, or clear the role with null. */
    fun setReranker(model: StoredModel?) {
        apply(settings.copy(rerankerPath = model?.path ?: ""), reload = false)
    }

    /**
     * RENAME [model]. [newName] may be typed with or without ".gguf". The
     * roles that pointed to the old path point to the new one afterwards.
     */
    fun rename(model: StoredModel, newName: String) {
        viewModelScope.launch {
            val s = settings
            val wasMain = s.modelPath == model.path
            val wasEmbedder = s.embedderPath == model.path
            val wasReranker = s.rerankerPath == model.path
            val mainLoaded = wasMain && engineHoldsModel()

            // The engine may be using these files right now.
            if (EngineManager.isBusy && (mainLoaded || wasEmbedder || wasReranker)) {
                message = BUSY_TEXT
                return@launch
            }

            val result = store.rename(model.fileName, newName)
            val renamed = result.model
            if (renamed == null) {
                message = result.error ?: "The model could not be renamed."
                return@launch
            }

            // Move the roles to the new path.
            if (renamed.path != model.path) {
                apply(
                    s.copy(
                        modelPath = if (wasMain) renamed.path else s.modelPath,
                        embedderPath = if (wasEmbedder) renamed.path else s.embedderPath,
                        rerankerPath = if (wasReranker) renamed.path else s.rerankerPath
                    ),
                    // The engine remembers the OLD path it loaded from, so a
                    // loaded main model must be loaded again from the new one.
                    reload = mainLoaded
                )
            }
            reloadList()

            if (wasEmbedder && renamed.path != model.path) {
                message = "Renamed. The saved search vectors belong to the old name, " +
                        "so they will be made again the next time you save memories."
            }
        }
    }

    /**
     * DELETE [model] from the phone. (The screen asks "Are you sure?" first.)
     * Roles that used it are cleared. The loaded main model is unloaded first.
     */
    fun delete(model: StoredModel) {
        viewModelScope.launch {
            val s = settings
            val wasMain = s.modelPath == model.path
            val wasEmbedder = s.embedderPath == model.path
            val wasReranker = s.rerankerPath == model.path
            val mainLoaded = wasMain && engineHoldsModel()

            if (EngineManager.isBusy && (mainLoaded || wasEmbedder || wasReranker)) {
                message = BUSY_TEXT
                return@launch
            }

            // Free the model from RAM before its file goes away.
            if (mainLoaded) EngineManager.unload()

            if (!store.delete(model.fileName)) {
                message = "The model could not be deleted."
                return@launch
            }

            if (wasMain || wasEmbedder || wasReranker) {
                apply(
                    s.copy(
                        modelPath = if (wasMain) "" else s.modelPath,
                        embedderPath = if (wasEmbedder) "" else s.embedderPath,
                        rerankerPath = if (wasReranker) "" else s.rerankerPath
                    ),
                    reload = false
                )
            }
            reloadList()
        }
    }

    /** Closes the message dialog. */
    fun dismissMessage() {
        message = null
    }

    companion object {
        private const val BUSY_TEXT =
            "Gemma is busy (loading, answering or saving memories). Try again in a moment."
    }
}
