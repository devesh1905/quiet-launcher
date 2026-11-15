package app.quiet.launcher.core.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Battery and minute-tick state; receivers live for the app's lifetime. */
class DeviceStateRepositoryImpl(context: Context, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) : DeviceStateRepository {

    private val app = context.applicationContext

    private val _battery = MutableStateFlow(BatteryState(0f, false))
    override val battery: StateFlow<BatteryState> = _battery

    private val _tick = MutableStateFlow(System.currentTimeMillis())
    override val minuteTick: StateFlow<Long> = _tick

    init {
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handleBattery(intent)
        }
        val sticky = ContextCompat.registerReceiver(
            app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (sticky != null) handleBattery(sticky)

        val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                _tick.value = System.currentTimeMillis()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(app, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun handleBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val fraction = if (level >= 0 && scale > 0) (level.toFloat() / scale).coerceIn(0f, 1f) else _battery.value.level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0
        _battery.value = BatteryState(fraction, charging)
    }
}
