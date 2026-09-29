package com.kaiharimoto.neue.kit

import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kaiharimoto.mastertool.core.motion.Tilt
import com.kaiharimoto.mastertool.core.motion.TiltFilter
import kotlin.math.abs

/**
 * Gravity, from `TYPE_GRAVITY` where the phone has one (fused, steady) and the
 * accelerometer where it does not, turned into the screen's tilt by `TiltFilter`.
 * No permission is asked for either. It listens only while the activity is resumed:
 * a sensor left on behind the home screen is a battery drained for nothing.
 */
@Composable
actual fun rememberDeviceTilt(on: Boolean): State<Tilt?> {
    val context = LocalContext.current
    val view = LocalView.current
    val state = remember { mutableStateOf<Tilt?>(null) }
    DisposableEffect(on, context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!on || manager == null || sensor == null) {
            state.value = null
            return@DisposableEffect onDispose { }
        }
        val filter = TiltFilter()
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val dt = if (last == 0L) 0f else (event.timestamp - last) / 1_000_000_000f
                last = event.timestamp
                val turns = view.display?.rotation ?: 0
                val next = filter.feed(event.values[0], event.values[1], event.values[2], turns, dt)
                // A light that has settled does not redraw every card on the screen.
                val shown = state.value
                if (shown == null || abs(shown.x - next.x) > STEP || abs(shown.y - next.y) > STEP) state.value = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var listening = false
        fun start() {
            if (listening) return
            listening = true
            last = 0L
            filter.reset()
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        fun stop() {
            if (!listening) return
            listening = false
            manager.unregisterListener(listener)
            state.value = null
        }
        val lifecycle = context.activity()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        if (lifecycle == null) start() else lifecycle.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            stop()
        }
    }
    return state
}

/** Below this much movement the light is left where it is. */
private const val STEP = 0.004f

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
