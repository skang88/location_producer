package com.example.location_producer.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.example.location_producer.data.PreferencesRepository
import com.example.location_producer.service.SensorStreamingService
import com.example.location_producer.service.ServiceStatus
import com.example.location_producer.mqtt.MqttState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)

    private val _brokerUrl = MutableStateFlow(repository.brokerUrl)
    val brokerUrl: StateFlow<String> = _brokerUrl.asStateFlow()

    private val _deviceId = MutableStateFlow(repository.deviceId)
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    val serviceStatus: StateFlow<ServiceStatus> = SensorStreamingService.serviceStatus
    val mqttState: StateFlow<MqttState> = SensorStreamingService.mqttState

    fun updateBrokerUrl(url: String) {
        _brokerUrl.value = url
        repository.brokerUrl = url
    }

    fun updateDeviceId(id: String) {
        _deviceId.value = id
        repository.deviceId = id
    }

    fun startService(): Boolean {
        val app = getApplication<Application>()
        val trimmedId = _deviceId.value.trim()

        if (trimmedId.isEmpty()) {
            Toast.makeText(app, "Producer ID를 입력해 주세요. (예: RESCUER_01)", Toast.LENGTH_LONG).show()
            return false
        }

        val intent = Intent(app, SensorStreamingService::class.java).apply {
            action = SensorStreamingService.ACTION_START
            putExtra(SensorStreamingService.EXTRA_BROKER_URL, _brokerUrl.value)
            putExtra(SensorStreamingService.EXTRA_DEVICE_ID, trimmedId)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        return true
    }

    fun stopService() {
        val app = getApplication<Application>()
        val intent = Intent(app, SensorStreamingService::class.java).apply {
            action = SensorStreamingService.ACTION_STOP
        }
        app.startService(intent)
    }
}
