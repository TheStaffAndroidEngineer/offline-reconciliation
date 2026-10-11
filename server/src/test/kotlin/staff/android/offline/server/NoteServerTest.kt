package staff.android.offline.server

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import staff.android.offline.*

class NoteServerTest {
    @Test fun conditionalWriteConflictAndAccountIsolation() = runBlocking {
        NoteServer(0).use { server ->
            val api = HttpNoteApi("http://127.0.0.1:${server.port}")
            assertEquals(RemoteNote("Compare lenses", 5), api.fetch("A"))
            assertEquals(WriteReply.Accepted(RemoteNote("Check lens mount", 6)), api.put("A", 5, "Check lens mount"))
            assertEquals(WriteReply.Conflict(RemoteNote("Check lens mount", 6)), api.put("A", 5, "stale"))
            assertEquals(RemoteNote("Account B private note", 5), api.fetch("B"))
            assertEquals(RemoteNote("Check lens mount", 6), api.fetch("A"))
        }
    }

    @Test fun maximumEscapedNoteFitsTheDeclaredWireContract() = runBlocking {
        NoteServer(0).use { server ->
            val api = HttpNoteApi("http://127.0.0.1:${server.port}")
            val text = "\u0000".repeat(2000)
            assertEquals(WriteReply.Accepted(RemoteNote(text, 6)), api.put("A", 5, text))
            assertEquals(RemoteNote(text, 6), api.fetch("A"))
        }
    }

    @Test fun simultaneousSameBaseWritesHaveExactlyOneWinner() = runBlocking {
        NoteServer(0).use { server ->
            val api = HttpNoteApi("http://127.0.0.1:${server.port}")
            val replies = awaitAll(async { api.put("A", 5, "one") }, async { api.put("A", 5, "two") })
            assertEquals(1, replies.count { it is WriteReply.Accepted })
            assertEquals(1, replies.count { it is WriteReply.Conflict })
            assertEquals(6L, api.fetch("A").revision)
        }
    }

    @Test fun missingWeakAndUnauthorizedConditionsCannotOverwrite() {
        NoteServer(0).use { server ->
            val client = OkHttpClient()
            fun put(token: String, condition: String?): Int {
                val builder = Request.Builder().url("http://127.0.0.1:${server.port}/note/camera")
                    .header("Authorization", token).put("{\"note\":\"wrong\"}".toRequestBody())
                condition?.let { builder.header("If-Match", it) }
                return client.newCall(builder.build()).execute().use { it.code }
            }
            assertEquals(401, put("Bearer invalid", "\"5\""))
            assertEquals(428, put("Bearer demo-A", null))
            assertEquals(412, put("Bearer demo-A", "W/\"5\""))
            assertEquals(400, put("Bearer demo-A", "*")) // Endpoint requires one exact numeric validator.
            assertEquals(RemoteNote("Compare lenses", 5), runBlocking { HttpNoteApi("http://127.0.0.1:${server.port}").fetch("A") })
        }
    }

    @Test fun lostAcceptedReplyCanProduceConflictOnRetry() = runBlocking {
        NoteServer(0).use { server ->
            val url = "http://127.0.0.1:${server.port}"
            OkHttpClient().newCall(Request.Builder().url("$url/control/lose-reply")
                .post(ByteArray(0).toRequestBody()).build()).execute().close()
            val api = HttpNoteApi(url)
            assertThrows(java.io.IOException::class.java) { runBlocking { api.put("A", 5, "Check lens mount") } }
            assertEquals(WriteReply.Conflict(RemoteNote("Check lens mount", 6)), api.put("A", 5, "Check lens mount"))
        }
    }
}
