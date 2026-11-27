package com.example.location_producer.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import com.example.location_producer.ble.DiscoveredBleDevice
import com.example.location_producer.ble.Esp32Gateway
import com.example.location_producer.ble.Esp32GatewayState
import com.example.location_producer.ble.MiBandGateway
import com.example.location_producer.ble.MiBandGatewayState
import com.example.location_producer.data.PreferencesRepository
import com.example.location_producer.mqtt.MqttState
import com.example.location_producer.service.SensorStreamingService
import com.example.location_producer.service.ServiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)
    private val miBandGateway = MiBandGateway.getInstance(application)
    private val esp32Gateway = Esp32Gateway.getInstance(application)

    private val _brokerUrl = MutableStateFlow(repository.brokerUrl)
    val brokerUrl: StateFlow<String> = _brokerUrl.asStateFlow()

    private val _deviceId = MutableStateFlow(repository.deviceId)
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _miBandAddress = MutableStateFlow(repository.miBandAddress)
    val miBandAddress: StateFlow<String> = _miBandAddress.asStateFlow()

    private val _miBandName = MutableStateFlow(repository.miBandName)
    val miBandName: StateFlow<String> = _miBandName.asStateFlow()

    private val _esp32Address = MutableStateFlow(repository.esp32Address)
    val esp32Address: StateFlow<String> = _esp32Address.asStateFlow()

    private val _esp32Name = MutableStateFlow(repository.esp32Name)
    val esp32Name: StateFlow<String> = _esp32Name.asStateFlow()

    val serviceStatus: StateFlow<ServiceStatus> = SensorStreamingService.serviceStatus
    val mqttState: StateFlow<MqttState> = SensorStreamingService.mqttState
    val miBandState: StateFlow<MiBandGatewayState> = miBandGateway.gatewayState
    val esp32State: StateFlow<Esp32GatewayState> = esp32Gateway.gatewayState
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = miBandGateway.discoveredDevices
    val isScanning: StateFlow<Boolean> = miBandGateway.isScanning

    init {
        miBandGateway.onLog = { SensorStreamingService.addLog("[BLE] $it") }
        esp32Gateway.onLog = { SensorStreamingService.addLog("[ESP32] $it") }
    }

    fun updateBrokerUrl(url: String) {
        _brokerUrl.value = url
        repository.brokerUrl = url
    }

    fun updateDeviceId(id: String) {
        _deviceId.value = id
        repository.deviceId = id
    }

    fun refreshBondedDevices() {
        miBandGateway.refreshBondedDevices()
    }

    fun toggleBleScan() {
        if (miBandGateway.isScanning.value) {
            miBandGateway.stopScan()
        } else {
            miBandGateway.startScan()
        }
    }

    fun stopBleScan() {
        miBandGateway.stopScan()
    }

    fun selectMiBandDevice(device: DiscoveredBleDevice) {
        _miBandAddress.value = device.address
        _miBandName.value = device.name
        repository.miBandAddress = device.address
        repository.miBandName = device.name
        miBandGateway.stopScan()
    }

    fun clearSelectedMiBand() {
        miBandGateway.disconnect()
        _miBandAddress.value = ""
        _miBandName.value = ""
        repository.miBandAddress = ""
        repository.miBandName = ""
    }

    fun selectEsp32Device(device: DiscoveredBleDevice) {
        _esp32Address.value = device.address
        _esp32Name.value = device.name
        repository.esp32Address = device.address
        repository.esp32Name = device.name
        miBandGateway.stopScan()
    }

    fun clearSelectedEsp32() {
        esp32Gateway.disconnect()
        _esp32Address.value = ""
        _esp32Name.value = ""
        repository.esp32Address = ""
        repository.esp32Name = ""
    }

    fun connectMiBand() {
        val app = getApplication<Application>()
        val address = _miBandAddress.value.trim()
        if (address.isEmpty()) {
            Toast.makeText(app, "먼저 Mi Band 기기를 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        miBandGateway.connect(address, _miBandName.value)
    }

    fun disconnectMiBand() {
        miBandGateway.disconnect()
    }

    fun connectEsp32() {
        val app = getApplication<Application>()
        val address = _esp32Address.value.trim()
        if (address.isEmpty()) {
            Toast.makeText(app, "먼저 ESP32 기기를 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        esp32Gateway.connect(address, _esp32Name.value)
    }

    fun disconnectEsp32() {
        esp32Gateway.disconnect()
    }

    fun togglePauseSniffer() {
        esp32Gateway.togglePauseSniffer()
    }

    fun selectCharUuid(charUuid: String) {
        esp32Gateway.selectCharUuid(charUuid)
    }

    fun clearLogs() {
        SensorStreamingService.clearLogs()
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
            putExtra(SensorStreamingService.EXTRA_MIBAND_ADDRESS, _miBandAddress.value.trim())
            putExtra(SensorStreamingService.EXTRA_MIBAND_NAME, _miBandName.value.trim())
            putExtra(SensorStreamingService.EXTRA_ESP32_ADDRESS, _esp32Address.value.trim())
            putExtra(SensorStreamingService.EXTRA_ESP32_NAME, _esp32Name.value.trim())
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

    override fun onCleared() {
        super.onCleared()
        miBandGateway.stopScan()
    }
}
