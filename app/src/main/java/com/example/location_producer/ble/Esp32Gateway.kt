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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.location_producer.model.AccelData
import com.example.location_producer.model.BarometerData
import com.example.location_producer.model.GyroData
import com.example.location_producer.model.ImuData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Esp32GatewayState(
    val status: BleConnectionStatus = BleConnectionStatus.DISCONNECTED,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val lastRawHex: String = "",
    val lastRawText: String = "",
    val imu: ImuData = ImuData(),
    val barometer: BarometerData = BarometerData(),
    val isDataValid: Boolean = false,
    val lastUpdatedAt: Long = 0L,
    val statusMessage: String? = null
)

class Esp32Gateway private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "Esp32Gateway"
        private const val GATT_OP_TIMEOUT_MS = 2_000L
        private const val FRESHNESS_TIMEOUT_MS = 15_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(3_000L, 6_000L, 10_000L)

        @Volatile
        private var instance: Esp32Gateway? = null

        fun getInstance(context: Context): Esp32Gateway {
            return instance ?: synchronized(this) {
                instance ?: Esp32Gateway(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private val _gatewayState = MutableStateFlow(Esp32GatewayState())
    val gatewayState: StateFlow<Esp32GatewayState> = _gatewayState.asStateFlow()

    @Volatile
    var onLog: ((String) -> Unit)? = null

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

    private fun logMsg(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    fun connect(address: String, name: String = "") {
        val cleanAddress = address.trim()
        if (cleanAddress.isEmpty()) {
            _gatewayState.value = Esp32GatewayState(
                status = BleConnectionStatus.ERROR,
                statusMessage = "연결할 ESP32 기기를 선택해 주세요."
            )
            return
        }

        if (!hasBluetoothConnectPermission()) {
            _gatewayState.value = Esp32GatewayState(
                status = BleConnectionStatus.ERROR,
                deviceName = name,
                deviceAddress = cleanAddress,
                statusMessage = "BLUETOOTH_CONNECT 권한이 필요합니다."
            )
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _gatewayState.value = Esp32GatewayState(
                status = BleConnectionStatus.ERROR,
                deviceName = name,
                deviceAddress = cleanAddress,
                statusMessage = "블루투스를 켜 주세요."
            )
            return
        }

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
            isDataValid = false,
            statusMessage = "ESP32 BLE 연결 중... ($targetName)"
        )
        logMsg("ESP32 연결 시도: $targetName ($targetAddress)")

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

        cleanupGatt()
        _gatewayState.value = Esp32GatewayState(
            status = BleConnectionStatus.DISCONNECTED,
            deviceName = targetName,
            deviceAddress = targetAddress,
            statusMessage = "연결 해제됨"
        )
        logMsg("ESP32 BLE 연결 해제 완료")
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
                logMsg("ESP32 GATT 연결 성공 — 서비스 탐색 시작")
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.CONNECTING,
                    statusMessage = "GATT 서비스 탐색 중..."
                )
                if (hasBluetoothConnectPermission()) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                logMsg("ESP32 GATT 연결 종료 (status=$status)")
                cleanupGatt()
                if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
                    scheduleReconnect()
                } else {
                    _gatewayState.value = _gatewayState.value.copy(
                        status = BleConnectionStatus.DISCONNECTED,
                        isDataValid = false,
                        statusMessage = "연결 끊김 (status=$status)"
                    )
                }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("ESP32 GATT 연결 오류 (status=$status, newState=$newState)")
                cleanupGatt()
                if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logMsg("ESP32 서비스 탐색 실패 (status=$status)")
                _gatewayState.value = _gatewayState.value.copy(
                    status = BleConnectionStatus.ERROR,
                    statusMessage = "GATT 서비스 탐색 실패 (status=$status)"
                )
                return
            }

            inspectAndSubscribeServices(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            onGattOperationComplete()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
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

    private fun inspectAndSubscribeServices(gatt: BluetoothGatt) {
        val discoveredUuids = gatt.services.map { "0x" + it.uuid.toString().substring(4, 8) }
        logMsg("ESP32 GATT 서비스 발견: ${discoveredUuids.joinToString(", ")}")

        var subscribedCount = 0

        for (service in gatt.services) {
            val serviceShort = service.uuid.toString().substring(4, 8)
            for (characteristic in service.characteristics) {
                val charShort = characteristic.uuid.toString().substring(4, 8)
                val props = characteristic.properties
                val hasNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val hasIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                if (hasNotify || hasIndicate) {
                    enableNotification(gatt, characteristic)
                    subscribedCount++
                    logMsg("ESP32 알림 채널 구독: Svc=0x$serviceShort, Char=0x$charShort")
                }
            }
        }

        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTED_STREAMING,
            statusMessage = "ESP32 패킷 수신 및 스니핑 중 (채널 $subscribedCount 개)"
        )
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val now = System.currentTimeMillis()
        val shortUuid = characteristic.uuid.toString().substring(4, 8)

        val hexStr = bytesToHex(value)
        val textStr = String(value, Charsets.UTF_8).filter { it >= ' ' || it == '\n' || it == '\r' }.trim()

        logMsg("[ESP32 Sniffer 0x$shortUuid] Text: '$textStr' | Hex: $hexStr")

        var parsedImu: ImuData? = null
        var parsedBaro: BarometerData? = null

        if (textStr.startsWith("{") && textStr.endsWith("}")) {
            try {
                val json = JSONObject(textStr)
                if (json.has("imu") || json.has("accel") || json.has("ax")) {
                    val accelObj = json.optJSONObject("accel")
                    val gyroObj = json.optJSONObject("gyro")
                    val ax = (accelObj?.optDouble("x") ?: json.optDouble("ax", 0.0)).toFloat()
                    val ay = (accelObj?.optDouble("y") ?: json.optDouble("ay", 0.0)).toFloat()
                    val az = (accelObj?.optDouble("z") ?: json.optDouble("az", 0.0)).toFloat()

                    val gx = (gyroObj?.optDouble("x") ?: json.optDouble("gx", 0.0)).toFloat()
                    val gy = (gyroObj?.optDouble("y") ?: json.optDouble("gy", 0.0)).toFloat()
                    val gz = (gyroObj?.optDouble("z") ?: json.optDouble("gz", 0.0)).toFloat()

                    val yaw = json.optDouble("yaw", 0.0)

                    parsedImu = ImuData(
                        accel = AccelData(ax, ay, az),
                        gyro = GyroData(gx, gy, gz),
                        yaw = yaw
                    )
                }
                if (json.has("barometer") || json.has("pressure") || json.has("press")) {
                    val baroObj = json.optJSONObject("barometer")
                    val press = (baroObj?.optDouble("pressure") ?: json.optDouble("pressure", json.optDouble("press", 0.0))).toFloat()
                    if (press > 0f) {
                        parsedBaro = BarometerData(pressure = press)
                    }
                }
            } catch (_: Exception) {
            }
        } else if (textStr.contains(",")) {
            val tokens = textStr.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (tokens.size >= 6) {
                parsedImu = ImuData(
                    accel = AccelData(tokens[0], tokens[1], tokens[2]),
                    gyro = GyroData(tokens[3], tokens[4], tokens[5]),
                    yaw = if (tokens.size >= 7) tokens[6].toDouble() else 0.0
                )
            }
            if (tokens.size >= 7 && tokens.last() > 300f) {
                parsedBaro = BarometerData(pressure = tokens.last())
            }
        } else if (value.size >= 24) {
            try {
                val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
                val ax = buffer.float
                val ay = buffer.float
                val az = buffer.float
                val gx = buffer.float
                val gy = buffer.float
                val gz = buffer.float
                parsedImu = ImuData(
                    accel = AccelData(ax, ay, az),
                    gyro = GyroData(gx, gy, gz)
                )
                if (value.size >= 28) {
                    val press = buffer.float
                    if (press > 300f) {
                        parsedBaro = BarometerData(pressure = press)
                    }
                }
            } catch (_: Exception) {
            }
        }

        _gatewayState.value = _gatewayState.value.copy(
            status = BleConnectionStatus.CONNECTED_STREAMING,
            lastRawHex = hexStr,
            lastRawText = textStr,
            imu = parsedImu ?: _gatewayState.value.imu,
            barometer = parsedBaro ?: _gatewayState.value.barometer,
            isDataValid = true,
            lastUpdatedAt = now,
            statusMessage = "ESP32 패킷 수신 중 (0x$shortUuid)"
        )
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
            isDataValid = false,
            statusMessage = "ESP32 연결 끊김 — ${delayMs / 1000}초 후 재연결..."
        )
        logMsg("${delayMs / 1000}초 후 ESP32 BLE 자동 재연결 예정 (시도 #$reconnectAttempt)")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userInitiatedDisconnect && targetAddress.isNotEmpty()) {
                openGattConnection()
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    fun getLatestImu(): ImuData? {
        val state = _gatewayState.value
        val isFresh = state.isDataValid && (System.currentTimeMillis() - state.lastUpdatedAt) < FRESHNESS_TIMEOUT_MS
        return if (isFresh) state.imu else null
    }

    fun getLatestBarometer(): BarometerData? {
        val state = _gatewayState.value
        val isFresh = state.isDataValid && (System.currentTimeMillis() - state.lastUpdatedAt) < FRESHNESS_TIMEOUT_MS
        return if (isFresh && state.barometer.pressure > 0f) state.barometer else null
    }
}
