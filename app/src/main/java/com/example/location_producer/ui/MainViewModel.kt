package com.example.location_producer.ui

import android.app.Application
import android.content.Intent
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

    fun startService() {
        val app = getApplication<Application>()
        val intent = Intent(app, SensorStreamingService::class.java).apply {
            action = SensorStreamingService.ACTION_START
            putExtra(SensorStreamingService.EXTRA_BROKER_URL, _brokerUrl.value)
            putExtra(SensorStreamingService.EXTRA_DEVICE_ID, _deviceId.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun stopService() {
        val app = getApplication<Application>()
        val intent = Intent(app, SensorStreamingService::class.java).apply {
            action = SensorStreamingService.ACTION_STOP
        }
        app.startService(intent)
    }
}
