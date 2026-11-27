package com.example.location_producer.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.location_producer.model.HeartRateData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BleConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED_STREAMING,
    ERROR
}

enum class BleAuthMode {
    STANDARD_HRS,
    MI_BAND_AUTH
}

data class DiscoveredBleDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val isBonded: Boolean = false
)

data class MiBandGatewayState(
    val status: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val mode: BleAuthMode = BleAuthMode.STANDARD_HRS,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val bpm: Int = 0,
    val isHrValid: Boolean = false,
    val lastHrUpdatedAt: Long = 0L,
    val statusMessage: String? = null
)

private data class AuthAttemptSpec(
    val authFlag: Byte,
    val useCryptFlag: Boolean,
    val includeProtocolVersion: Boolean = false
)

class MiBandGateway private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "MiBandGateway"
        private const val GATT_OP_TIMEOUT_MS = 3_000L
        private const val AUTH_ATTEMPT_TIMEOUT_MS = 2_000L
        private const val HR_PING_INTERVAL_MS = 12_000L
        private const val HR_FRESHNESS_TIMEOUT_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(3_000L, 6_000L, 10_000L)

        private val AUTH_ATTEMPTS = listOf(
            AuthAttemptSpec(authFlag = MiBandProtocol.AUTH_FLAG_DEFAULT, useCryptFlag = false, includeProtocolVersion = false),
            AuthAttemptSpec(authFlag = MiBandProtocol.AUTH_FLAG_DEFAULT, useCryptFlag = false, includeProtocolVersion = true),
            AuthAttemptSpec(authFlag = MiBandProtocol.AUTH_FLAG_DEFAULT, useCryptFlag = true, includeProtocolVersion = false),
            AuthAttemptSpec(authFlag = MiBandProtocol.AUTH_FLAG_LEGACY, useCryptFlag = false, includeProtocolVersion = false)
        )

        @Volatile
        private var instance: MiBandGateway? = null

        fun getInstance(context: Context): MiBandGateway {
            return instance ?: synchronized(this) {
                instance ?: MiBandGateway(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _gatewayState = MutableStateFlow(MiBandGatewayState())
    val gatewayState: StateFlow<MiBandGatewayState> = _gatewayState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    @Volatile
    var onLog: ((String) -> Unit)? = null

    private var activeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var authJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var opWatchdogJob: Job? = null

    private val queueLock = Any()
    private val gattQueue = ArrayDeque<() -> Boolean>()
    private var operationInFlight = false

    @Volatile
    private var userInitiatedDisconnect = false

    @Volatile
    private var authFailed = false

    @Volatile
    private var reconnectAttempt = 0

    private var targetAddress: String = ""
    private var targetName: String = ""
    private var targetAuthKeyHex: String = ""
    private var activeAuthKeyBytes: ByteArray? = null

    private var currentAuthFlag: Byte = MiBandProtocol.AUTH_FLAG_DEFAULT
    private var activeUseCryptFlag = false
    private var challengeReceived = false

    private var lastChallenge16: ByteArray? = null
    private var triedFallbackResp = false

    fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun logMsg(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices(): List<DiscoveredBleDevice> {
        if (!hasBluetoothConnectPermission()) {
            logMsg("BLUETOOTH_CONNECT 권한이 없어 페어링된 기기를 조회할 수 없습니다.")
            return emptyList()
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            logMsg("블루투스가 비활성화되어 있습니다.")
            return emptyList()
        }

        val bonded = try {
            adapter.bondedDevices.orEmpty().map { device ->
                DiscoveredBleDevice(
                    name = device.name ?: "Unknown Device",
                    address = device.address,
                    rssi = null,
                    isBonded = true
                )
            }
        } catch (e: SecurityException) {
            logMsg("페어링 기기 조회 권한 오류: ${e.message}")
            emptyList()
        }

        mergeDiscoveredDevices(bonded)
        logMsg("페어링된 블루투스 기기 ${bonded.size}개 조회됨")
        return _discoveredDevices.value
    }

    @SuppressLint("MissingPermission")
    fun startScan(timeoutMs: Long = 10_000L) {
        if (_isScanning.value) return
        if (!hasBluetoothScanPermission() || !hasBluetoothConnectPermission()) {
            logMsg("BLE 스캔 권한(BLUETOOTH_SCAN/CONNECT)이 필요합니다.")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            logMsg("블루투스가 꺼져 있어 스캔할 수 없습니다.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            logMsg("BLE 스캐너를 사용할 수 없습니다.")
            return
        }

        refreshBondedDevices()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val deviceName = try {
                    device.name ?: result.scanRecord?.deviceName ?: "BLE Device"
                } catch (_: SecurityException) {
                    result.scanRecord?.deviceName ?: "BLE Device"
                }
                val entry = DiscoveredBleDevice(
                    name = deviceName,
                    address = device.address,
                    rssi = result.rssi,
                    isBonded = false
                )
                mergeDiscoveredDevices(listOf(entry))
            }

            override fun onScanFailed(errorCode: Int) {
                logMsg("BLE 스캔 실패 (errorCode=$errorCode)")
                stopScan()
            }
        }

        activeScanner = scanner
        scanCallback = callback
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            logMsg("BLE 주변 기기 스캔 시작 (${timeoutMs / 1000}초)")
        } catch (e: SecurityException) {
            logMsg("BLE 스캔 권한 오류: ${e.message}")
            _isScanning.value = false
            scanCallback = null
            return
        }

        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(timeoutMs)
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        val callback = scanCallback
        val scanner = activeScanner
        scanCallback = null
        activeScanner = null
        if (_isScanning.value) {
            _isScanning.value = false
            if (callback != null && scanner != null && hasBluetoothScanPermission()) {
                try {
                    scanner.stopScan(callback)
                    logMsg("BLE 스캔 중지")
                } catch (e: Exception) {
                    Log.w(TAG, "stopScan error: ${e.message}")
                }
            }
        }
    }

    private fun mergeDiscoveredDevices(incoming: List<DiscoveredBleDevice>) {
        if (incoming.isEmpty()) return
        val map = _discoveredDevices.value.associateBy { it.address }.toMutableMap()
        for (item in incoming) {
            val existing = map[item.address]
            if (existing == null) {
                map[item.address] = item
            } else {
                val mergedName = if (item.name != "Unknown Device" && item.name != "BLE Device") {
                    item.name
                } else {
                    existing.name
                }
                map[item.address] = existing.copy(
                    name = mergedName,
                    rssi = item.rssi ?: existing.rssi,
                    isBonded = existing.isBonded || item.isBonded
                )
            }
        }
        _discoveredDevices.value = map.values.sortedWith(
            compareByDescending<DiscoveredBleDevice> { isMiBandName(it.name) }
                .thenByDescending { it.isBonded }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name }
        )
    }

    private fun isMiBandName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("mi smart band") || lower.contains("mi band") || lower.contains("smart band")
    }

    fun connect(address: String, name: String = "", authKeyHex: String = "") {
        val cleanAddress = address.trim()
        if (cleanAddress.isEmpty()) {
            _gatewayState.value = MiBandGatewayState(
                status = BleConnectionStatus.ERROR,
                statusMessage = "연결할 블루투스 기기를 선택해 주세요."
            )
            return
        }

        if (!hasBluetoothConnectPermission()) {
            _gatewayState.value = MiBandGatewayState(
                status = BleConnectionStatus.ERROR,
                deviceName = name,
                deviceAddress = cleanAddress,
                statusMessage = "BLUETOOTH_CONNECT 권한이 필요합니다."
            )
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _gatewayState.value = MiBandGatewayState(
                status = BleConnectionStatus.ERROR,
                deviceName = name,
                deviceAddress = cleanAddress,
                statusMessage = "블루투스를 켜 주세요."
            )
            return
        }

        val parsedKey = if (authKeyHex.isBlank()) {
            null
        } else {
            MiBandProtocol.parseAuthKeyHex(authKeyHex) ?: run {
                _gatewayState.value = MiBandGatewayState(
                    status = BleConnectionStatus.ERROR,
                    mode = BleAuthMode.MI_BAND_AUTH,
                    deviceName = name,
                    deviceAddress = cleanAddress,
                    statusMessage = "Auth Key 형식이 올바르지 않습니다 (32자리 hex)"
                )
                return
            }
        }

        stopScan()
        reconnectJob?.cancel()
        cleanupGatt()

        targetAddress = cleanAddress
        targetName = name.ifBlank { cleanAddress }
        targetAuthKeyHex = authKeyHex
        activeAuthKeyBytes = parsedKey
        userInitiatedDisconnect = false
        authFailed = false
        reconnectAttempt = 0

        openGattConnection()
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection() {
        if (!hasBluetoothConnectPermission()) return
        val adapter = bluetoothAdapter ?: return

        val mode = if (activeAuthKeyBytes != null) BleAuthMode.MI_BAND_AUTH else BleAuthMode.STANDARD_HRS
        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTING,
            mode = mode,
            deviceName = targetName,
            deviceAddress = targetAddress,
            bpm = 0,
            isHrValid = false,
            statusMessage = "BLE 연결 중... ($targetName)"
        )
        logMsg("연결 시도: $targetName ($targetAddress), mode=$mode")

        try {
            val device = adapter.getRemoteDevice(targetAddress)
            bluetoothGatt = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: Exception) {
            logMsg("GATT 연결 시작 실패: ${e.message}")
            _gatewayState.value = _gatewayState.value.copy(
                status = BleConnectionStatus.ERROR,
                statusMessage = "BLE 연결 실패: ${e.message}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopScan()

        val gatt = bluetoothGatt
        if (gatt != null && activeAuthKeyBytes != null && hasBluetoothConnectPermission()) {
            val hrService = gatt.getService(MiBandProtocol.SERVICE_HEART_RATE)
            val hrControl = hrService?.getCharacteristic(MiBandProtocol.CHAR_HEART_RATE_CONTROL_POINT)
            if (hrControl != null) {
                try {
                    writeCharacteristicRaw(gatt, hrControl, MiBandProtocol.CMD_HR_STOP_CONTINUOUS)
                } catch (_: Exception) {
                }
            }
        }

        cleanupGatt()
        _gatewayState.value = MiBandGatewayState(
            status = BleConnectionStatus.DISCONNECTED,
            mode = if (activeAuthKeyBytes != null) BleAuthMode.MI_BAND_AUTH else BleAuthMode.STANDARD_HRS,
            deviceName = targetName,
            deviceAddress = targetAddress,
            statusMessage = "연결 해제됨"
        )
        logMsg("BLE 연결 해제 완료")
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt() {
        authJob?.cancel()
        authJob = null
        pingJob?.cancel()
        pingJob = null
        opWatchdogJob?.cancel()
        opWatchdogJob = null
        clearGattQueue()

        bluetoothGatt?.let { gatt ->
            try {
                if (hasBluetoothConnectPermission()) {
                    gatt.disconnect()
                    gatt.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cleanupGatt error: ${e.message}")
            }
        }
        bluetoothGatt = null
    }

    private fun clearGattQueue() {
        synchronized(queueLock) {
            gattQueue.clear()
            operationInFlight = false
        }
    }

    private fun enqueueGattOperation(operation: () -> Boolean) {
        synchronized(queueLock) {
            gattQueue.addLast(operation)
            if (!operationInFlight) {
                drainNextOperationLocked()
            }
        }
    }

    private fun onGattOperationComplete() {
        opWatchdogJob?.cancel()
        opWatchdogJob = null
        synchronized(queueLock) {
            operationInFlight = false
            drainNextOperationLocked()
        }
    }

    private fun drainNextOperationLocked() {
        while (gattQueue.isNotEmpty()) {
            val next = gattQueue.removeFirst()
            operationInFlight = true
            val started = try {
                next()
            } catch (e: Exception) {
                Log.e(TAG, "GATT operation threw exception: ${e.message}", e)
                false
            }
            if (started) {
                opWatchdogJob?.cancel()
                opWatchdogJob = scope.launch {
                    delay(GATT_OP_TIMEOUT_MS)
                    logMsg("GATT 명령 응답 타임아웃 — 다음 명령 진행")
                    onGattOperationComplete()
                }
                return
            } else {
                operationInFlight = false
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                reconnectJob?.cancel()
                reconnectAttempt = 0
                logMsg("GATT 연결 성공 — 서비스 탐색 시작")
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.CONNECTING,
                    statusMessage = "GATT 서비스 탐색 중..."
                )
                if (hasBluetoothConnectPermission()) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logMsg("GATT 연결 종료 (status=$status)")
                cleanupGatt()
                if (!userInitiatedDisconnect && !authFailed && targetAddress.isNotEmpty()) {
                    scheduleReconnect()
                } else if (!authFailed) {
                    _gatewayState.value = _gatewayState.value.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        bpm = 0,
                        isHrValid = false,
                        statusMessage = "연결 끊김 (status=$status)"
                    )
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("GATT 연결 오류 (status=$status, newState=$newState)")
                cleanupGatt()
                if (!userInitiatedDisconnect && !authFailed && targetAddress.isNotEmpty()) {
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("서비스 탐색 실패 (status=$status)")
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.ERROR,
                    statusMessage = "GATT 서비스 탐색 실패 (status=$status)"
                )
                return
            }

            val baseService = gatt.getService(MiBandProtocol.SERVICE_HUAMI_BASE)
            val hasChunked2021 = baseService?.getCharacteristic(MiBandProtocol.CHAR_CHUNKED_2021_WRITE) != null &&
                baseService.getCharacteristic(MiBandProtocol.CHAR_CHUNKED_2021_READ) != null

            if (activeAuthKeyBytes != null) {
                val authService = gatt.getService(MiBandProtocol.SERVICE_HUAMI_AUTH)
                val authChar = authService?.getCharacteristic(MiBandProtocol.CHAR_HUAMI_AUTH)
                if (authChar != null) {
                    logMsg(
                        "서비스 탐색 완료: 0x0009(props=0x${authChar.properties.toString(16)}), " +
                            "2021chunked=$hasChunked2021"
                    )
                    startHuamiAuthentication(gatt, authChar)
                } else {
                    logMsg("Huami Auth 서비스(0xFEE1)를 찾지 못해 표준 BLE(0x180D) 모드로 전환합니다.")
                    subscribeHeartRate(gatt, sendControlCommands = false)
                }
            } else {
                subscribeHeartRate(gatt, sendControlCommands = false)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("Descriptor 쓰기 실패 (${descriptor.characteristic.uuid}): status=$status")
            }
            onGattOperationComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("Characteristic 쓰기 실패 (${characteristic.uuid}): status=$status")
            }
            onGattOperationComplete()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                handleCharacteristicChanged(gatt, characteristic, value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startHuamiAuthentication(
        gatt: BluetoothGatt,
        authChar: BluetoothGattCharacteristic
    ) {
        currentAuthFlag = MiBandProtocol.AUTH_FLAG_DEFAULT
        activeUseCryptFlag = false
        challengeReceived = false
        lastChallenge16 = null
        triedFallbackResp = false

        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.AUTHENTICATING,
            mode = BleAuthMode.MI_BAND_AUTH,
            statusMessage = "Mi Band Auth 인증 중..."
        )
        logMsg("Mi Band 6 AES 인증 시작")

        enableNotification(gatt, authChar)

        authJob?.cancel()
        authJob = scope.launch {
            for ((index, attempt) in AUTH_ATTEMPTS.withIndex()) {
                if (challengeReceived ||
                    bluetoothGatt !== gatt ||
                    _gatewayState.value.status != BleConnectionStatus.AUTHENTICATING
                ) {
                    return@launch
                }

                currentAuthFlag = attempt.authFlag
                activeUseCryptFlag = attempt.useCryptFlag
                val payload = MiBandProtocol.buildRequestRandomChallenge(
                    authFlag = attempt.authFlag,
                    useCryptFlag = attempt.useCryptFlag,
                    includeProtocolVersion = attempt.includeProtocolVersion
                )
                logMsg("Auth TX (#${index + 1}/${AUTH_ATTEMPTS.size}): ${MiBandProtocol.bytesToHex(payload)}")
                enqueueGattOperation {
                    writeCharacteristicRaw(gatt, authChar, payload)
                }

                delay(AUTH_ATTEMPT_TIMEOUT_MS)
            }

            if (!challengeReceived &&
                bluetoothGatt === gatt &&
                _gatewayState.value.status == BleConnectionStatus.AUTHENTICATING
            ) {
                authFailed = true
                val msg = "Mi Band 인증 응답 타임아웃 (Operation Logs 확인 필요)"
                logMsg(msg)
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.ERROR,
                    bpm = 0,
                    isHrValid = false,
                    statusMessage = msg
                )
                cleanupGatt()
            }
        }
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when (characteristic.uuid) {
            MiBandProtocol.CHAR_HUAMI_AUTH -> handleAuthNotification(gatt, characteristic, value)
            MiBandProtocol.CHAR_HEART_RATE_MEASUREMENT -> handleHeartRateNotification(value)
        }
    }

    private fun handleAuthNotification(
        gatt: BluetoothGatt,
        authChar: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        logMsg("Auth RX: ${MiBandProtocol.bytesToHex(value)}")
        val parsed = MiBandProtocol.parseAuthNotification(value)
        when (parsed) {
            is MiBandProtocol.AuthNotification.Challenge -> {
                challengeReceived = true
                triedFallbackResp = false
                authJob?.cancel()
                val key = activeAuthKeyBytes ?: return
                lastChallenge16 = parsed.random16
                val useCrypt = parsed.command == 0x82.toByte() || activeUseCryptFlag
                logMsg("인증 챌린지 수신 — AES 응답 전송 (flag=0x${String.format("%02X", currentAuthFlag)}, crypt=$useCrypt)")
                val response = MiBandProtocol.buildEncryptedAuthResponse(
                    authKey16 = key,
                    random16 = parsed.random16,
                    authFlag = currentAuthFlag,
                    useCryptFlag = useCrypt
                )
                logMsg("Auth TX (resp): ${MiBandProtocol.bytesToHex(response)}")
                enqueueGattOperation {
                    writeCharacteristicRaw(gatt, authChar, response)
                }
            }
            is MiBandProtocol.AuthNotification.AuthSuccess -> {
                authJob?.cancel()
                logMsg("Mi Band Auth SUCCESS — 연속 심박 측정 시작")
                subscribeHeartRate(gatt, sendControlCommands = true)
            }
            is MiBandProtocol.AuthNotification.AuthFailure -> {
                val cmdHex = String.format("%02X", parsed.command)
                val statusHex = String.format("%02X", parsed.status)
                logMsg("Auth RX Failure: cmd=0x$cmdHex, status=0x$statusHex")

                val challenge = lastChallenge16
                val key = activeAuthKeyBytes
                if (!triedFallbackResp && key != null && challenge != null) {
                    triedFallbackResp = true
                    val fallbackFlag = if (currentAuthFlag == MiBandProtocol.AUTH_FLAG_DEFAULT) MiBandProtocol.AUTH_FLAG_LEGACY else MiBandProtocol.AUTH_FLAG_DEFAULT
                    val fallbackCrypt = !activeUseCryptFlag
                    logMsg("AES 응답 거부(status=0x$statusHex) — 대체 플래그(flag=0x${String.format("%02X", fallbackFlag)}, crypt=$fallbackCrypt)로 재시도")
                    val response = MiBandProtocol.buildEncryptedAuthResponse(
                        authKey16 = key,
                        random16 = challenge,
                        authFlag = fallbackFlag,
                        useCryptFlag = fallbackCrypt
                    )
                    enqueueGattOperation {
                        writeCharacteristicRaw(gatt, authChar, response)
                    }
                    return
                }

                authJob?.cancel()
                authFailed = true
                val msg = "Mi Band 인증 실패 (cmd=0x$cmdHex, status=0x$statusHex). Zepp Life 앱을 [강제 중지] 후 다시 시도해보세요."
                logMsg(msg)
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.ERROR,
                    bpm = 0,
                    isHrValid = false,
                    statusMessage = msg
                )
                cleanupGatt()
            }
            is MiBandProtocol.AuthNotification.Unknown -> {
                logMsg("알 수 없는 Auth 응답: ${MiBandProtocol.bytesToHex(parsed.raw)}")
            }
        }
    }

    private fun subscribeHeartRate(gatt: BluetoothGatt, sendControlCommands: Boolean) {
        val hrService = gatt.getService(MiBandProtocol.SERVICE_HEART_RATE)
        val hrChar = hrService?.getCharacteristic(MiBandProtocol.CHAR_HEART_RATE_MEASUREMENT)
        if (hrChar == null) {
            val msg = "Heart Rate Service(0x180D)를 찾을 수 없습니다."
            logMsg(msg)
            _gatewayState.value = _gatewayState.value.copy(
                status = BleConnectionStatus.ERROR,
                statusMessage = msg
            )
            return
        }

        enableNotification(gatt, hrChar)

        val hrControl = hrService.getCharacteristic(MiBandProtocol.CHAR_HEART_RATE_CONTROL_POINT)
        if (sendControlCommands && hrControl != null) {
            enqueueGattOperation { writeCharacteristicRaw(gatt, hrControl, MiBandProtocol.CMD_HR_STOP_MANUAL) }
            enqueueGattOperation { writeCharacteristicRaw(gatt, hrControl, MiBandProtocol.CMD_HR_STOP_CONTINUOUS) }
            enqueueGattOperation { writeCharacteristicRaw(gatt, hrControl, MiBandProtocol.CMD_HR_START_CONTINUOUS) }

            pingJob?.cancel()
            pingJob = scope.launch {
                while (isActive) {
                    delay(HR_PING_INTERVAL_MS)
                    if (bluetoothGatt === gatt &&
                        _gatewayState.value.status == BleConnectionStatus.CONNECTED_STREAMING
                    ) {
                        enqueueGattOperation {
                            writeCharacteristicRaw(gatt, hrControl, MiBandProtocol.CMD_HR_PING)
                        }
                    }
                }
            }

            _gatewayState.value = _gatewayState.value.copy(
                status = BleConnectionStatus.CONNECTED_STREAMING,
                mode = BleAuthMode.MI_BAND_AUTH,
                statusMessage = "Mi Band 연속 심박수 스트리밍 중"
            )
            logMsg("연속 심박 측정 활성화 및 Keep-Alive 시작")
        } else {
            _gatewayState.value = _gatewayState.value.copy(
                status = BleConnectionStatus.CONNECTED_STREAMING,
                mode = BleAuthMode.STANDARD_HRS,
                statusMessage = "표준 BLE 심박수(0x180D) 구독 중 — 값 대기"
            )
            logMsg("표준 BLE Heart Rate(0x2A37) Notification 구독 시작")
        }
    }

    private fun handleHeartRateNotification(value: ByteArray) {
        val bpm = MiBandProtocol.parseHeartRateMeasurement(value) ?: return
        val now = System.currentTimeMillis()
        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTED_STREAMING,
            bpm = bpm,
            isHrValid = true,
            lastHrUpdatedAt = now,
            statusMessage = "수신 중: $bpm BPM"
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasBluetoothConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(MiBandProtocol.DESCRIPTOR_CCCD)
        if (cccd != null) {
            enqueueGattOperation {
                writeDescriptorRaw(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristicRaw(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        if (!hasBluetoothConnectPermission()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = value
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptorRaw(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        if (!hasBluetoothConnectPermission()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        reconnectAttempt++
        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTING,
            bpm = 0,
            isHrValid = false,
            statusMessage = "연결 끊김 — ${delayMs / 1000}초 후 재연결 시도..."
        )
        logMsg("${delayMs / 1000}초 후 BLE 자동 재연결 예정 (시도 #$reconnectAttempt)")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userInitiatedDisconnect && !authFailed && targetAddress.isNotEmpty()) {
                openGattConnection()
            }
        }
    }

    fun getLatestHeartRate(): HeartRateData {
        val state = _gatewayState.value
        val isFresh = state.bpm > 0 &&
            (System.currentTimeMillis() - state.lastHrUpdatedAt) < HR_FRESHNESS_TIMEOUT_MS
        return HeartRateData(
            bpm = if (isFresh) state.bpm else 0,
            isValid = isFresh,
            updatedAt = state.lastHrUpdatedAt,
            source = state.deviceName.ifBlank { state.deviceAddress }
        )
    }
}
