package app.quiet.launcher.feature.underwater.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Test knobs for the water, set only from adb through the DUMP-protected ShellCommandReceiver
 * (`--es cmd water`), never from the UI. Defaults are the shipped behaviour.
 *
 *  - [shaderMode]: 0 normal; 1 pass-through (cost of the layer alone); 2 no glass; 3 no water (GPU profiling).
 *  - [forceStill]: behave as with reduced motion / battery saver (one settled frame per change),
 *    so that path can be tested without touching system settings.
 *  - [logFeel]: log every haptic feel the water plays (logcat tag QuietWater).
 */
internal object WaterDebug {
    /** Profiling (adb `--ei shader 16*h + m`): h bits leave out 1 the painting, 2 the veil, 4 the RenderEffect, 8 the pages. */
    private val hideState = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile var shaderMode = 0
        set(v) { field = v and 15; hideState.intValue = v shr 4 }
    val hide: Int get() = hideState.intValue
    var forceStill by mutableStateOf(false)
    @Volatile var logFeel = false
}
