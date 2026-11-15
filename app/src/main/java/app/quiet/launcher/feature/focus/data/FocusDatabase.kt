package app.quiet.launcher.feature.focus.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.quiet.launcher.feature.focus.engine.AppBlock
import app.quiet.launcher.feature.focus.engine.AppRule
import app.quiet.launcher.feature.focus.engine.Behaviour
import app.quiet.launcher.feature.focus.engine.BlockSource
import app.quiet.launcher.feature.focus.engine.Schedule
import app.quiet.launcher.feature.focus.engine.ScheduleMath
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Per-app reminder rule. Nullable columns follow the global setting. */
@Entity(tableName = "app_rule")
data class AppRuleEntity(
    @PrimaryKey val pkg: String,
    val reminder: Boolean,
    val behaviour: String? = null,
    val blockMinutes: Int? = null,
    val launchDelay: Boolean? = null,
    val extensionDelay: Boolean? = null,
    val intention: Boolean? = null,
    val budgetMinutes: Int = 0,
) {
    fun toModel() = AppRule(
        pkg, reminder, behaviour?.let { runCatching { Behaviour.valueOf(it) }.getOrNull() },
        blockMinutes, launchDelay, extensionDelay, intention, budgetMinutes,
    )

    companion object {
        fun from(r: AppRule) = AppRuleEntity(
            r.pkg, r.reminder, r.behaviour?.name, r.blockMinutes, r.launchDelay, r.extensionDelay, r.intention, r.budgetMinutes,
        )
    }
}

@Entity(tableName = "app_block")
data class BlockEntity(
    @PrimaryKey val pkg: String,
    val untilMs: Long,
    val source: String,
    val createdMs: Long,
) {
    fun toModel() = AppBlock(pkg, untilMs, runCatching { BlockSource.valueOf(source) }.getOrDefault(BlockSource.MANUAL), createdMs)
}

/** [apps] is newline-joined package names; [daysMask] bit 0 = Monday. */
@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val startMinute: Int,
    val endMinute: Int,
    val daysMask: Int,
    val apps: String,
    val enabled: Boolean,
    val pausedUntilEpochDay: Long?,
    val createdMs: Long,
) {
    fun toModel() = Schedule(
        id, name, startMinute, endMinute, ScheduleMath.maskToDays(daysMask),
        apps.split('\n').filter { it.isNotBlank() }.toSet(), enabled, pausedUntilEpochDay?.let(LocalDate::ofEpochDay), createdMs,
    )

    companion object {
        fun from(s: Schedule) = ScheduleEntity(
            s.id, s.name, s.startMinute, s.endMinute, ScheduleMath.daysToMask(s.days),
            s.apps.sorted().joinToString("\n"), s.enabled, s.pausedUntil?.toEpochDay(), s.createdMs,
        )
    }
}

/** Answers to the intention prompt, kept on the device for the usage detail. */
@Entity(tableName = "intention", indices = [Index("pkg"), Index("atMs")])
data class IntentionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val atMs: Long,
    val intention: String,
    val minutes: Int,
)

@Dao
interface FocusDao {
    @Query("SELECT * FROM app_rule")
    fun observeRules(): Flow<List<AppRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: AppRuleEntity)

    @Query("SELECT * FROM app_rule WHERE pkg = :pkg")
    suspend fun rule(pkg: String): AppRuleEntity?

    @Query("SELECT * FROM app_block")
    fun observeBlocks(): Flow<List<BlockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlocks(blocks: List<BlockEntity>)

    @Query("DELETE FROM app_block WHERE pkg = :pkg")
    suspend fun deleteBlock(pkg: String)

    @Query("DELETE FROM app_block WHERE untilMs < :nowMs")
    suspend fun deleteExpiredBlocks(nowMs: Long)

    @Query("SELECT * FROM schedule ORDER BY startMinute ASC")
    fun observeSchedules(): Flow<List<ScheduleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(schedule: ScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedules(schedules: List<ScheduleEntity>)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun deleteSchedule(id: String)

    @Insert
    suspend fun insertIntention(i: IntentionEntity)

    @Query("SELECT * FROM intention WHERE atMs >= :sinceMs ORDER BY atMs DESC")
    fun observeIntentions(sinceMs: Long): Flow<List<IntentionEntity>>

    @Query("DELETE FROM intention WHERE atMs < :beforeMs")
    suspend fun deleteIntentionsBefore(beforeMs: Long)
}

@Database(
    entities = [AppRuleEntity::class, BlockEntity::class, ScheduleEntity::class, IntentionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FocusDatabase : RoomDatabase() {
    abstract fun dao(): FocusDao

    companion object {
        @Volatile private var instance: FocusDatabase? = null

        fun get(context: Context): FocusDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, FocusDatabase::class.java, "focus.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
