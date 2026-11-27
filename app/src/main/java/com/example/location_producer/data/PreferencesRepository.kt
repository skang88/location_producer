package com.example.location_producer.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("location_producer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BROKER_URL = "broker_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MIBAND_ADDRESS = "miband_address"
        private const val KEY_MIBAND_NAME = "miband_name"
        private const val KEY_ESP32_ADDRESS = "esp32_address"
        private const val KEY_ESP32_NAME = "esp32_name"

        const val DEFAULT_BROKER_URL = "ws://mqtt.gnfire.opik.net:80/"
        const val DEFAULT_DEVICE_ID = ""
    }

    var brokerUrl: String
        get() {
            val saved = prefs.getString(KEY_BROKER_URL, DEFAULT_BROKER_URL) ?: DEFAULT_BROKER_URL
            return if (saved.startsWith("wss://")) {
                val updated = saved.replace("wss://", "ws://")
                prefs.edit().putString(KEY_BROKER_URL, updated).apply()
                updated
            } else {
                saved
            }
        }
        set(value) = prefs.edit().putString(KEY_BROKER_URL, value).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var miBandAddress: String
        get() = prefs.getString(KEY_MIBAND_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MIBAND_ADDRESS, value).apply()

    var miBandName: String
        get() = prefs.getString(KEY_MIBAND_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MIBAND_NAME, value).apply()

    var esp32Address: String
        get() = prefs.getString(KEY_ESP32_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ESP32_ADDRESS, value).apply()

    var esp32Name: String
        get() = prefs.getString(KEY_ESP32_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ESP32_NAME, value).apply()
}
