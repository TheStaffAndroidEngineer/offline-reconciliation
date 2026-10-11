package staff.android.offline

import android.content.Context
import androidx.room3.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_notes", primaryKeys = ["accountId", "productId"])
data class CachedNote(
    val accountId: String, val productId: String,
    val serverNote: String, val serverRevision: Long,
    val checkedAtMillis: Long, val localCounter: Long = 0,
)

@Entity(tableName = "pending_edits", primaryKeys = ["accountId", "productId"],
    foreignKeys = [ForeignKey(entity = CachedNote::class,
        parentColumns = ["accountId", "productId"], childColumns = ["accountId", "productId"],
        onDelete = ForeignKey.CASCADE)])
data class PendingEdit(
    val accountId: String, val productId: String,
    val localRevision: Long, val note: String, val baseRevision: Long,
    val conflict: Boolean = false,
)

data class NoteView(
    val serverNote: String, val serverRevision: Long,
    val visibleNote: String, val pendingRevision: Long?,
    val conflict: Boolean, val checkedAtMillis: Long,
)

const val NOTE_VIEW = """SELECT c.serverNote, c.serverRevision,
    COALESCE(p.note, c.serverNote) AS visibleNote,
    p.localRevision AS pendingRevision,
    COALESCE(p.`conflict`, 0) AS `conflict`, c.checkedAtMillis
    FROM cached_notes c LEFT JOIN pending_edits p
    ON c.accountId = p.accountId AND c.productId = p.productId
    WHERE c.accountId = :accountId AND c.productId = 'camera'"""

@Dao
interface OfflineDao {
    @Query(NOTE_VIEW) fun observe(accountId: String): Flow<NoteView?>
    @Query(NOTE_VIEW) suspend fun view(accountId: String): NoteView?
    @Query("""SELECT * FROM cached_notes
        WHERE accountId = :accountId AND productId = 'camera'""")
    suspend fun cached(accountId: String): CachedNote?
    @Query("""SELECT * FROM pending_edits
        WHERE accountId = :accountId AND productId = 'camera'""")
    suspend fun pending(accountId: String): PendingEdit?
    @Upsert suspend fun cache(value: CachedNote)
    @Upsert suspend fun enqueue(value: PendingEdit)
    @Query("""DELETE FROM pending_edits
        WHERE accountId = :accountId AND productId = 'camera'""")
    suspend fun clearPending(accountId: String)
    @Query("DELETE FROM cached_notes WHERE accountId = :accountId")
    suspend fun purge(accountId: String)
}

@Database(entities = [CachedNote::class, PendingEdit::class], version = 1, exportSchema = true)
abstract class OfflineDatabase : RoomDatabase() { abstract fun notes(): OfflineDao }

fun openOffline(context: Context, name: String = "offline.db"): OfflineDatabase =
    Room.databaseBuilder<OfflineDatabase>(context, name)
        .setDriver(BundledSQLiteDriver()).build()
