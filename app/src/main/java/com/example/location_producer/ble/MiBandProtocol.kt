package com.example.location_producer.ble

import java.util.UUID

/**
 * Pure Kotlin BLE helper for Smart Bands (Standard BLE Heart Rate 0x180D).
 */
object MiBandProtocol {

    val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

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
}
