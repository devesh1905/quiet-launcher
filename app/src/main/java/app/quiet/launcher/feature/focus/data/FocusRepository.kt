package app.quiet.launcher.feature.focus.data

import android.content.Context
import app.quiet.launcher.core.data.UsageRepository
import app.quiet.launcher.feature.focus.engine.AppBlock
import app.quiet.launcher.feature.focus.engine.AppRule
import app.quiet.launcher.feature.focus.engine.BlockSource
import app.quiet.launcher.feature.focus.engine.EngineState
import app.quiet.launcher.feature.focus.engine.FocusConfig
import app.quiet.launcher.feature.focus.engine.FocusSession
import app.quiet.launcher.feature.focus.engine.FocusSnapshot
import app.quiet.launcher.feature.focus.engine.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Focus storage: Room (rules, blocks, schedules, intentions) plus the "focus" DataStore
 * (global switches, consent, focus session, persisted engine state). Room and DataStore do
 * their own IO off the main thread; every mutator is a suspend function.
 */
class FocusRepository(context: Context, private val scope: CoroutineScope, usage: UsageRepository) {
    private val app = context.applicationContext
    private val dao = FocusDatabase.get(app).dao()
    private val prefsStore = FocusPrefs(app)

    /** Packages watched by default; filled by the runtime from the installed apps. */
    val defaultWatched = MutableStateFlow<Set<String>>(emptySet())
    /** Never blocked by a focus session. */
    val alwaysAllowed = MutableStateFlow<Set<String>>(setOf(app.packageName))

    private val _loaded = MutableStateFlow(false)
    /** True once DataStore and Room have both emitted. Before that, the snapshot is defaults. */
    val loaded: StateFlow<Boolean> = _loaded

    val prefs: StateFlow<FocusPrefsState> = prefsStore.data.map { FocusPrefs.read(it) }
        .stateIn(scope, SharingStarted.Eagerly, FocusPrefsState())

    val rules: StateFlow<Map<String, AppRule>> = dao.observeRules()
        .map { list -> list.associate { it.pkg to it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val blocks: StateFlow<Map<String, AppBlock>> = dao.observeBlocks()
        .map { list -> list.associate { it.pkg to it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val schedules: StateFlow<List<Schedule>> = dao.observeSchedules()
        .map { list -> list.map { it.toModel() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val usedToday: Flow<Map<String, Long>> =
        usage.today.map { t -> t.perApp.associate { it.packageName to it.millis } }.distinctUntilChanged()

    private data class Extras(val used: Map<String, Long>, val defaults: Set<String>, val allowed: Set<String>)

    private val core = combine(prefsStore.data.map { FocusPrefs.read(it) }, dao.observeRules(), dao.observeBlocks(), dao.observeSchedules()) { p, r, b, s ->
        FocusSnapshot(
            config = p.config,
            rules = r.associate { it.pkg to it.toModel() },
            blocks = b.associate { it.pkg to it.toModel() },
            schedules = s.map { it.toModel() },
            session = p.session,
            shortVideo = p.shortVideo,
        )
    }.onEach { _loaded.value = true }

    val snapshot: StateFlow<FocusSnapshot> = combine(core, combine(usedToday, defaultWatched, alwaysAllowed, ::Extras)) { c, e ->
        c.copy(usedTodayMs = e.used, defaultWatched = e.defaults, alwaysAllowed = e.allowed)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, FocusSnapshot())

    /** One read straight from disk (the [prefs] StateFlow starts with defaults). */
    suspend fun readPrefsOnce(): FocusPrefsState = FocusPrefs.read(prefsStore.data.first())

    fun intentions(sinceMs: Long): Flow<List<IntentionEntity>> = dao.observeIntentions(sinceMs)

    // ---- Mutators ----

    suspend fun updateConfig(transform: (FocusConfig) -> FocusConfig) =
        prefsStore.edit { p -> FocusPrefs.writeConfig(p, transform(FocusPrefs.read(p).config)) }

    suspend fun setFloatingHiddenAlways(hidden: Boolean) = prefsStore.edit { it[FocusPrefs.FLOATING_HIDDEN_ALWAYS] = hidden }

    suspend fun setPrecisionConsent(given: Boolean) = prefsStore.edit { it[FocusPrefs.PRECISION_CONSENT] = given }

    suspend fun setShortVideoEnabled(on: Boolean) = prefsStore.edit { it[FocusPrefs.SHORT_VIDEO] = on }

    suspend fun setShortVideoRule(ruleId: String, on: Boolean) = prefsStore.edit { p ->
        val cur = p[FocusPrefs.SHORT_VIDEO_OFF] ?: emptySet()
        p[FocusPrefs.SHORT_VIDEO_OFF] = if (on) cur - ruleId else cur + ruleId
    }

    suspend fun setSession(session: FocusSession?, dndPrevious: Int? = null) = prefsStore.edit { p ->
        FocusPrefs.writeSession(p, session)
        if (dndPrevious != null) p[FocusPrefs.DND_PREVIOUS] = dndPrevious
    }

    suspend fun saveEngine(state: EngineState) = prefsStore.edit { it[FocusPrefs.ENGINE] = FocusPrefs.encodeEngine(state) }

    /** Rule for [pkg] as it applies now (stored, or the default for that package). */
    fun effectiveRule(pkg: String): AppRule = rules.value[pkg] ?: AppRule(pkg, reminder = pkg in defaultWatched.value)

    suspend fun updateRule(pkg: String, transform: (AppRule) -> AppRule) {
        val current = dao.rule(pkg)?.toModel() ?: AppRule(pkg, reminder = pkg in defaultWatched.value)
        dao.upsertRule(AppRuleEntity.from(transform(current)))
    }

    suspend fun block(pkgs: Collection<String>, untilMs: Long, source: BlockSource = BlockSource.MANUAL) {
        val now = System.currentTimeMillis()
        val existing = blocks.value
        dao.upsertBlocks(
            pkgs.distinct().map { pkg ->
                // Never shorten an existing block by adding a shorter one.
                val until = maxOf(untilMs, existing[pkg]?.untilMs ?: 0L)
                BlockEntity(pkg, until, source.name, now)
            },
        )
    }

    suspend fun unblock(pkg: String) = dao.deleteBlock(pkg)

    suspend fun saveSchedule(s: Schedule) = dao.upsertSchedule(ScheduleEntity.from(s))

    suspend fun saveSchedules(list: List<Schedule>) = dao.upsertSchedules(list.map { ScheduleEntity.from(it) })

    suspend fun deleteSchedule(id: String) = dao.deleteSchedule(id)

    /** "Turn off until tomorrow": windows starting today are skipped; it turns back on by itself. */
    suspend fun pauseScheduleUntilTomorrow(s: Schedule, zone: ZoneId = ZoneId.systemDefault()) {
        val tomorrow = LocalDate.now(zone).plusDays(1)
        saveSchedule(s.copy(pausedUntil = tomorrow, enabled = true))
    }

    suspend fun logIntention(pkg: String, intention: String, minutes: Int) {
        dao.insertIntention(IntentionEntity(pkg = pkg, atMs = System.currentTimeMillis(), intention = intention, minutes = minutes))
    }

    /** Housekeeping: expired blocks and intentions older than 60 days. */
    fun cleanUp() {
        scope.launch {
            val now = System.currentTimeMillis()
            runCatching {
                dao.deleteExpiredBlocks(now)
                dao.deleteIntentionsBefore(Instant.ofEpochMilli(now).minusSeconds(60L * 86_400).toEpochMilli())
            }
        }
    }
}
