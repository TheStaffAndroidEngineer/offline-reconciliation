package staff.android.offline

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ContractFailure(message: String) : IOException(message)

@Serializable
data class RemoteNote(val note: String, val revision: Long)

@Serializable
data class NoteWrite(val note: String)

sealed interface WriteReply {
    data class Accepted(val remote: RemoteNote) : WriteReply
    data class Conflict(val remote: RemoteNote) : WriteReply
}

interface NoteApi {
    suspend fun fetch(accountId: String): RemoteNote
    suspend fun put(accountId: String, baseRevision: Long, note: String): WriteReply
}

// These public demo credentials identify fixture accounts; they are not authentication guidance.
class HttpNoteApi(
    baseUrl: String,
    private val transport: HttpTransport = HttpTransport(),
) : NoteApi {
    private val endpoint = baseUrl.trimEnd('/') + "/note/camera"
    private val json = Json { ignoreUnknownKeys = true }

    private fun request(accountId: String): Request.Builder {
        require(accountId in setOf("A", "B"))
        return Request.Builder().url(endpoint)
            .header("Authorization", "Bearer demo-$accountId")
            .header("Cache-Control", "no-store")
    }

    private fun decode(reply: HttpReply): RemoteNote {
        if (reply.headers["Content-Type"]?.substringBefore(';') != "application/json") {
            throw ContractFailure("Expected application/json")
        }
        val value = try {
            json.decodeFromString<RemoteNote>(reply.body.decodeToString(throwOnInvalidSequence = true))
        } catch (failure: Exception) {
            throw ContractFailure("Invalid note representation")
        }
        if (value.revision < 0 || value.note.length > 2_000 ||
            reply.headers["ETag"] != "\"${value.revision}\"") {
            throw ContractFailure("Invalid revision, text bound, or strong ETag")
        }
        return value
    }

    override suspend fun fetch(accountId: String): RemoteNote {
        val reply = transport.exchange(request(accountId).get().build())
        if (reply.code != 200) throw ContractFailure("Read HTTP ${reply.code}")
        return decode(reply)
    }

    override suspend fun put(accountId: String, baseRevision: Long, note: String): WriteReply {
        require(baseRevision >= 0 && note.length <= 2_000)
        val body = json.encodeToString(NoteWrite(note))
            .toRequestBody("application/json".toMediaType())
        val reply = transport.exchange(request(accountId)
            .header("If-Match", "\"$baseRevision\"").put(body).build())
        return when (reply.code) {
            200 -> WriteReply.Accepted(decode(reply))
            412 -> WriteReply.Conflict(decode(reply))
            else -> throw ContractFailure("Write HTTP ${reply.code}")
        }
    }
}
