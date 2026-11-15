package app.quiet.launcher.feature.notifications

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "held_notification", indices = [Index("postedAt"), Index("key")])
data class HeldNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val packageName: String,
    val userSerial: Long,
    val title: String,
    val text: String,
    val meta: String,
    val postedAt: Long,
)

@Dao
interface HeldDao {
    @Insert
    suspend fun insert(n: HeldNotification): Long

    @Query("SELECT COUNT(*) FROM held_notification WHERE key = :key AND title = :title AND text = :text")
    suspend fun countSame(key: String, title: String, text: String): Int

    @Query("SELECT * FROM held_notification ORDER BY postedAt DESC, id DESC")
    fun observeAll(): Flow<List<HeldNotification>>

    @Query("SELECT COUNT(*) FROM held_notification")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM held_notification")
    suspend fun count(): Int

    @Query("DELETE FROM held_notification WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM held_notification")
    suspend fun deleteAll()

    @Query("DELETE FROM held_notification WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}

@Database(entities = [HeldNotification::class], version = 1, exportSchema = false)
abstract class NotificationsDatabase : RoomDatabase() {
    abstract fun held(): HeldDao

    companion object {
        @Volatile private var instance: NotificationsDatabase? = null

        fun get(context: Context): NotificationsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, NotificationsDatabase::class.java, "notifications.db",
            ).fallbackToDestructiveMigration(true).build().also { instance = it }
        }
    }
}
