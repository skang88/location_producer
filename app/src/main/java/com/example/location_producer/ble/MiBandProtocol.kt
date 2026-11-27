package com.example.location_producer.ble

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin protocol & cryptographic helpers for Xiaomi Mi Band 6 (Huami Auth + BLE Heart Rate Service).
 */
object MiBandProtocol {

    val SERVICE_HUAMI_BASE: UUID = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    val CHAR_CHUNKED_2021_WRITE: UUID = UUID.fromString("00000016-0000-3512-2118-0009af100700")
    val CHAR_CHUNKED_2021_READ: UUID = UUID.fromString("00000017-0000-3512-2118-0009af100700")

    val SERVICE_HUAMI_AUTH: UUID = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    val CHAR_HUAMI_AUTH: UUID = UUID.fromString("00000009-0000-3512-2118-0009af100700")

    val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val CHAR_HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CHAR_HEART_RATE_CONTROL_POINT: UUID = UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb")
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val AUTH_FLAG_DEFAULT: Byte = 0x00
    const val AUTH_FLAG_LEGACY: Byte = 0x08

    val CMD_HR_STOP_MANUAL = byteArrayOf(0x15, 0x02, 0x00)
    val CMD_HR_STOP_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x00)
    val CMD_HR_START_CONTINUOUS = byteArrayOf(0x15, 0x01, 0x01)
    val CMD_HR_PING = byteArrayOf(0x16)

    sealed class AuthNotification {
        data class Challenge(val random16: ByteArray, val command: Byte = 0x02) : AuthNotification() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Challenge) return false
                return command == other.command && random16.contentEquals(other.random16)
            }

            override fun hashCode(): Int = 31 * random16.contentHashCode() + command.hashCode()
        }

        data object PairSuccess : AuthNotification()
        data object AuthSuccess : AuthNotification()
        data class AuthFailure(val command: Byte, val status: Byte) : AuthNotification()
        data class Unknown(val raw: ByteArray) : AuthNotification() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Unknown) return false
                return raw.contentEquals(other.raw)
            }

            override fun hashCode(): Int = raw.contentHashCode()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    fun normalizeAuthKeyHex(input: String): String {
        var cleaned = input.trim()
        if (cleaned.startsWith("0x", ignoreCase = true)) {
            cleaned = cleaned.substring(2)
        }
        return cleaned.replace(Regex("[\\s:\\-_]"), "")
    }

    fun parseAuthKeyHex(input: String): ByteArray? {
        val hex = normalizeAuthKeyHex(input)
        if (hex.length != 32) return null
        val out = ByteArray(16)
        for (i in 0 until 16) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return null
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isValidAuthKeyInput(input: String): Boolean {
        return input.isBlank() || parseAuthKeyHex(input) != null
    }

    fun buildSendKeyRequest(
        authKey16: ByteArray,
        authFlag: Byte = AUTH_FLAG_DEFAULT,
        useCryptFlag: Boolean = false
    ): ByteArray {
        val cmd = if (useCryptFlag) 0x81.toByte() else 0x01.toByte()
        return byteArrayOf(cmd, authFlag) + authKey16
    }

    fun buildRequestRandomChallenge(
        authFlag: Byte = AUTH_FLAG_DEFAULT,
        useCryptFlag: Boolean = false,
        includeProtocolVersion: Boolean = false
    ): ByteArray {
        val cmd = if (useCryptFlag) 0x82.toByte() else 0x02.toByte()
        return if (includeProtocolVersion) {
            byteArrayOf(cmd, authFlag, 0x02)
        } else {
            byteArrayOf(cmd, authFlag)
        }
    }

    fun encryptChallenge(authKey16: ByteArray, random16: ByteArray): ByteArray {
        require(authKey16.size == 16) { "Auth key must be 16 bytes, got ${authKey16.size}" }
        require(random16.size == 16) { "Random challenge must be 16 bytes, got ${random16.size}" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(authKey16, "AES"))
        return cipher.doFinal(random16)
    }

    fun buildEncryptedAuthResponse(
        authKey16: ByteArray,
        random16: ByteArray,
        authFlag: Byte = AUTH_FLAG_DEFAULT,
        useCryptFlag: Boolean = false
    ): ByteArray {
        val cmd = if (useCryptFlag) 0x83.toByte() else 0x03.toByte()
        val encrypted = encryptChallenge(authKey16, random16)
        return byteArrayOf(cmd, authFlag) + encrypted
    }

    fun parseAuthNotification(bytes: ByteArray): AuthNotification {
        if (bytes.size >= 3 && bytes[0] == 0x10.toByte()) {
            val cmd = bytes[1]
            val status = bytes[2]
            if ((cmd == 0x01.toByte() || cmd == 0x81.toByte()) && status == 0x01.toByte()) {
                return AuthNotification.PairSuccess
            }
            if ((cmd == 0x02.toByte() || cmd == 0x82.toByte()) &&
                status == 0x01.toByte() &&
                bytes.size >= 19
            ) {
                return AuthNotification.Challenge(bytes.copyOfRange(3, 19), cmd)
            }
            if ((cmd == 0x03.toByte() || cmd == 0x83.toByte()) && status == 0x01.toByte()) {
                return AuthNotification.AuthSuccess
            }
            if (status != 0x01.toByte()) {
                return AuthNotification.AuthFailure(cmd, status)
            }
        }
        return AuthNotification.Unknown(bytes)
    }

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
