package staff.android.offline

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Account identity and its data are one publication, not independent UI flows.
data class ScreenState(val ticket: SessionTicket? = null, val note: NoteView? = null,
    val message: String = "Choose a fixture account", val syncing: Boolean = false,
    val reading: Boolean = false, val readError: Boolean = false)

class OfflineOwner(application: Application) : AndroidViewModel(application) {
    private val database = openOffline(application)
    private val store = OfflineStore(database, HttpNoteApi("http://127.0.0.1:8788"))
    private val mutable = MutableStateFlow(ScreenState())
    val screen = mutable.asStateFlow()
    private var observer: Job? = null
    private var switching = false

    fun signIn(account: String) {
        if (switching || mutable.value.ticket != null) return
        switching = true
        viewModelScope.launch {
            try {
                val ticket = store.signIn(account)
                mutable.value = ScreenState(ticket, message = "Read locally; Refresh checks the service")
                retryRead(ticket)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = ScreenState(message = "Account unavailable") }
            finally { switching = false }
        }
    }

    fun retryRead(ticket: SessionTicket) {
        if (mutable.value.ticket != ticket) return
        observer?.cancel()
        mutable.value = mutable.value.copy(reading = true, readError = false)
        observer = viewModelScope.launch {
            try {
                store.observe(ticket).collect { note ->
                    if (mutable.value.ticket == ticket) mutable.value = mutable.value.copy(
                        note = note, reading = false, readError = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mutable.value.ticket == ticket) mutable.value = mutable.value.copy(
                    reading = false, readError = true, message = "Local read unavailable; select Retry read")
            }
        }
    }

    private fun action(ticket: SessionTicket, success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (mutable.value.ticket == ticket) mutable.value = mutable.value.copy(message = success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (mutable.value.ticket == ticket) mutable.value = mutable.value.copy(
                    message = ("Action unavailable; local state retained " +
            "(${failure.javaClass.simpleName})"))
            }
        }
    }

    fun refresh(ticket: SessionTicket) = action(ticket, "Service checked") { store.refresh(ticket) }
    fun save(ticket: SessionTicket, text: String) = action(ticket, "Saved locally") { store.save(ticket, text) }
    fun keepMine(ticket: SessionTicket, note: NoteView) = action(ticket, "Choice queued; select Sync once") {
        store.keepMine(ticket, note.serverRevision, checkNotNull(note.pendingRevision))
    }
    fun useServer(ticket: SessionTicket, note: NoteView) = action(ticket, "Server version selected") {
        store.useServer(ticket, note.serverRevision, checkNotNull(note.pendingRevision))
    }

    fun sync(ticket: SessionTicket) {
        if (mutable.value.ticket != ticket || mutable.value.syncing) return
        mutable.value = mutable.value.copy(syncing = true)
        action(ticket, "Sync attempt finished; inspect the saved status") {
            try { store.sync(ticket) }
            finally {
                if (mutable.value.ticket == ticket) mutable.value = mutable.value.copy(syncing = false)
            }
        }
    }

    fun signOut(ticket: SessionTicket) = action(ticket, "Signed out") {
        store.signOut(ticket)
        if (mutable.value.ticket == ticket) {
            observer?.cancel()
            mutable.value = ScreenState(message = "Signed out; account cache and pending edits removed")
        }
    }

    override fun onCleared() { database.close() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val owner = ViewModelProvider(this)[OfflineOwner::class.java]
        setContent {
            val state by owner.screen.collectAsStateWithLifecycle()
            CommonGoodsTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CommonGoodsHeader()
                        Text("Saved", style = MaterialTheme.typography.headlineLarge)
                        val ticket = state.ticket
                        if (ticket == null) {
                            Text(("The local service gives each fixture account a " +
            "separate Camera note."))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { owner.signIn("A") }) { Text("Account A") }
                                Button(onClick = { owner.signIn("B") }) { Text("Account B") }
                            }
                        } else {
                            Text("Account ${ticket.accountId}")
                            ProductIdentity("camera", "Camera", "Your private product note")
                            val note = state.note
                            val ready = !state.reading && !state.readError
                            when {
                                state.reading -> Text("Reading local state…")
                                state.readError -> Button(onClick = { owner.retryRead(ticket) }) { Text("Retry read") }
                                note == null -> Text("No local note. Select Refresh to obtain a base.")
                            }
                            if (note != null) key(ticket) { NoteControls(note, ticket, owner, ready) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { owner.refresh(ticket) }, enabled = ready) { Text("Refresh") }
                                Button(onClick = { owner.sync(ticket) }, enabled = ready && note?.pendingRevision != null &&
                                    note.conflict == false && !state.syncing) { Text("Sync once") }
                            }
                            if (state.syncing) Text("Sending a captured edit; another local Save is allowed")
                            OutlinedButton(onClick = { owner.signOut(ticket) }) { Text("Sign out · discard local edits") }
                        }
                        val actionUnavailable = state.message.startsWith("Action unavailable;")
                        if (state.message.isNotBlank()) ShopMessage(
                            if (actionUnavailable)
                                "Action unavailable. Check your saved note, " +
                                    "then try again."
                            else state.message
                        )
                        LabControls {
                            Text("Local fixture: manual sync; no background scheduler")
                            if (actionUnavailable) Text("Diagnostic: ${state.message}")
                            state.note?.let {
                                Text("Server revision ${it.serverRevision}: ${it.serverNote}")
                                Text("Last service observation: " +
                                    "${it.checkedAtMillis} ms since epoch")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteControls(note: NoteView, ticket: SessionTicket, owner: OfflineOwner, ready: Boolean) {
    var draft by rememberSaveable(ticket.epoch) { mutableStateOf(note.visibleNote) }
    ShopMessage("Saved note: ${note.visibleNote}")
    Text(when { note.conflict -> "Conflict"; note.pendingRevision != null -> "Pending"; else -> "Synced" })
    if (note.conflict) ShopMessage("Service revision ${note.serverRevision}: ${note.serverNote}")
    OutlinedTextField(draft, { if (it.length <= 2000) draft = it }, label = { Text("Note draft") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { owner.save(ticket, draft) }, enabled = ready) { Text("Save locally") }
    if (note.conflict) {
        Text(("Another version exists. Keep mine queues this saved " +
            "local note against the displayed server revision."))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { owner.keepMine(ticket, note) }, enabled = ready) { Text("Keep mine") }
            OutlinedButton(onClick = { owner.useServer(ticket, note) }, enabled = ready) { Text("Use server") }
        }
    }
}
