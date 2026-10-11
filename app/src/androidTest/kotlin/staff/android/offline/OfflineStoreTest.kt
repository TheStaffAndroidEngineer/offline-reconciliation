package staff.android.offline

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room3.useWriterConnection
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: OfflineDatabase
    private lateinit var api: FakeApi
    private lateinit var store: OfflineStore
    private lateinit var ticket: SessionTicket
    private val name = "contract-${System.nanoTime()}.db"

    @Before fun setup() = runBlocking {
        database = openOffline(context, name)
        api = FakeApi()
        store = OfflineStore(database, api) { 100 }
        ticket = store.signIn("A")
        store.refresh(ticket)
    }
    @After fun cleanup() { database.close(); context.deleteDatabase(name) }
    private suspend fun view() = checkNotNull(database.notes().view("A"))

    @Test fun staleRefreshPreservesAcceptedLocalIntent() = runBlocking {
        store.save(ticket, "Check lens mount")
        store.refresh(ticket)
        assertEquals("Check lens mount", view().visibleNote)
        assertEquals("Compare lenses", view().serverNote)
        assertEquals(1L, view().pendingRevision)
        store.sync(ticket)
        assertNull(view().pendingRevision)
        assertEquals(6L, view().serverRevision)
        assertEquals("Check lens mount", view().visibleNote)
    }

    @Test fun equalTextIsStillAnExplicitPendingOperation() = runBlocking {
        store.save(ticket, "Compare lenses")
        assertEquals(1L, view().pendingRevision)
        store.sync(ticket)
        assertEquals(6L, view().serverRevision)
        assertNull(view().pendingRevision)
    }

    @Test fun pendingSurvivesDatabaseReopenWithoutNetwork() = runBlocking {
        store.save(ticket, "Check lens mount")
        database.close()
        database = openOffline(context, name)
        store = OfflineStore(database, api)
        ticket = store.signIn("A")
        assertEquals("Check lens mount", view().visibleNote)
        assertEquals(1L, view().pendingRevision)
        assertEquals(5L, view().serverRevision)
    }

    @Test fun failedNetworkRetainsPendingAndLastObservation() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.putFailure = IOException("offline")
        try { store.sync(ticket); fail() } catch (_: IOException) { }
        assertEquals(1L, view().pendingRevision)
        assertEquals(5L, view().serverRevision)
    }

    @Test fun oldAcknowledgementDoesNotClearNewerSave() = runBlocking {
        store.save(ticket, "Check lens mount")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforePut = { entered.complete(Unit); release.await() }
        val sending = launch { store.sync(ticket) }
        entered.await()
        store.save(ticket, "Ask about warranty")
        release.complete(Unit); sending.join()
        assertEquals("Ask about warranty", view().visibleNote)
        assertEquals(2L, view().pendingRevision)
        assertEquals(6L, database.notes().pending("A")!!.baseRevision)
        assertEquals("Check lens mount", view().serverNote)
        store.sync(ticket)
        assertEquals(7L, view().serverRevision)
        assertEquals("Ask about warranty", api.remote.note)
        assertNull(view().pendingRevision)
    }

    @Test fun delayedReadCannotRegressNewerAcknowledgement() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeFetch = { entered.complete(Unit); release.await() }
        val reading = launch { store.refresh(ticket) }
        entered.await()
        store.save(ticket, "Check lens mount"); store.sync(ticket)
        release.complete(Unit); reading.join()
        assertEquals(6L, view().serverRevision)
        assertEquals("Check lens mount", view().visibleNote)
    }

    @Test fun acknowledgementCannotRegressAStillNewerRemoteObservation() = runBlocking {
        store.save(ticket, "Check lens mount")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.afterPut = { entered.complete(Unit); release.await() }
        val sending = launch { store.sync(ticket) }
        entered.await()
        store.save(ticket, "Ask about warranty")
        api.remote = RemoteNote("Check shutter count", 7)
        store.refresh(ticket)
        release.complete(Unit); sending.join()
        assertEquals(7L, view().serverRevision)
        assertEquals("Check shutter count", view().serverNote)
        assertEquals("Ask about warranty", view().visibleNote)
        assertEquals(6L, database.notes().pending("A")!!.baseRevision)
        store.sync(ticket)
        assertTrue(view().conflict)
    }

    @Test fun conflictKeepMineCreatesConditionalFollowup() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.remote = RemoteNote("Check shutter count", 6)
        store.sync(ticket)
        assertTrue(view().conflict)
        assertEquals("Check lens mount", view().visibleNote)
        assertEquals("Check shutter count", view().serverNote)
        store.keepMine(ticket, 6, 1)
        assertEquals(2L, view().pendingRevision)
        assertFalse(view().conflict)
        assertEquals(6L, database.notes().pending("A")!!.baseRevision)
        store.sync(ticket)
        assertEquals(RemoteNote("Check lens mount", 7), api.remote)
        assertNull(view().pendingRevision)
    }

    @Test fun useServerDiscardsOnlyTheExplicitlyConflictedEdit() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.remote = RemoteNote("Check shutter count", 6)
        store.sync(ticket); store.useServer(ticket, 6, 1)
        assertEquals("Check shutter count", view().visibleNote)
        assertNull(view().pendingRevision)
    }

    @Test fun failedEnqueueRollsBackCounterAndVisibleState() = runBlocking {
        database.useWriterConnection { connection ->
            connection.usePrepared("""CREATE TRIGGER reject_pending BEFORE INSERT ON pending_edits
                BEGIN SELECT RAISE(ABORT, 'injected enqueue failure'); END""") { it.step() }
        }
        try { store.save(ticket, "Check lens mount"); fail() } catch (_: android.database.SQLException) { }
        catch (failure: Exception) { assertTrue(failure.message.orEmpty().contains("injected enqueue failure")) }
        assertEquals(0L, database.notes().cached("A")!!.localCounter)
        assertNull(view().pendingRevision)
        assertEquals("Compare lenses", view().visibleNote)
    }

    @Test fun lateWriteCannotResurrectPurgedAccountOrLeakIntoB() = runBlocking {
        store.save(ticket, "Check lens mount")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.afterPut = { entered.complete(Unit); release.await() }
        val old = ticket
        val sending = async { runCatching { store.sync(old) }.exceptionOrNull() }
        entered.await()
        store.signOut(ticket)
        ticket = store.signIn("B"); store.refresh(ticket)
        release.complete(Unit)
        assertTrue(sending.await() is StaleSession)
        assertNull(database.notes().view("A"))
        assertNull(database.notes().pending("A"))
        assertEquals("Account B private note", database.notes().view("B")!!.visibleNote)
    }

    @Test fun sameAccountReloginStillRejectsOldEpoch() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeFetch = { entered.complete(Unit); release.await() }
        val old = ticket
        val reading = async { runCatching { store.refresh(old) }.exceptionOrNull() }
        entered.await(); store.signOut(ticket); ticket = store.signIn("A")
        release.complete(Unit)
        assertTrue(reading.await() is StaleSession)
        assertNotEquals(old.epoch, ticket.epoch)
        assertNull(database.notes().view("A"))
    }

    @Test fun cancellationDuringNetworkKeepsDurableWork() = runBlocking {
        store.save(ticket, "Check lens mount")
        val entered = CompletableDeferred<Unit>()
        api.beforePut = { entered.complete(Unit); awaitCancellation() }
        val sending = launch { store.sync(ticket) }
        entered.await(); sending.cancelAndJoin()
        assertEquals(1L, view().pendingRevision)
        assertEquals(5L, view().serverRevision)
    }

    @Test fun lostAcceptedReplyRemainsPendingThenBecomesExplicitConflict() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.afterPut = { throw IOException("accepted reply lost") }
        try { store.sync(ticket); fail() } catch (_: IOException) { }
        assertEquals(6L, api.remote.revision)
        assertEquals(1L, view().pendingRevision)
        api.afterPut = {}
        store.sync(ticket)
        assertTrue(view().conflict)
        assertEquals("Check lens mount", view().serverNote)
        assertEquals("Check lens mount", view().visibleNote)
        assertNotNull(view().pendingRevision)
    }

    @Test fun changedConflictMustBeReviewedBeforeChoosing() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.remote = RemoteNote("Check shutter count", 6)
        store.sync(ticket)
        api.remote = RemoteNote("Newest unseen change", 7)
        store.refresh(ticket)
        try { store.keepMine(ticket, 6, 1); fail() } catch (_: IllegalStateException) { }
        try { store.useServer(ticket, 6, 1); fail() } catch (_: IllegalStateException) { }
        assertTrue(view().conflict)
        assertEquals("Check lens mount", view().visibleNote)
        store.save(ticket, "Ask about warranty")
        try { store.useServer(ticket, 7, 1); fail() } catch (_: IllegalStateException) { }
        assertEquals(2L, view().pendingRevision)
        store.keepMine(ticket, 7, 2)
        assertEquals(7L, database.notes().pending("A")!!.baseRevision)
    }

    @Test fun oldLogoutCannotDeleteANewAccountSession() = runBlocking {
        val old = ticket
        store.signOut(old)
        ticket = store.signIn("B"); store.refresh(ticket)
        try { store.signOut(old); fail() } catch (_: StaleSession) { }
        assertEquals(ticket, store.session.value)
        assertEquals("Account B private note", database.notes().view("B")!!.visibleNote)
    }

    @Test fun failedPurgeKeepsSessionAndPendingRows() = runBlocking {
        store.save(ticket, "Check lens mount")
        database.useWriterConnection { connection ->
            connection.usePrepared("""CREATE TRIGGER reject_purge BEFORE DELETE ON cached_notes
                BEGIN SELECT RAISE(ABORT, 'injected purge failure'); END""") { it.step() }
        }
        try { store.signOut(ticket); fail() } catch (failure: Exception) {
            assertTrue(failure.message.orEmpty().contains("injected purge failure"))
        }
        assertEquals(ticket, store.session.value)
        assertEquals("Check lens mount", view().visibleNote)
        assertEquals(1L, view().pendingRevision)
    }

    @Test fun invalidOlderConflictReplyDoesNotAlterStoredState() = runBlocking {
        store.save(ticket, "Check lens mount")
        api.forced = WriteReply.Conflict(RemoteNote("older", 4))
        try { store.sync(ticket); fail() } catch (_: IllegalStateException) { }
        assertEquals(5L, view().serverRevision)
        assertFalse(view().conflict)
        assertEquals(1L, view().pendingRevision)
    }
}

private class FakeApi : NoteApi {
    var remote = RemoteNote("Compare lenses", 5)
    var beforeFetch: suspend () -> Unit = {}
    var beforePut: suspend () -> Unit = {}
    var afterPut: suspend () -> Unit = {}
    var putFailure: Exception? = null
    var forced: WriteReply? = null
    override suspend fun fetch(accountId: String): RemoteNote {
        val captured = if (accountId == "B") RemoteNote("Account B private note", 5) else remote
        beforeFetch()
        return captured
    }
    override suspend fun put(accountId: String, baseRevision: Long, note: String): WriteReply {
        beforePut(); putFailure?.let { throw it }; forced?.let { return it }
        if (baseRevision != remote.revision) return WriteReply.Conflict(remote)
        remote = RemoteNote(note, remote.revision + 1)
        val result = WriteReply.Accepted(remote)
        afterPut()
        return result
    }
}
