package com.orin.app.voice

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/** Wave hand over proximity sensor to interrupt an active reply. */
class WaveStop(context: Context, private val onWave: () -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var watching = false
    private var wasNear: Boolean? = null
    private val main = Handler(Looper.getMainLooper())

    fun watch(on: Boolean) {
        main.post { set(on) }
    }

    private fun set(on: Boolean) {
        if (on == watching || proximity == null) return
        watching = on
        wasNear = null
        if (on) {
            sensors?.registerListener(this, proximity, SensorManager.SENSOR_DELAY_UI)
        } else {
            sensors?.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val near = event.values[0] < (proximity?.maximumRange ?: 5f).coerceAtMost(NEAR_CM)
        if (near && wasNear == false) onWave()
        wasNear = near
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NEAR_CM = 5f
    }
}
