package com.example.location_producer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.location_producer.MainActivity
import com.example.location_producer.R
import com.example.location_producer.ble.BleConnectionStatus
import com.example.location_producer.ble.MiBandGateway
import com.example.location_producer.ble.MiBandGatewayState
import com.example.location_producer.location.GpsCollector
import com.example.location_producer.model.SensorPayload
import com.example.location_producer.mqtt.MqttConnectionStatus
import com.example.location_producer.mqtt.MqttState
import com.example.location_producer.mqtt.MqttStreamManager
import com.example.location_producer.sensor.SensorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ServiceStatus(
    val isRunning: Boolean = false,
    val brokerUrl: String = "",
    val deviceId: String = "",
    val sentPacketsCount: Long = 0L,
    val latestPayload: SensorPayload? = null,
    val logs: List<String> = emptyList()
)

class SensorStreamingService : Service() {

    companion object {
        private const val TAG = "SensorStreamingService"
        const val CHANNEL_ID = "sensor_streaming_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_STREAMING"
        const val ACTION_STOP = "ACTION_STOP_STREAMING"

        const val EXTRA_BROKER_URL = "EXTRA_BROKER_URL"
        const val EXTRA_DEVICE_ID = "EXTRA_DEVICE_ID"
        const val EXTRA_MIBAND_ADDRESS = "EXTRA_MIBAND_ADDRESS"
        const val EXTRA_MIBAND_NAME = "EXTRA_MIBAND_NAME"

        private val _serviceStatus = MutableStateFlow(ServiceStatus())
        val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

        private val _mqttState = MutableStateFlow(MqttState())
        val mqttState: StateFlow<MqttState> = _mqttState.asStateFlow()

        private val _miBandState = MutableStateFlow(MiBandGatewayState())
        val miBandState: StateFlow<MiBandGatewayState> = _miBandState.asStateFlow()

        fun addLog(msg: String) {
            val currentLogs = _serviceStatus.value.logs.takeLast(49).toMutableList()
            currentLogs.add(msg)
            _serviceStatus.value = _serviceStatus.value.copy(logs = currentLogs)
        }

        fun clearLogs() {
            _serviceStatus.value = _serviceStatus.value.copy(logs = emptyList())
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var sensorCollector: SensorCollector
    private lateinit var gpsCollector: GpsCollector
    private lateinit var mqttStreamManager: MqttStreamManager
    private lateinit var miBandGateway: MiBandGateway

    private var streamingJob: Job? = null
    private var sentPacketCounter: Long = 0L
    private var autoConnectedMiBand: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SensorStreamingService onCreate")
        createNotificationChannel()

        sensorCollector = SensorCollector(this)
        gpsCollector = GpsCollector(this)
        mqttStreamManager = MqttStreamManager()
        miBandGateway = MiBandGateway.getInstance(applicationContext)
        miBandGateway.onLog = { addLog("[BLE] $it") }

        serviceScope.launch {
            miBandGateway.gatewayState.collect { state ->
                _miBandState.value = state
            }
        }

        serviceScope.launch {
            mqttStreamManager.mqttState.collect { state ->
                _mqttState.value = state
                if (state.status == MqttConnectionStatus.ERROR) {
                    addLog("MQTT Error: ${state.errorMessage ?: "Unknown error"}")
                } else {
                    addLog("MQTT State: ${state.status} (${state.brokerUrl})")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_START -> {
                val brokerUrl = intent.getStringExtra(EXTRA_BROKER_URL) ?: "ws://mqtt.gnfire.opik.net:80/"
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                val miBandAddress = intent.getStringExtra(EXTRA_MIBAND_ADDRESS) ?: ""
                val miBandName = intent.getStringExtra(EXTRA_MIBAND_NAME) ?: ""
                if (deviceId.isNotEmpty()) {
                    startStreamingService(
                        brokerUrl = brokerUrl,
                        deviceId = deviceId,
                        miBandAddress = miBandAddress,
                        miBandName = miBandName
                    )
                }
            }
            ACTION_STOP -> {
                stopStreamingService()
            }
        }

        return START_STICKY
    }

    private fun startStreamingService(
        brokerUrl: String,
        deviceId: String,
        miBandAddress: String = "",
        miBandName: String = ""
    ) {
        val notification = buildNotification("Initializing streaming service...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                miBandGateway.hasBluetoothConnectPermission()
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        sensorCollector.start()
        gpsCollector.startLocationUpdates(1000L)
        mqttStreamManager.connect(brokerUrl, deviceId)

        if (miBandAddress.isNotBlank() && miBandGateway.hasBluetoothConnectPermission()) {
            val currentBle = miBandGateway.gatewayState.value
            val alreadyActiveForTarget = currentBle.deviceAddress == miBandAddress &&
                (currentBle.status == BleConnectionStatus.CONNECTING ||
                    currentBle.status == BleConnectionStatus.CONNECTED_STREAMING)
            if (!alreadyActiveForTarget) {
                miBandGateway.connect(miBandAddress, miBandName)
            }
            autoConnectedMiBand = true
        } else {
            autoConnectedMiBand = false
        }

        sentPacketCounter = 0L
        _serviceStatus.value = ServiceStatus(
            isRunning = true,
            brokerUrl = brokerUrl,
            deviceId = deviceId,
            sentPacketsCount = 0L
        )

        addLog("Service started. Device ID: $deviceId, Broker: $brokerUrl")

        streamingJob?.cancel()
        streamingJob = serviceScope.launch {
            val topic = "sensors/$deviceId/data"
            val statusTopic = "sensors/$deviceId/status"

            // Publish ONLINE status
            val onlinePayload = JSONObject().apply {
                put("device_id", deviceId)
                put("status", "ONLINE")
                put("timestamp", System.currentTimeMillis())
            }.toString()
            mqttStreamManager.publish(statusTopic, onlinePayload, qos = 1, retained = true)

            Log.d(TAG, "Publishing loop started for topic: $topic")

            // Sensor streaming loop running at ~20ms - 50ms interval (20Hz - 50Hz)
            while (isActive) {
                val imu = sensorCollector.getLatestImu()
                val barometer = sensorCollector.getLatestBarometer()
                val gps = gpsCollector.getLatestGps()
                val vitals = miBandGateway.getLatestVitals()

                val payload = SensorPayload(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    gps = gps,
                    imu = imu,
                    barometer = barometer,
                    vitals = vitals
                )

                val jsonPayload = payload.toJsonString()
                val published = mqttStreamManager.publish(topic, jsonPayload, qos = 0)

                if (published) {
                    sentPacketCounter++
                    _serviceStatus.value = _serviceStatus.value.copy(
                        sentPacketsCount = sentPacketCounter,
                        latestPayload = payload
                    )
                }

                // Sleep for ~20ms (Game delay / 50Hz sensor rate)
                delay(20)
            }
        }
    }

    private fun stopStreamingService() {
        Log.d(TAG, "Stopping streaming service")
        addLog("Stopping service...")

        streamingJob?.cancel()
        streamingJob = null

        val currentDeviceId = _serviceStatus.value.deviceId
        if (currentDeviceId.isNotEmpty()) {
            val statusTopic = "sensors/$currentDeviceId/status"
            val offlinePayload = JSONObject().apply {
                put("device_id", currentDeviceId)
                put("status", "OFFLINE")
                put("reason", "user_stopped")
                put("timestamp", System.currentTimeMillis())
            }.toString()
            mqttStreamManager.publish(statusTopic, offlinePayload, qos = 1, retained = true)
        }

        sensorCollector.stop()
        gpsCollector.stopLocationUpdates()
        mqttStreamManager.disconnect()
        if (autoConnectedMiBand || miBandGateway.gatewayState.value.status != BleConnectionStatus.DISCONNECTED) {
            miBandGateway.disconnect()
        }
        autoConnectedMiBand = false

        releaseWakeLock()

        _serviceStatus.value = _serviceStatus.value.copy(isRunning = false)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocationProducer::SensorStreamingWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L /* 10 mins or ongoing */)
            }
            Log.d(TAG, "PARTIAL_WAKE_LOCK acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "PARTIAL_WAKE_LOCK released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sensor Streaming Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time location & sensor data streaming service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SensorStreamingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Real-time Sensor Streaming")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(R.mipmap.ic_launcher, "Stop Service", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "SensorStreamingService onDestroy")
        stopStreamingService()
        serviceJob.cancel()
        super.onDestroy()
    }
}
