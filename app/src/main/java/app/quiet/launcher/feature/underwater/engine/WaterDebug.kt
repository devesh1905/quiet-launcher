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
    @Volatile var shaderMode = 0
    var forceStill by mutableStateOf(false)
    @Volatile var logFeel = false
}
