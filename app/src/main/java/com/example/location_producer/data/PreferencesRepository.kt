package com.example.location_producer.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("location_producer_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BROKER_URL = "broker_url"
        private const val KEY_DEVICE_ID = "device_id"

        const val DEFAULT_BROKER_URL = "ws://mqtt.gnfire.opik.net:80/"
        const val DEFAULT_DEVICE_ID = "RESCUER_01"
    }

    var brokerUrl: String
        get() {
            val saved = prefs.getString(KEY_BROKER_URL, DEFAULT_BROKER_URL) ?: DEFAULT_BROKER_URL
            // If previously saved as wss://, migrate to ws://
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
}
