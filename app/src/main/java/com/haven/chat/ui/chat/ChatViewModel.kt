// =====================================================================
// ChatViewModel.kt  (app/src/main/java/com/haven/chat/ui/chat/)
// WHAT: The "brain" of the chat screen. It OWNS the data (message list,
//       model status) and the actions (send, new chat, open chat...).
//       ChatScreen only draws what this class holds.
// WHY A VIEWMODEL: Android destroys and rebuilds screens (for example
//       when you rotate the phone). A ViewModel survives that, so the
//       chat is not lost.
//
// PHASE 2: sendMessage() asks the real Gemma model through EngineManager
//       and STREAMS the reply (bubble grows, thinking box fills, speed
//       line at the end).
//
// PHASE 3 (everything new is marked "PHASE 3"): CHATS ARE SAVED.
//   1) The chat starts EMPTY (the dummy conversation is gone).
//   2) The first message creates a chat in the database. Its title is
//      made at once from the first words of that message.
//   3) Every user message and every finished answer is saved.
//   4) AUTO-NAME (setting, default OFF): after the FIRST answer, Gemma
//      writes a better title in the background (hidden). While it works
//      the engine is busy. If you send a message in that time, a popup
//      asks: Wait or Cancel (see namingDialogVisible below).
//   5) openChat(id): shows an old chat and puts it back into Gemma's head
//      (as much as fits the context window, EngineManager.openChat).
//   6) deleteChat(id) and renameChat(id, title) for the "..." menu.
//   7) The drawer's chat list and search are served from here (chats,
//      setSearchQuery) as a live stream from the database.
//   8) It is now an AndroidViewModel (needs the Application to reach the
//      database). HavenApp still creates it with viewModel().
//
// MAX REPLY LENGTH (after Phase 3): the reply limit is no longer a fixed
//   1024. Each send uses SettingsViewModel.active.maxReplyTokens, the number
//   the user types in Settings (64 to 32768, default 2048). The engine still
//   stops earlier if the context window is full.
//
// PHASE 4 (everything new is marked "PHASE 4"): REMEMBER PERSONAL THINGS.
//   1) The typed phrase "remember my personal things from this chat" (and,
//      later, a menu item that calls rememberPersonalThings()) starts the
//      memory job INSTEAD of a normal message. The phrase is never saved
//      as a chat message and never sent to Gemma as a question.
//   2) The job itself is MemoryExtractor.kt: Gemma only DECIDES which of
//      the user's messages are personal; the app copies each personal
//      question and its saved final answer, word for word, into the
//      memories database (a separate file, memories.db). Deleting a chat
//      never deletes its memories.
//   3) While the job runs, "memoryJob" holds the progress for a dialog
//      (progress, a Cancel button, and at the end a short result text).
//      The dialog is drawn by ChatScreen / HavenApp (next files).
//   4) While it runs, nothing else may start (sending, opening, new chat,
//      delete): our "working" flag AND the engine state MEMORIZING block
//      them. At the end the open chat is put back into Gemma's head.
//
// STILL TEMPORARY:
//   - No Agent and no memory SEARCH yet (later steps of Phase 4).
//   - Delete chat does not save personal things first yet (step 4).
//
// IDS: messages shown on screen need a unique id for the list. Messages
//   loaded from the database keep their database id (positive numbers).
//   Messages created in this session get a temporary NEGATIVE id, so the
//   two kinds can never collide. (The memory job reads the messages from
//   the DATABASE, so it always sees the real, positive ids.)
// =====================================================================
package com.haven.chat.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haven.chat.data.ChatMessage
import com.haven.chat.data.ChatRepository
import com.haven.chat.data.ChatSummary
import com.haven.chat.data.MemoryRepository
import com.haven.chat.data.Role
import com.haven.chat.engine.EngineManager
import com.haven.chat.engine.MemoryExtractor
import com.haven.chat.engine.MemoryJobResult
import com.haven.chat.ui.settings.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------
// PHASE 4 (NEW): what the memory dialog shows.
//   running  true = Gemma is working (show progress and a Cancel button)
//            false = the job is over (show "message" and an OK button)
//   checked / total  how many of the user's messages are checked so far,
//            out of how many. total = 0 means "not known yet" (the first
//            batch is still being read).
//   saved    how many memories were saved so far
//   message  the result text, only when running = false
// ---------------------------------------------------------------------
class MemoryJobUi(
    val running: Boolean,
    val checked: Int = 0,
    val total: Int = 0,
    val saved: Int = 0,
    val message: String? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    // PHASE 3: the one place that saves and loads chats.
    private val repo = ChatRepository(application)

    // PHASE 4: the job that finds the personal messages of a chat and
    // saves them (it uses the chat repository and the memory repository).
    private val extractor = MemoryExtractor(repo, MemoryRepository(application))

    // ----- STATE (data the screen shows) -----
    // "mutableStateOf" = when this value changes, Compose redraws.
    // "private set" = only this class can change it; screens can only read.

    // The messages in the open chat. Starts EMPTY (PHASE 3).
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set

    // PHASE 3: the database id of the open chat, or null for a new chat
    // that has no message yet (it is created with the first message).
    // The drawer can use it to mark the open chat.
    var currentChatId by mutableStateOf<Long?>(null)
        private set

    // PHASE 3: true while the "Wait / Cancel" popup must be shown. It
    // appears when the user sends a message while Gemma is still naming
    // the chat. ChatScreen / HavenApp draw the popup (next steps).
    var namingDialogVisible by mutableStateOf(false)
        private set

    // PHASE 4: the state of the memory dialog. null = no dialog is shown.
    // ChatScreen / HavenApp draw it (next files).
    var memoryJob by mutableStateOf<MemoryJobUi?>(null)
        private set

    // Text of the chip under the title, for example "gemma4-e2b-q4_0 • Ready".
    // It is a "getter": it is calculated every time the screen reads it.
    // EngineManager.state is Compose state, so when the engine status
    // changes (Loading, Ready, Thinking...) the chip redraws by itself.
    val modelStatus: String
        get() = EngineManager.modelName + " \u2022 " + EngineManager.statusText()

    // ----- DRAWER: chat list and search (PHASE 3) -----

    // The text in the drawer's search box.
    private val searchQuery = MutableStateFlow("")

    // The chat list for the drawer: a LIVE stream from the database.
    // Whenever the search text OR the chats change, a new list arrives.
    // (flatMapLatest = when the search text changes, drop the old query
    //  and start the new one.)
    // stateIn = turns the stream into a value the screen can read; it is
    //  kept alive for 5 s after the drawer stops looking (rotation).
    @OptIn(ExperimentalCoroutinesApi::class)
    val chats: StateFlow<List<ChatSummary>> = searchQuery
        .flatMapLatest { query -> repo.observeChats(query) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Called by the drawer when the user types in the search box. */
    fun setSearchQuery(text: String) {
        searchQuery.value = text
    }

    // ----- PRIVATE PARTS -----

    // (The longest reply used to be fixed at 1024 tokens here. It is now the
    //  setting "Max reply length" that the user types in Settings, read at
    //  every send in startGeneration().)

    // Counter for temporary ids of messages made in this session.
    // It counts DOWN (-1, -2, -3...), see "IDS" at the top.
    private var nextLocalId: Long = -1L

    // Ids of messages that exist only on screen and must NOT be saved and
    // NOT be given to Gemma: error texts and the "(stopped)" note.
    private val localOnlyIds = HashSet<Long>()

    // True while we are busy with something the engine state does not show:
    // saving a message, opening a chat, deleting a chat (PHASE 4: and the
    // memory job). It stops a second send/open/delete from starting in the
    // middle of the first one.
    private var working = false

    // A message the user sent while Gemma was naming the chat. It is sent
    // as soon as the naming is over (finished or cancelled).
    private var pendingText: String? = null

    // True while Gemma is writing (read from the engine, the single truth).
    private val isGenerating: Boolean
        get() = EngineManager.state == EngineManager.State.THINKING

    // PHASE 3: this runs when the ViewModel is created.
    init {
        // Tell the engine how to get the open chat. After "Save & Reload"
        // in Settings the model is loaded again, and the engine uses this
        // to put the open chat back into Gemma's head.
        EngineManager.historyProvider = { historyForEngine(messages) }
    }

    // ----- ACTIONS (things the user can do) -----

    // Called when the user taps send.
    fun sendMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        // PHASE 4: the typed phrase starts the memory job. It is NOT a
        // chat message: it is not shown, not saved and not sent to Gemma.
        if (isMemoryCommand(clean)) {
            rememberPersonalThings()
            return
        }

        // Ignore the message if we are still busy with something.
        if (working) return

        // PHASE 3: Gemma is writing a chat title right now. Keep the text,
        // show the "Wait / Cancel" popup, and send it when naming is over.
        if (EngineManager.state == EngineManager.State.NAMING) {
            pendingText = clean
            namingDialogVisible = true
            return
        }

        // Gemma is writing an answer, or the model is loading (PHASE 4: or
        // Gemma is saving memories): ignore.
        if (isGenerating ||
            EngineManager.state == EngineManager.State.LOADING ||
            EngineManager.state == EngineManager.State.MEMORIZING
        ) return

        working = true

        // 1) Show the user's message at once.
        // "messages + x" makes a NEW list with x at the end.
        // (Compose notices a new list; changing an old list in place
        //  would NOT redraw.)
        val userMessage = ChatMessage(id = nextLocalId--, role = Role.USER, text = clean)
        messages = messages + userMessage

        // 2) Show an EMPTY answer bubble. The streamed text is poured
        //    into it while Gemma writes. We remember its id.
        val answerId = nextLocalId--
        messages = messages + ChatMessage(id = answerId, role = Role.ASSISTANT, text = "")

        // 3) PHASE 3: save, then ask Gemma. The database work is quick
        //    (milliseconds), and doing it first guarantees that the chat
        //    row exists before the answer comes back.
        viewModelScope.launch {
            // Create the chat with the first message (new chat only).
            val isNewChat = currentChatId == null
            val chatId = currentChatId
                ?: repo.createChat(ChatRepository.titleFromFirstMessage(clean))
            currentChatId = chatId

            // Save the user's message (also moves the chat to the top).
            repo.addMessage(chatId, userMessage)

            // Ask the real engine.
            startGeneration(
                chatId = chatId,
                answerId = answerId,
                userText = clean,
                isFirstExchange = isNewChat
            )
        }
    }

    // Asks Gemma and streams the answer into the bubble "answerId".
    private fun startGeneration(
        chatId: Long,
        answerId: Long,
        userText: String,
        isFirstExchange: Boolean
    ) {
        // Collect the pieces that arrive while Gemma writes.
        val answerSoFar = StringBuilder()
        val thinkingSoFar = StringBuilder()

        // The thinking switch and the longest reply (in tokens, thinking
        // included) come from the Settings screen.
        val thinkingOn = SettingsViewModel.active.thinkingEnabled
        val maxReply = SettingsViewModel.active.maxReplyTokens

        // All three callbacks run on the main thread, so changing
        // "messages" here is safe.
        EngineManager.generate(
            userText = userText,
            maxNewTokens = maxReply,
            thinking = thinkingOn,

            // A piece of THINKING text arrived.
            onThinking = { piece ->
                thinkingSoFar.append(piece)
                updateMessage(answerId) { it.copy(thinking = thinkingSoFar.toString()) }
            },

            // A piece of ANSWER text arrived.
            onAnswer = { piece ->
                answerSoFar.append(piece)
                updateMessage(answerId) { it.copy(text = answerSoFar.toString()) }
            },

            // Gemma finished (or was stopped, or failed).
            onDone = { result ->
                val failed = result.error != null
                val emptyStop = !failed && result.answer.isEmpty() && result.stopped

                updateMessage(answerId) { old ->
                    when {
                        // A problem: show it in plain words inside the bubble.
                        failed -> old.copy(text = result.error ?: "")

                        // Stopped by the user and nothing was written.
                        emptyStop -> old.copy(text = "(stopped)")

                        // Normal end (also covers "stopped after some text").
                        else -> old.copy(
                            text = result.answer.ifEmpty { old.text },
                            thinking = result.thinking.ifEmpty { null },
                            tokensPerSecond = result.tokensPerSecond,
                            tokenCount = result.tokenCount
                        )
                    }
                }

                // PHASE 3: error texts and "(stopped)" are shown, but never
                // saved and never given to Gemma later.
                if (failed || emptyStop) localOnlyIds.add(answerId)

                finishTurn(
                    chatId = chatId,
                    answerId = answerId,
                    userText = userText,
                    cleanFinish = !failed && !result.stopped,
                    isFirstExchange = isFirstExchange
                )
            }
        )
    }

    // PHASE 3: runs after every answer. Saves the answer, and (for the
    // first answer of a new chat, if Auto-name is ON) starts the naming.
    private fun finishTurn(
        chatId: Long,
        answerId: Long,
        userText: String,
        cleanFinish: Boolean,           // no error and not stopped by the user
        isFirstExchange: Boolean
    ) {
        viewModelScope.launch {
            // Save the answer (not error texts, not empty ones).
            val answer = messages.firstOrNull { it.id == answerId }
            if (answer != null && answerId !in localOnlyIds && answer.text.isNotBlank()) {
                repo.addMessage(chatId, answer)
            }

            // Should Gemma now write a title?
            val wantTitle = isFirstExchange &&
                    cleanFinish &&
                    SettingsViewModel.active.autoNameChats &&
                    answer != null &&
                    answer.text.isNotBlank()

            if (wantTitle && answer != null) {
                startNaming(chatId, userText, answer.text)
            }
            // From here the engine state (NAMING or READY) protects the
            // next send, so our own "working" flag can go.
            working = false
        }
    }

    // PHASE 3: the hidden title request. The engine sets its state to
    // NAMING at once, so the next send shows the Wait / Cancel popup.
    private fun startNaming(chatId: Long, firstUserText: String, firstAnswer: String) {
        val systemPrompt = SettingsViewModel.systemPromptOf(SettingsViewModel.active)
        EngineManager.generateTitle(systemPrompt, firstUserText, firstAnswer) { title ->
            // title == null: cancelled, failed or empty. The quick title
            // from the first words stays.
            viewModelScope.launch {
                if (title != null) repo.renameChat(chatId, title)

                // Naming is over: close the popup and send the waiting
                // message, if there is one.
                namingDialogVisible = false
                val waiting = pendingText
                pendingText = null
                if (waiting != null) sendMessage(waiting)
            }
        }
    }

    // PHASE 3: popup button "Wait": close the popup. The message is sent
    // when the title is ready (a few seconds).
    fun waitForNaming() {
        namingDialogVisible = false
    }

    // PHASE 3: popup button "Cancel": stop the naming now. The chat keeps
    // its quick title. The waiting message is sent as soon as the engine
    // is free (a moment).
    fun cancelNaming() {
        namingDialogVisible = false
        EngineManager.cancelNaming()
    }

    // Called when the user wants to stop the answer.
    fun stopGenerating() {
        EngineManager.stop()
    }

    // Called when the user taps the pencil icon (start a fresh chat).
    fun startNewChat() {
        // Do nothing while the engine or we are busy, to avoid a reply
        // landing in the wrong chat.
        if (working || EngineManager.isBusy) return
        showEmptyChat()
    }

    // PHASE 3: called when the user taps a chat in the drawer.
    fun openChat(chatId: Long) {
        if (working || EngineManager.isBusy) return
        working = true
        viewModelScope.launch {
            val loaded = repo.loadMessages(chatId)

            // Show the whole old chat on screen.
            localOnlyIds.clear()
            pendingText = null
            messages = loaded
            currentChatId = chatId

            // Put the newest messages that fit back into Gemma's head.
            // (If no model is loaded, this is done automatically later,
            //  when the model is loaded: see historyProvider.)
            val s = SettingsViewModel.active
            EngineManager.openChat(
                systemPrompt = SettingsViewModel.systemPromptOf(s),
                history = historyForEngine(loaded),
                thinking = s.thinkingEnabled
            ) { /* nothing to do with the count for now */ }

            working = false
        }
    }

    // PHASE 3: "..." menu > Rename. Allowed at any time (it only touches
    // the database, not the engine).
    fun renameChat(chatId: Long, title: String) {
        viewModelScope.launch { repo.renameChat(chatId, title) }
    }

    // PHASE 3: "..." menu > Delete (after the user confirmed).
    // The database removes the chat AND all its messages, and wipes them
    // from the file (secure_delete).
    fun deleteChat(chatId: Long) {
        // Not while Gemma is writing: the answer would have no chat to go to.
        if (working || EngineManager.isBusy) return
        working = true
        viewModelScope.launch {
            repo.deleteChat(chatId)
            // If it was the open chat, show an empty new chat, and make
            // Gemma forget it too.
            if (currentChatId == chatId) showEmptyChat()
            working = false
        }
    }

    // ----- PHASE 4: REMEMBER PERSONAL THINGS -----

    // PHASE 4: starts the memory job for the OPEN chat. Called by the typed
    // phrase (see sendMessage) and, in the next files, by a menu item.
    // Whatever happens, the user gets a dialog (progress, or a short text).
    fun rememberPersonalThings() {
        // The dialog is already open (a job is running or its result is
        // still shown): do nothing.
        if (memoryJob != null) return

        // Busy with a send, an open or a delete, or the engine is busy.
        // (This also covers the short moment after an answer, while it is
        // still being saved: then the answer would be missing.)
        if (working || EngineManager.isBusy) {
            memoryJob = MemoryJobUi(
                running = false,
                message = "Gemma is busy. Try again in a moment."
            )
            return
        }

        // A new chat with no message yet is not in the database.
        val chatId = currentChatId
        if (chatId == null) {
            memoryJob = MemoryJobUi(
                running = false,
                message = "This chat has no messages yet, so there is nothing to remember."
            )
            return
        }

        working = true
        memoryJob = MemoryJobUi(running = true)
        val s = SettingsViewModel.active

        viewModelScope.launch {
            val ui: MemoryJobUi = try {
                val result = extractor.run(
                    chatId = chatId,
                    memoryThinking = s.memoryThinking,
                    systemPrompt = SettingsViewModel.systemPromptOf(s),
                    chatThinking = s.thinkingEnabled,
                    // Called at the END of the job, on the main thread: the
                    // open chat is put back into Gemma's head.
                    restoreHistory = { historyForEngine(messages) },
                    onProgress = { p ->
                        memoryJob = MemoryJobUi(
                            running = true,
                            checked = p.checked,
                            total = p.total,
                            saved = p.saved
                        )
                    }
                )
                MemoryJobUi(running = false, saved = result.saved, message = memoryMessage(result))
            } catch (e: CancellationException) {
                throw e      // the ViewModel is being destroyed: let it stop
            } catch (e: Exception) {
                MemoryJobUi(
                    running = false,
                    message = "Something went wrong: " + (e.message ?: e.javaClass.simpleName)
                )
            } finally {
                working = false
            }
            memoryJob = ui
        }
    }

    // PHASE 4: the dialog's Cancel button. The batch that Gemma is reading
    // is interrupted; what earlier batches saved stays saved.
    fun cancelMemoryJob() {
        extractor.cancel()
    }

    // PHASE 4: the dialog's OK button (only works when the job is over).
    fun dismissMemoryJob() {
        if (memoryJob?.running != true) memoryJob = null
    }

    // Stop writing if this screen is destroyed for good.
    override fun onCleared() {
        EngineManager.historyProvider = null
        EngineManager.stop()
        super.onCleared()
    }

    // ----- HELPERS -----

    // Shows a clean new chat and resets the conversation inside the engine.
    private fun showEmptyChat() {
        localOnlyIds.clear()
        pendingText = null
        messages = emptyList()
        currentChatId = null
        EngineManager.newChat(SettingsViewModel.systemPromptOf(SettingsViewModel.active))
    }

    // Turns the screen's messages into the simple list the engine wants.
    // Skips empty bubbles and the screen-only texts (errors, "(stopped)").
    private fun historyForEngine(list: List<ChatMessage>): List<EngineManager.HistoryItem> {
        return list
            .filter { it.id !in localOnlyIds && it.text.isNotBlank() }
            .map { EngineManager.HistoryItem(isUser = it.role == Role.USER, text = it.text) }
    }

    // Changes ONE message (found by id) and keeps all the others.
    // It builds a NEW list, so Compose redraws.
    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        messages = messages.map { if (it.id == id) change(it) else it }
    }

    // PHASE 4: is this text the memory command? Capitals, extra spaces and
    // a final "." "!" or "?" do not matter.
    private fun isMemoryCommand(text: String): Boolean {
        val t = text.trim().lowercase()
            .trimEnd('.', '!', '?', ' ')
            .replace(Regex("\\s+"), " ")
        return t == MEMORY_COMMAND
    }

    // PHASE 4: "3 memories", "1 memory".
    private fun memoriesText(n: Int): String = if (n == 1) "1 memory" else "$n memories"

    // PHASE 4: the short result text for the dialog.
    private fun memoryMessage(r: MemoryJobResult): String = when {
        r.error != null ->
            "Could not finish: " + r.error +
                    (if (r.saved > 0) "\n" + memoriesText(r.saved) + " saved before the problem." else "")
        r.cancelled ->
            if (r.saved > 0) "Cancelled. " + memoriesText(r.saved) + " saved before you cancelled."
            else "Cancelled. Nothing was saved."
        r.saved == 0 ->
            "Done. No new personal things were found in this chat."
        else ->
            "Done. " + memoriesText(r.saved) + " saved from this chat."
    }

    companion object {
        // PHASE 4: the typed phrase that starts the memory job.
        const val MEMORY_COMMAND = "remember my personal things from this chat"
    }
}
