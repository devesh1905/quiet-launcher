package app.quiet.launcher.feature.gesture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import app.quiet.launcher.core.model.AppEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GestureSettings(
    /** Ink dissolve on the tapped name when an app opens. */
    val ink: Boolean = true,
    /** Draw a letter on Home to open an app. */
    val letters: Boolean = true,
    /** Hold the Home clock to feel the time as vibration (otherwise the hold opens quick actions). */
    val hapticClock: Boolean = true,
    /** Letter -> target: [CAMERA], a package name, or an app key id. Letters missing here open nothing. */
    val map: Map<Char, String> = DEFAULT_MAP,
)

const val CAMERA = "@camera"
val DEFAULT_MAP = mapOf('C' to CAMERA, 'M' to "com.apple.android.music", 'W' to "com.whatsapp")

/**
 * Small prefs of its own (SharedPreferences, read once on first use), so the shared settings store
 * stays untouched. Read lazily: nothing here runs at app start-up.
 */
class GesturePrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("gestures", Context.MODE_PRIVATE)
    // Defaults first; the file is read off the main thread so Home's first frame never waits on it.
    private val _state = MutableStateFlow(GestureSettings())
    val state: StateFlow<GestureSettings> = _state

    init { kotlin.concurrent.thread(name = "gesture-prefs") { _state.compareAndSet(GestureSettings(), read()) } }

    private fun read(): GestureSettings {
        val map = Unistroke.LETTERS.mapNotNull { l ->
            val v = if (prefs.contains("map_$l")) prefs.getString("map_$l", "").orEmpty() else DEFAULT_MAP[l].orEmpty()
            if (v.isEmpty()) null else l to v
        }.toMap()
        return GestureSettings(
            ink = prefs.getBoolean("ink", true),
            letters = prefs.getBoolean("letters", true),
            hapticClock = prefs.getBoolean("hapticClock", true),
            map = map,
        )
    }

    fun setInk(v: Boolean) { prefs.edit().putBoolean("ink", v).apply(); _state.value = read() }
    fun setLetters(v: Boolean) { prefs.edit().putBoolean("letters", v).apply(); _state.value = read() }
    fun setHapticClock(v: Boolean) { prefs.edit().putBoolean("hapticClock", v).apply(); _state.value = read() }

    /** [target] null clears the letter. */
    fun setLetter(letter: Char, target: String?) {
        prefs.edit().putString("map_$letter", target.orEmpty()).apply(); _state.value = read()
    }

    companion object {
        @Volatile private var instance: GesturePrefs? = null
        fun get(context: Context): GesturePrefs =
            instance ?: synchronized(this) { instance ?: GesturePrefs(context).also { instance = it } }
    }
}

object GestureTargets {
    /** The default camera's package (still-image camera, then image capture), or null. */
    fun cameraPackage(ctx: Context): String? {
        val pm = ctx.packageManager
        for (action in listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE)) {
            val intent = Intent(action)
            val def = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            // "android" is the chooser: no default set, take the first camera instead.
            if (def != null && def != "android") return def
            pm.queryIntentActivities(intent, 0).firstOrNull()?.activityInfo?.packageName?.let { return it }
        }
        return null
    }

    /** The installed app a target opens, or null (the letter is then ignored). */
    fun resolve(ctx: Context, target: String, apps: List<AppEntry>, cameraPkg: () -> String?): AppEntry? {
        val pkg = when {
            target == CAMERA -> cameraPkg() ?: return null
            target.contains('/') -> return apps.firstOrNull { it.key.id == target }
            else -> target
        }
        return apps.firstOrNull { it.key.packageName == pkg && !it.isWorkProfile } ?: apps.firstOrNull { it.key.packageName == pkg }
    }

    fun label(ctx: Context, target: String?, apps: List<AppEntry>): String = when {
        target == null -> "Nothing"
        else -> resolve(ctx, target, apps) { cameraPackage(ctx) }?.label
            ?: if (target == CAMERA) "Camera" else "Not installed"
    }
}
