package com.luverse.fetchy

import android.content.Context
import androidx.room.*
import org.json.JSONObject

@Entity(tableName = "records", indices = [Index("kind")])
data class RecordEntity(@PrimaryKey val id: String, val kind: String, val json: String)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE kind = :kind AND id = :id")
    fun get(kind: String, id: String): RecordEntity?
    @Query("SELECT * FROM records WHERE kind = :kind ORDER BY rowid")
    fun list(kind: String): List<RecordEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(record: RecordEntity)
    @Query("DELETE FROM records WHERE kind = :kind AND id = :id")
    fun delete(kind: String, id: String)
}

@Database(entities = [RecordEntity::class], version = 1, exportSchema = false)
abstract class FetchyDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    companion object {
        @Volatile private var instance: FetchyDatabase? = null
        fun get(context: Context): FetchyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FetchyDatabase::class.java, "fetchy.db")
                // Settings are also read by the activity; all bulk operations belong on IO.
                .allowMainThreadQueries().build().also { instance = it }
        }
    }
}

/** Synchronous API: callers must dispatch snapshots and storage work onto IO. Record keys are scoped by kind; jobs and files may share a job ID. */
class Store(context: Context) {
    private val db = FetchyDatabase.get(context)
    private val dao = db.records()
    fun get(kind: String, id: String): JSONObject? = dao.get(kind, key(kind, id))?.let { JSONObject(it.json) }
    fun list(kind: String): List<JSONObject> = dao.list(kind).map { JSONObject(it.json) }
    fun put(kind: String, value: JSONObject) {
        val id = value.getString("id")
        require(id.isNotBlank()) { "Record id is required" }
        dao.put(RecordEntity(key(kind, id), kind, value.toString()))
    }
    fun putMany(kind: String, values: List<JSONObject>) = db.runInTransaction { values.forEach { put(kind, it) } }
    fun delete(kind: String, id: String) = dao.delete(kind, key(kind, id))
    fun settings(): JSONObject = defaults().also { result ->
        get("settings", "settings")?.let { saved -> saved.keys().forEach { key -> result.put(key, saved.get(key)) } }
    }
    fun setting(key: String, value: Any) = db.runInTransaction {
        put("settings", settings().put(key, value).put("id", "settings"))
    }
    private fun key(kind: String, id: String) = "${kind.length}:$kind$id"
    private fun defaults() = JSONObject().apply {
        put("quality", 1080); put("audioFormat", "m4a"); put("videoFormat", "mp4")
        put("platformFolders", true); put("nameRule", "title_id"); put("duplicates", "rename")
        put("wifiOnly", false); put("mobileData", true); put("concurrency", 2); put("retries", 3)
        put("resume", true); put("notifications", true); put("theme", "system")
        put("rootUri", ""); put("rootName", "")
        put("audioMetadata", true); put("audioCover", true)
    }
}
