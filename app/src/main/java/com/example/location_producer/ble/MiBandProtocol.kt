package com.example.location_producer.ble

import java.util.UUID

/**
 * Pure Kotlin BLE helper for Smart Bands (Standard BLE Heart Rate 0x180D + Pulse Oximeter 0x1822).
 */
object MiBandProtocol {

    val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    val SERVICE_PULSE_OXIMETER: UUID = UUID.fromString("00001822-0000-1000-8000-00805f9b34fb")
    val CHAR_PLX_CONTINUOUS_MEASUREMENT: UUID = UUID.fromString("00002a5f-0000-1000-8000-00805f9b34fb")
    val CHAR_PLX_SPOT_CHECK_MEASUREMENT: UUID = UUID.fromString("00002a5e-0000-1000-8000-00805f9b34fb")

    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    fun parseHeartRateMeasurement(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val is16Bit = (bytes[0].toInt() and 0x01) != 0
        val bpm = if (is16Bit) {
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else {
            bytes[1].toInt() and 0xFF
        }
        return if (bpm in 1..300) bpm else null
    }

    fun parseSpO2Measurement(bytes: ByteArray): Int? {
        if (bytes.size < 3) return null
        val spo2 = bytes[2].toInt() and 0xFF
        return if (spo2 in 70..100) spo2 else null
    }
}
