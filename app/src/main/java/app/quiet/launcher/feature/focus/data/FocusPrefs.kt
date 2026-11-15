package app.quiet.launcher.feature.focus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.quiet.launcher.feature.focus.engine.Behaviour
import app.quiet.launcher.feature.focus.engine.EngineState
import app.quiet.launcher.feature.focus.engine.FocusConfig
import app.quiet.launcher.feature.focus.engine.FocusSession
import app.quiet.launcher.feature.focus.engine.ShortVideoConfig
import kotlinx.serialization.json.Json

private val Context.focusStore: DataStore<Preferences> by preferencesDataStore(name = "focus")

/** Everything in the "focus" DataStore, as one value. */
data class FocusPrefsState(
    val config: FocusConfig = FocusConfig(),
    val floatingHiddenAlways: Boolean = false,
    val precisionConsent: Boolean = false,
    val shortVideo: ShortVideoConfig = ShortVideoConfig(),
    val session: FocusSession? = null,
    /** Interruption filter in effect before our session turned DND on (0 = we did not change it). */
    val dndPrevious: Int = 0,
    val engine: EngineState = EngineState(),
)

class FocusPrefs(context: Context) {
    private val store = context.applicationContext.focusStore
    val data = store.data

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    companion object Keys {
        val REMINDER = booleanPreferencesKey("reminder_enabled")
        val FLOATING = booleanPreferencesKey("floating_bar")
        val FLOATING_HIDDEN_ALWAYS = booleanPreferencesKey("floating_hidden_always")
        val LAUNCH_DELAY = booleanPreferencesKey("launch_delay")
        val PROGRESSIVE = booleanPreferencesKey("progressive_friction")
        val EXTENSION_DELAY = booleanPreferencesKey("extension_delay")
        val BEHAVIOUR = stringPreferencesKey("behaviour")
        val BLOCK_MINUTES = intPreferencesKey("block_minutes")
        val INTENTION = booleanPreferencesKey("intention_prompt")
        val PRECISION_CONSENT = booleanPreferencesKey("precision_consent")
        val SHORT_VIDEO = booleanPreferencesKey("short_video_enabled")
        val SHORT_VIDEO_OFF = stringSetPreferencesKey("short_video_disabled_rules")
        val SESSION_START = longPreferencesKey("session_start")
        val SESSION_END = longPreferencesKey("session_end")
        val SESSION_INTENTION = stringPreferencesKey("session_intention")
        val SESSION_ALLOWED = stringSetPreferencesKey("session_allowed")
        val SESSION_DND = booleanPreferencesKey("session_dnd")
        val DND_PREVIOUS = intPreferencesKey("dnd_previous")
        val ENGINE = stringPreferencesKey("engine_state")

        private val json = Json { ignoreUnknownKeys = true }

        fun encodeEngine(state: EngineState): String = json.encodeToString(EngineState.serializer(), state)

        fun read(p: Preferences): FocusPrefsState {
            val d = FocusConfig()
            val config = FocusConfig(
                reminderEnabled = p[REMINDER] ?: d.reminderEnabled,
                floatingBar = p[FLOATING] ?: d.floatingBar,
                launchDelay = p[LAUNCH_DELAY] ?: d.launchDelay,
                progressiveFriction = p[PROGRESSIVE] ?: d.progressiveFriction,
                extensionDelay = p[EXTENSION_DELAY] ?: d.extensionDelay,
                behaviour = p[BEHAVIOUR]?.let { runCatching { Behaviour.valueOf(it) }.getOrNull() } ?: d.behaviour,
                blockMinutes = p[BLOCK_MINUTES] ?: d.blockMinutes,
                intentionPrompt = p[INTENTION] ?: d.intentionPrompt,
            )
            val end = p[SESSION_END] ?: 0L
            val session = if (end > 0) FocusSession(
                startedMs = p[SESSION_START] ?: 0L,
                endsMs = end,
                intention = p[SESSION_INTENTION] ?: "",
                allowed = p[SESSION_ALLOWED] ?: emptySet(),
                dnd = p[SESSION_DND] ?: false,
            ) else null
            val engine = p[ENGINE]?.let { runCatching { json.decodeFromString(EngineState.serializer(), it) }.getOrNull() } ?: EngineState()
            return FocusPrefsState(
                config = config,
                floatingHiddenAlways = p[FLOATING_HIDDEN_ALWAYS] ?: false,
                precisionConsent = p[PRECISION_CONSENT] ?: false,
                shortVideo = ShortVideoConfig(p[SHORT_VIDEO] ?: false, p[SHORT_VIDEO_OFF] ?: emptySet()),
                session = session,
                dndPrevious = p[DND_PREVIOUS] ?: 0,
                engine = engine,
            )
        }

        fun writeConfig(p: MutablePreferences, c: FocusConfig) {
            p[REMINDER] = c.reminderEnabled
            p[FLOATING] = c.floatingBar
            p[LAUNCH_DELAY] = c.launchDelay
            p[PROGRESSIVE] = c.progressiveFriction
            p[EXTENSION_DELAY] = c.extensionDelay
            p[BEHAVIOUR] = c.behaviour.name
            p[BLOCK_MINUTES] = c.blockMinutes
            p[INTENTION] = c.intentionPrompt
        }

        fun writeSession(p: MutablePreferences, s: FocusSession?) {
            if (s == null) {
                p.remove(SESSION_START); p.remove(SESSION_END); p.remove(SESSION_INTENTION)
                p.remove(SESSION_ALLOWED); p.remove(SESSION_DND)
            } else {
                p[SESSION_START] = s.startedMs
                p[SESSION_END] = s.endsMs
                p[SESSION_INTENTION] = s.intention
                p[SESSION_ALLOWED] = s.allowed
                p[SESSION_DND] = s.dnd
            }
        }
    }
}
