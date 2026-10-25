package com.example.location_producer.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.location_producer.model.AccelData
import com.example.location_producer.model.BarometerData
import com.example.location_producer.model.GyroData
import com.example.location_producer.model.ImuData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SensorCollector(context: Context) : SensorEventListener {

    private val tag = "SensorCollector"

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val barometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    @Volatile
    private var currentAccel = AccelData()

    @Volatile
    private var currentGyro = GyroData()

    @Volatile
    private var currentYaw: Double = 0.0

    @Volatile
    private var currentBarometer = BarometerData()

    private val _imuState = MutableStateFlow(ImuData())
    val imuState: StateFlow<ImuData> = _imuState.asStateFlow()

    private val _barometerState = MutableStateFlow(BarometerData())
    val barometerState: StateFlow<BarometerData> = _barometerState.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    fun start() {
        Log.d(tag, "Starting sensor listeners at SENSOR_DELAY_GAME")

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(tag, "Accelerometer not available on this device")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(tag, "Gyroscope not available on this device")

        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(tag, "Rotation Vector Sensor not available on this device")

        barometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(tag, "Barometer Sensor not available on this device")
    }

    fun stop() {
        Log.d(tag, "Stopping sensor listeners")
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                currentAccel = AccelData(x = x, y = y, z = z)
                updateImuData()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                currentGyro = GyroData(x = x, y = y, z = z)
                updateImuData()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                val yawRad = orientationValues[0].toDouble()
                val yawDeg = Math.toDegrees(yawRad)
                val normalizedYaw = (yawDeg + 360.0) % 360.0
                currentYaw = normalizedYaw
                updateImuData()
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                currentBarometer = BarometerData(pressure = pressure)
                _barometerState.value = currentBarometer
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Sensor accuracy changed
    }

    private fun updateImuData() {
        val newImu = ImuData(
            accel = currentAccel,
            gyro = currentGyro,
            yaw = currentYaw
        )
        _imuState.value = newImu
    }

    fun getLatestImu(): ImuData = _imuState.value

    fun getLatestBarometer(): BarometerData = _barometerState.value
}
