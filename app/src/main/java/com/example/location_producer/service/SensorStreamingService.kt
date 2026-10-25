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

        private val _serviceStatus = MutableStateFlow(ServiceStatus())
        val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus.asStateFlow()

        private val _mqttState = MutableStateFlow(MqttState())
        val mqttState: StateFlow<MqttState> = _mqttState.asStateFlow()

        fun addLog(msg: String) {
            val currentLogs = _serviceStatus.value.logs.takeLast(49).toMutableList()
            currentLogs.add(msg)
            _serviceStatus.value = _serviceStatus.value.copy(logs = currentLogs)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var sensorCollector: SensorCollector
    private lateinit var gpsCollector: GpsCollector
    private lateinit var mqttStreamManager: MqttStreamManager

    private var streamingJob: Job? = null
    private var sentPacketCounter: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SensorStreamingService onCreate")
        createNotificationChannel()

        sensorCollector = SensorCollector(this)
        gpsCollector = GpsCollector(this)
        mqttStreamManager = MqttStreamManager()

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
                val brokerUrl = intent.getStringExtra(EXTRA_BROKER_URL) ?: "wss://mqtt.gnfire.opik.net/"
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: "RESCUER_01"
                startStreamingService(brokerUrl, deviceId)
            }
            ACTION_STOP -> {
                stopStreamingService()
            }
        }

        return START_STICKY
    }

    private fun startStreamingService(brokerUrl: String, deviceId: String) {
        val notification = buildNotification("Initializing streaming service...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        sensorCollector.start()
        gpsCollector.startLocationUpdates(1000L)
        mqttStreamManager.connect(brokerUrl, deviceId)

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
            Log.d(TAG, "Publishing loop started for topic: $topic")

            // Sensor streaming loop running at ~20ms - 50ms interval (20Hz - 50Hz)
            while (isActive) {
                val imu = sensorCollector.getLatestImu()
                val barometer = sensorCollector.getLatestBarometer()
                val gps = gpsCollector.getLatestGps()

                val payload = SensorPayload(
                    deviceId = deviceId,
                    timestamp = System.currentTimeMillis(),
                    gps = gps,
                    imu = imu,
                    barometer = barometer
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

        sensorCollector.stop()
        gpsCollector.stopLocationUpdates()
        mqttStreamManager.disconnect()

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
