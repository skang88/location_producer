package com.example.location_producer.model

import org.json.JSONObject

data class GpsData(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val alt: Double = 0.0,
    val accuracy: Float = 0.0f,
    val isValid: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lng", lng)
        put("alt", alt)
        put("accuracy", accuracy.toDouble())
        put("is_valid", isValid)
    }
}

data class AccelData(
    val x: Float = 0.0f,
    val y: Float = 0.0f,
    val z: Float = 0.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("z", z.toDouble())
    }
}

data class GyroData(
    val x: Float = 0.0f,
    val y: Float = 0.0f,
    val z: Float = 0.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x.toDouble())
        put("y", y.toDouble())
        put("z", z.toDouble())
    }
}

data class ImuData(
    val accel: AccelData = AccelData(),
    val gyro: GyroData = GyroData(),
    val yaw: Double = 0.0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("accel", accel.toJson())
        put("gyro", gyro.toJson())
        put("yaw", yaw)
    }
}

data class BarometerData(
    val pressure: Float = 0.0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pressure", pressure.toDouble())
    }
}

data class SensorPayload(
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val gps: GpsData = GpsData(),
    val imu: ImuData = ImuData(),
    val barometer: BarometerData = BarometerData()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("timestamp", timestamp)
        put("gps", gps.toJson())
        put("imu", imu.toJson())
        put("barometer", barometer.toJson())
    }

    fun toJsonString(): String = toJson().toString()
}
