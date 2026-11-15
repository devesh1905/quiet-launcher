package app.quiet.launcher.core.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** User-level metadata for one launchable activity. [id] is `AppKey.id`. */
@Entity(tableName = "app_meta")
data class AppMetaEntity(
    @PrimaryKey val id: String,
    val customLabel: String? = null,
    val hidden: Boolean = false,
    val folder: String? = null,
)

/** A pinned favourite; [position] is the 0-based order. */
@Entity(tableName = "favourite")
data class FavouriteEntity(
    @PrimaryKey val id: String,
    val position: Int,
)

@Dao
abstract class AppMetaDao {
    @Query("SELECT * FROM app_meta")
    abstract fun observeAll(): Flow<List<AppMetaEntity>>

    @Query("SELECT * FROM app_meta WHERE id = :id")
    abstract suspend fun get(id: String): AppMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(entity: AppMetaEntity)

    /** Read-modify-write of a single row inside one transaction. */
    @Transaction
    open suspend fun modify(id: String, transform: (AppMetaEntity) -> AppMetaEntity) {
        upsert(transform(get(id) ?: AppMetaEntity(id)))
    }
}

@Dao
abstract class FavouriteDao {
    @Query("SELECT * FROM favourite ORDER BY position ASC")
    abstract fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourite ORDER BY position ASC")
    abstract suspend fun getAll(): List<FavouriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(items: List<FavouriteEntity>)

    @Query("DELETE FROM favourite WHERE id = :id")
    abstract suspend fun delete(id: String)

    /** Appends [id] after the last favourite; no-op if already present. */
    @Transaction
    open suspend fun append(id: String) {
        val current = getAll()
        if (current.any { it.id == id }) return
        upsertAll(listOf(FavouriteEntity(id, (current.maxOfOrNull { it.position } ?: -1) + 1)))
    }

    /** Rewrites positions so that [orderedIds] become 0..n-1. */
    @Transaction
    open suspend fun renumber(orderedIds: List<String>) {
        upsertAll(orderedIds.mapIndexed { index, id -> FavouriteEntity(id, index) })
    }
}

/**
 * The app-wide Room database. Other features that need persistence should add their entities and
 * DAOs here (bump [version] and add a migration) and obtain it via [QuietDatabase.get].
 */
@Database(entities = [AppMetaEntity::class, FavouriteEntity::class], version = 1, exportSchema = true)
abstract class QuietDatabase : RoomDatabase() {
    abstract fun appMetaDao(): AppMetaDao
    abstract fun favouriteDao(): FavouriteDao

    companion object {
        @Volatile private var instance: QuietDatabase? = null

        /** Process-wide singleton accessor. */
        fun get(context: Context): QuietDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, QuietDatabase::class.java, "quiet.db")
                .build()
                .also { instance = it }
        }
    }
}
