package staff.android.offline

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionTicket(val accountId: String, val epoch: Long)
class StaleSession : IllegalStateException("Session changed")

class OfflineStore(
    private val database: OfflineDatabase,
    private val api: NoteApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val guard = Mutex()
    private val drain = Mutex()
    private var epoch = 0L
    private val mutableSession = MutableStateFlow<SessionTicket?>(null)
    val session = mutableSession.asStateFlow()
    private val dao = database.notes()

    suspend fun signIn(accountId: String): SessionTicket = guard.withLock {
        require(accountId in setOf("A", "B"))
        check(mutableSession.value == null) { "Sign out before changing accounts" }
        SessionTicket(accountId, ++epoch).also { mutableSession.value = it }
    }

    suspend fun signOut(ticket: SessionTicket) = guard.withLock {
        requireCurrent(ticket)
        val old = ticket
        // Publication is blocked by guard until deletion commits. A failed deletion keeps the session.
        database.withWriteTransaction { dao.purge(old.accountId) }
        mutableSession.value = null
        epoch++
    }

    private fun requireCurrent(ticket: SessionTicket) {
        if (mutableSession.value != ticket) throw StaleSession()
    }

    private suspend fun <T> commit(ticket: SessionTicket, block: suspend () -> T): T =
        guard.withLock {
            requireCurrent(ticket)
            database.withWriteTransaction { block() }
        }

    fun observe(ticket: SessionTicket): Flow<NoteView?> = dao.observe(ticket.accountId).map {
        guard.withLock { if (mutableSession.value == ticket) it else null }
    }

    private suspend fun acceptRemote(accountId: String, remote: RemoteNote) {
        require(remote.revision >= 0 && remote.note.length <= 2_000)
        val old = dao.cached(accountId)
        if (old != null && remote.revision < old.serverRevision) return
        check(old == null || remote.revision != old.serverRevision || remote.note == old.serverNote) {
            "One server revision described different values"
        }
        dao.cache(CachedNote(accountId, "camera", remote.note, remote.revision,
            clock(), old?.localCounter ?: 0))
    }

    suspend fun refresh(ticket: SessionTicket) {
        guard.withLock { requireCurrent(ticket) }
        val remote = api.fetch(ticket.accountId)
        commit(ticket) { acceptRemote(ticket.accountId, remote) }
    }

    suspend fun save(ticket: SessionTicket, note: String) {
        require(note.length <= 2_000)
        commit(ticket) {
            val cached = checkNotNull(dao.cached(ticket.accountId)) { "Fetch a base first" }
            val previous = dao.pending(ticket.accountId)
            val next = Math.addExact(cached.localCounter, 1)
            dao.cache(cached.copy(localCounter = next))
            dao.enqueue(PendingEdit(ticket.accountId, "camera", next, note,
                previous?.baseRevision ?: cached.serverRevision, previous?.conflict ?: false))
        }
    }

    suspend fun sync(ticket: SessionTicket) = drain.withLock {
        val sent = commit(ticket) { dao.pending(ticket.accountId) }
            ?: return@withLock
        if (sent.conflict) return@withLock
        // The network call holds no database transaction and no session guard.
        val reply = api.put(ticket.accountId, sent.baseRevision, sent.note)
        commit(ticket) {
            when (reply) {
                is WriteReply.Accepted -> {
                    check(reply.remote.revision > sent.baseRevision && reply.remote.note == sent.note)
                    acceptRemote(ticket.accountId, reply.remote)
                    val now = dao.pending(ticket.accountId)
                    if (now?.localRevision == sent.localRevision) {
                        dao.clearPending(ticket.accountId)
                    } else if (now != null) {
                        dao.enqueue(now.copy(baseRevision = reply.remote.revision, conflict = false))
                    }
                }
                is WriteReply.Conflict -> {
                    check(reply.remote.revision > sent.baseRevision)
                    acceptRemote(ticket.accountId, reply.remote)
                    dao.pending(ticket.accountId)?.let { dao.enqueue(it.copy(conflict = true)) }
                }
            }
        }
    }

    suspend fun keepMine(ticket: SessionTicket, expectedServerRevision: Long,
                         expectedLocalRevision: Long) = commit(ticket) {
        val cached = checkNotNull(dao.cached(ticket.accountId))
        val pending = checkNotNull(dao.pending(ticket.accountId))
        check(pending.conflict && pending.localRevision == expectedLocalRevision &&
            cached.serverRevision == expectedServerRevision) { "Conflict changed; review again" }
        val next = Math.addExact(cached.localCounter, 1)
        dao.cache(cached.copy(localCounter = next))
        dao.enqueue(pending.copy(localRevision = next,
            baseRevision = cached.serverRevision, conflict = false))
    }

    suspend fun useServer(ticket: SessionTicket, expectedServerRevision: Long,
                          expectedLocalRevision: Long) = commit(ticket) {
        val cached = checkNotNull(dao.cached(ticket.accountId))
        val pending = checkNotNull(dao.pending(ticket.accountId))
        check(pending.conflict && pending.localRevision == expectedLocalRevision &&
            cached.serverRevision == expectedServerRevision) { "Conflict changed; review again" }
        dao.clearPending(ticket.accountId)
    }
}
