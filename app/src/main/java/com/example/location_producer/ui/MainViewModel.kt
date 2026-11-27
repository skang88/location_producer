package com.example.location_producer.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.location_producer.ble.DiscoveredBleDevice
import com.example.location_producer.ble.HuamiCloudClient
import com.example.location_producer.ble.MiBandGateway
import com.example.location_producer.ble.MiBandGatewayState
import com.example.location_producer.ble.MiBandProtocol
import com.example.location_producer.data.PreferencesRepository
import com.example.location_producer.mqtt.MqttState
import com.example.location_producer.service.SensorStreamingService
import com.example.location_producer.service.ServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PreferencesRepository(application)
    private val miBandGateway = MiBandGateway.getInstance(application)

    private val _brokerUrl = MutableStateFlow(repository.brokerUrl)
    val brokerUrl: StateFlow<String> = _brokerUrl.asStateFlow()

    private val _deviceId = MutableStateFlow(repository.deviceId)
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    private val _miBandAddress = MutableStateFlow(repository.miBandAddress)
    val miBandAddress: StateFlow<String> = _miBandAddress.asStateFlow()

    private val _miBandName = MutableStateFlow(repository.miBandName)
    val miBandName: StateFlow<String> = _miBandName.asStateFlow()

    private val _miBandAuthKey = MutableStateFlow(repository.miBandAuthKey)
    val miBandAuthKey: StateFlow<String> = _miBandAuthKey.asStateFlow()

    private val _zeppEmail = MutableStateFlow(repository.zeppEmail)
    val zeppEmail: StateFlow<String> = _zeppEmail.asStateFlow()

    private val _zeppPassword = MutableStateFlow(repository.zeppPassword)
    val zeppPassword: StateFlow<String> = _zeppPassword.asStateFlow()

    private val _isFetchingZeppKey = MutableStateFlow(false)
    val isFetchingZeppKey: StateFlow<Boolean> = _isFetchingZeppKey.asStateFlow()

    val serviceStatus: StateFlow<ServiceStatus> = SensorStreamingService.serviceStatus
    val mqttState: StateFlow<MqttState> = SensorStreamingService.mqttState
    val miBandState: StateFlow<MiBandGatewayState> = miBandGateway.gatewayState
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = miBandGateway.discoveredDevices
    val isScanning: StateFlow<Boolean> = miBandGateway.isScanning

    init {
        miBandGateway.onLog = { SensorStreamingService.addLog("[BLE] $it") }
    }

    fun updateBrokerUrl(url: String) {
        _brokerUrl.value = url
        repository.brokerUrl = url
    }

    fun updateDeviceId(id: String) {
        _deviceId.value = id
        repository.deviceId = id
    }

    fun updateZeppEmail(email: String) {
        _zeppEmail.value = email
        repository.zeppEmail = email
    }

    fun updateZeppPassword(password: String) {
        _zeppPassword.value = password
        repository.zeppPassword = password
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

    fun updateMiBandAuthKey(key: String) {
        _miBandAuthKey.value = key
        repository.miBandAuthKey = key
    }

    fun fetchAuthKeyFromZepp() {
        val app = getApplication<Application>()
        val email = _zeppEmail.value.trim()
        val password = _zeppPassword.value.trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(app, "Zepp 계정 이메일과 비밀번호를 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        _isFetchingZeppKey.value = true
        SensorStreamingService.addLog("[Zepp Cloud] Zepp 계정 로그인 시도 중...")

        viewModelScope.launch(Dispatchers.IO) {
            val result = HuamiCloudClient.fetchAuthKeys(email, password)
            withContext(Dispatchers.Main) {
                _isFetchingZeppKey.value = false
                result.fold(
                    onSuccess = { devices ->
                        val currentTarget = _miBandAddress.value.trim()
                        val matched = if (currentTarget.isNotEmpty()) {
                            devices.firstOrNull { it.mac.equals(currentTarget, ignoreCase = true) }
                                ?: devices.last()
                        } else {
                            devices.last()
                        }

                        updateMiBandAuthKey(matched.authKey)
                        _miBandAddress.value = matched.mac
                        _miBandName.value = matched.deviceName
                        repository.miBandAddress = matched.mac
                        repository.miBandName = matched.deviceName

                        val msg = "Zepp Cloud Auth Key 동기화 완료: ${matched.deviceName} (${matched.mac})"
                        SensorStreamingService.addLog("[Zepp Cloud] $msg")
                        Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { err ->
                        val errMsg = "Zepp Auth Key 가져오기 실패: ${err.message}"
                        SensorStreamingService.addLog("[Zepp Cloud] $errMsg")
                        Toast.makeText(app, errMsg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    fun connectMiBand() {
        val app = getApplication<Application>()
        val address = _miBandAddress.value.trim()
        if (address.isEmpty()) {
            Toast.makeText(app, "먼저 Mi Band 기기를 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val authKey = _miBandAuthKey.value.trim()
        if (!MiBandProtocol.isValidAuthKeyInput(authKey)) {
            Toast.makeText(app, "Auth Key는 비워두거나 32자리 hex여야 합니다.", Toast.LENGTH_LONG).show()
            return
        }
        miBandGateway.connect(address, _miBandName.value, authKey)
    }

    fun pairMiBand() {
        val app = getApplication<Application>()
        val address = _miBandAddress.value.trim()
        if (address.isEmpty()) {
            Toast.makeText(app, "먼저 Mi Band 기기를 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val authKey = _miBandAuthKey.value.trim()
        if (!MiBandProtocol.isValidAuthKeyInput(authKey)) {
            Toast.makeText(app, "Auth Key는 비워두거나 32자리 hex여야 합니다.", Toast.LENGTH_LONG).show()
            return
        }
        miBandGateway.connect(address, _miBandName.value, authKey, forcePairFirst = true)
    }

    fun disconnectMiBand() {
        miBandGateway.disconnect()
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
            putExtra(SensorStreamingService.EXTRA_MIBAND_AUTH_KEY, _miBandAuthKey.value.trim())
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
