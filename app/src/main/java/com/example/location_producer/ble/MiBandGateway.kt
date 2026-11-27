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
import com.example.location_producer.model.VitalsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BleConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_STREAMING,
    ERROR
}

data class DiscoveredBleDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val isBonded: Boolean = false
)

data class MiBandGatewayState(
    val status: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val bpm: Int = 0,
    val isVitalsValid: Boolean = false,
    val lastUpdatedAt: Long = 0L,
    val statusMessage: String? = null
)

class MiBandGateway private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "MiBandGateway"
        private const val GATT_OP_TIMEOUT_MS = 2_000L
        private const val VITALS_FRESHNESS_TIMEOUT_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(3_000L, 6_000L, 10_000L)

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
    private var reconnectJob: Job? = null
    private var opWatchdogJob: Job? = null

    private val queueLock = Any()
    private val gattQueue = ArrayDeque<() -> Boolean>()
    private var operationInFlight = false

    @Volatile
    private var userInitiatedDisconnect = false

    @Volatile
    private var reconnectAttempt = 0

    private var targetAddress: String = ""
    private var targetName: String = ""

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

    fun connect(address: String, name: String = "") {
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

        stopScan()
        reconnectJob?.cancel()
        cleanupGatt()

        targetAddress = cleanAddress
        targetName = name.ifBlank { cleanAddress }
        userInitiatedDisconnect = false
        reconnectAttempt = 0

        openGattConnection()
    }

    @SuppressLint("MissingPermission")
    private fun openGattConnection() {
        if (!hasBluetoothConnectPermission()) return
        val adapter = bluetoothAdapter ?: return

        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTING,
            deviceName = targetName,
            deviceAddress = targetAddress,
            bpm = 0,
            isVitalsValid = false,
            statusMessage = "BLE 연결 중... ($targetName)"
        )
        logMsg("연결 시도: $targetName ($targetAddress)")

        try {
            val device = adapter.getRemoteDevice(targetAddress)
            if (device.bondState == BluetoothDevice.BOND_NONE && hasBluetoothConnectPermission()) {
                logMsg("OS 블루투스 페어링(Bonding) 요청: $targetAddress")
                try {
                    device.createBond()
                } catch (e: Exception) {
                    Log.w(TAG, "createBond error: ${e.message}")
                }
            }
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

        cleanupGatt()
        _gatewayState.value = MiBandGatewayState(
            status = BleConnectionStatus.DISCONNECTED,
            deviceName = targetName,
            deviceAddress = targetAddress,
            statusMessage = "연결 해제됨"
        )
        logMsg("BLE 연결 해제 완료")
    }

    @SuppressLint("MissingPermission")
    private fun cleanupGatt() {
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
                    logMsg("GATT 명령 응답 타임아웃 — 다음 진행")
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
                if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
                    scheduleReconnect()
                } else {
                    _gatewayState.value = _gatewayState.value.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        bpm = 0,
                        isVitalsValid = false,
                        statusMessage = "연결 끊김 (status=$status)"
                    )
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("GATT 연결 오류 (status=$status, newState=$newState)")
                cleanupGatt()
                if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
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

            subscribeVitalsServices(gatt)
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
            handleCharacteristicChanged(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value ?: return
                handleCharacteristicChanged(characteristic, value)
            }
        }
    }

    private fun subscribeVitalsServices(gatt: BluetoothGatt) {
        val hrService = gatt.getService(MiBandProtocol.SERVICE_HEART_RATE)
        val hrChar = hrService?.getCharacteristic(MiBandProtocol.CHAR_HEART_RATE_MEASUREMENT)

        if (hrChar != null) {
            enableNotification(gatt, hrChar)
            logMsg("표준 BLE Heart Rate(0x2A37) Notification 구독 성공")
        } else {
            logMsg("경고: Heart Rate Service(0x180D) 특성을 찾을 수 없습니다.")
        }

        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTED_STREAMING,
            statusMessage = "BLE 실시간 심박수 수신 대기 중"
        )
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val now = System.currentTimeMillis()
        if (characteristic.uuid == MiBandProtocol.CHAR_HEART_RATE_MEASUREMENT) {
            val bpm = MiBandProtocol.parseHeartRateMeasurement(value) ?: return
            _gatewayState.value = _gatewayState.value.copy(
                status = BleConnectionStatus.CONNECTED_STREAMING,
                bpm = bpm,
                isVitalsValid = true,
                lastUpdatedAt = now,
                statusMessage = "수신 중: $bpm BPM"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasBluetoothConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(MiBandProtocol.DESCRIPTOR_CCCD)
        if (cccd != null) {
            val props = characteristic.properties
            val valToSet = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            enqueueGattOperation {
                writeDescriptorRaw(gatt, cccd, valToSet)
            }
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
        descriptor.value = value
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
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
            isVitalsValid = false,
            statusMessage = "연결 끊김 — ${delayMs / 1000}초 후 재연결 시도..."
        )
        logMsg("${delayMs / 1000}초 후 BLE 자동 재연결 예정 (시도 #$reconnectAttempt)")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
                openGattConnection()
            }
        }
    }

    fun getLatestVitals(): VitalsData {
        val state = _gatewayState.value
        val isFresh = state.bpm > 0 &&
            (System.currentTimeMillis() - state.lastUpdatedAt) < VITALS_FRESHNESS_TIMEOUT_MS
        return VitalsData(
            heartRate = if (isFresh) state.bpm else 0,
            isValid = isFresh,
            updatedAt = state.lastUpdatedAt,
            source = state.deviceName.ifBlank { state.deviceAddress }
        )
    }
}
