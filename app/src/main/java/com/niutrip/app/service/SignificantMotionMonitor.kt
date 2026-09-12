package com.niutrip.app.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class SignificantMotionMonitor(context: Context) {
    private val sensorManager = context.getSystemService(SensorManager::class.java)

    fun events(): Flow<Unit> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sensor == null) {
            close()
            return@callbackFlow
        }

        lateinit var listener: TriggerEventListener
        fun register() {
            sensorManager.requestTriggerSensor(listener, sensor)
        }
        listener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                if (trySend(Unit).isSuccess) register()
            }
        }
        register()
        awaitClose { sensorManager.cancelTriggerSensor(listener, sensor) }
    }
}
