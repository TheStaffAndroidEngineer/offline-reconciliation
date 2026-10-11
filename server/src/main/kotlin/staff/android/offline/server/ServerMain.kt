package staff.android.offline.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import staff.android.offline.NoteWrite
import staff.android.offline.RemoteNote

/** Loopback-only synthetic backend. State is reset on restart; it is not production storage. */
class NoteServer(port: Int = 8788) : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val executor = Executors.newCachedThreadPool()
    private val lock = Any()
    private val json = Json
    private val notes = mutableMapOf("A" to RemoteNote("Compare lenses", 5),
        "B" to RemoteNote("Account B private note", 5))
    private val journal = mutableListOf<String>()
    private val unavailable = AtomicBoolean(false)
    private val holdNext = AtomicBoolean(false)
    private val loseNext = AtomicBoolean(false)
    @Volatile private var held: CountDownLatch? = null
    val port: Int get() = http.address.port

    init {
        http.executor = executor
        http.createContext("/note/camera") { exchange -> handle(exchange) }
        http.createContext("/control") { exchange -> control(exchange) }
        http.start()
    }

    private fun reply(exchange: HttpExchange, code: Int, body: String, remote: RemoteNote? = null) {
        exchange.responseHeaders.set("Content-Type", "application/json")
        if (code == 401) exchange.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"offline-fixture\"")
        if (code == 405) exchange.responseHeaders.set("Allow", if (exchange.requestURI.path.startsWith("/control")) "POST" else "GET, PUT")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        if (remote != null) exchange.responseHeaders.set("ETag", "\"${remote.revision}\"")
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val account = when (exchange.requestHeaders.getFirst("Authorization")) {
                "Bearer demo-A" -> "A"
                "Bearer demo-B" -> "B"
                else -> { reply(exchange, 401, "{}"); return }
            }
            if (exchange.requestURI.path != "/note/camera") {
                reply(exchange, 404, "{}"); return
            }
            if (unavailable.get()) { reply(exchange, 503, "{}"); return }
            when (exchange.requestMethod) {
                "GET" -> {
                    val remote = synchronized(lock) {
                        notes.getValue(account).also { journal += "GET $account -> ${it.revision}" }
                    }
                    reply(exchange, 200, json.encodeToString(remote), remote)
                }
                "PUT" -> {
                    // JSON may escape each allowed UTF-16 code unit as six ASCII bytes.
                    val bytes = exchange.requestBody.use { it.readNBytes(16 * 1024 + 1) }
                    if (bytes.size > 16 * 1024) { reply(exchange, 413, "{}"); return }
                    val write = try { json.decodeFromString<NoteWrite>(bytes.decodeToString(throwOnInvalidSequence = true)) }
                        catch (_: Exception) { reply(exchange, 400, "{}"); return }
                    if (write.note.length > 2000) { reply(exchange, 422, "{}"); return }
                    val tag = exchange.requestHeaders.getFirst("If-Match")
                    if (tag == null) { reply(exchange, 428, "{}"); return }
                    // This endpoint requires one numeric validator; '*' and lists are outside its contract.
                    if (!Regex("(?:W/)?\\\"[0-9]+\\\"").matches(tag)) {
                        reply(exchange, 400, "{}"); return
                    }
                    if (holdNext.compareAndSet(true, false)) {
                        val gate = CountDownLatch(1)
                        held = gate
                        synchronized(lock) { journal += "HOLD $account $tag" }
                        if (!gate.await(30, TimeUnit.SECONDS)) {
                            held = null
                            reply(exchange, 503, "{}"); return
                        }
                        held = null
                    }
                    val (code, remote) = synchronized(lock) {
                        val current = notes.getValue(account)
                        if (tag != "\"${current.revision}\"") {
                            journal += "PUT $account $tag -> 412 current=${current.revision}"
                            412 to current
                        } else {
                            val updated = RemoteNote(write.note, Math.addExact(current.revision, 1))
                            notes[account] = updated
                            journal += "PUT $account $tag -> 200 revision=${updated.revision}"
                            200 to updated
                        }
                    }
                    if (code == 200 && loseNext.compareAndSet(true, false)) {
                        synchronized(lock) { journal += "LOST_REPLY $account revision=${remote.revision}" }
                        exchange.close()
                    } else reply(exchange, code, json.encodeToString(remote), remote)
                }
                else -> reply(exchange, 405, "{}")
            }
        } catch (_: Exception) { exchange.close() }
    }

    private fun control(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST" && exchange.requestURI.path != "/control/journal") {
            reply(exchange, 405, "{}"); return
        }
        when (exchange.requestURI.path) {
            "/control/reset" -> synchronized(lock) {
                notes["A"] = RemoteNote("Compare lenses", 5)
                notes["B"] = RemoteNote("Account B private note", 5)
                journal.clear(); unavailable.set(false); holdNext.set(false); loseNext.set(false)
                held?.countDown()
            }
            "/control/offline" -> unavailable.set(true)
            "/control/online" -> unavailable.set(false)
            "/control/hold" -> holdNext.set(true)
            "/control/release" -> held?.countDown()
            "/control/lose-reply" -> loseNext.set(true)
            "/control/other-device" -> synchronized(lock) {
                val current = notes.getValue("A")
                notes["A"] = RemoteNote("Check shutter count", Math.addExact(current.revision, 1))
                journal += "OTHER_DEVICE A -> ${notes.getValue("A").revision}"
            }
            "/control/journal" -> {
                val body = synchronized(lock) { json.encodeToString(journal.toList()) }
                reply(exchange, 200, body); return
            }
            else -> { reply(exchange, 404, "{}"); return }
        }
        reply(exchange, 200, "{}")
    }

    override fun close() { held?.countDown(); http.stop(0); executor.shutdownNow() }
}

fun main(args: Array<String>) {
    val server = NoteServer(args.firstOrNull()?.toInt() ?: 8788)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("Offline fixture on http://127.0.0.1:${server.port}; controls are local-only")
    CountDownLatch(1).await()
}
