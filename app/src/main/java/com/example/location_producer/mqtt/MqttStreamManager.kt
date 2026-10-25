package com.example.location_producer.mqtt

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.URI
import java.util.UUID
import javax.net.ssl.SSLContext

enum class MqttConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class MqttState(
    val status: MqttConnectionStatus = MqttConnectionStatus.DISCONNECTED,
    val errorMessage: String? = null,
    val brokerUrl: String = ""
)

class MqttStreamManager {

    private val tag = "MqttStreamManager"

    private var mqttClient: MqttAsyncClient? = null
    private val _mqttState = MutableStateFlow(MqttState())
    val mqttState: StateFlow<MqttState> = _mqttState.asStateFlow()

    fun connect(serverUri: String, deviceId: String) {
        if (_mqttState.value.status == MqttConnectionStatus.CONNECTED ||
            _mqttState.value.status == MqttConnectionStatus.CONNECTING) {
            Log.d(tag, "Already connected or connecting.")
            return
        }

        val normalizedUri = normalizeBrokerUri(serverUri)

        try {
            _mqttState.value = MqttState(
                status = MqttConnectionStatus.CONNECTING,
                brokerUrl = normalizedUri
            )

            val clientId = "Rescuer_${deviceId}_${UUID.randomUUID().toString().take(6)}"
            Log.d(tag, "Initializing MqttAsyncClient with URI: $normalizedUri, ClientId: $clientId")

            val client = MqttAsyncClient(normalizedUri, clientId, MemoryPersistence())
            mqttClient = client

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(tag, "MQTT connected! Reconnect: $reconnect, URI: $serverURI")
                    _mqttState.value = MqttState(
                        status = MqttConnectionStatus.CONNECTED,
                        brokerUrl = normalizedUri
                    )
                }

                override fun connectionLost(cause: Throwable?) {
                    val msg = cause?.message ?: cause?.localizedMessage ?: "Connection lost"
                    Log.e(tag, "MQTT Connection lost: $msg", cause)
                    _mqttState.value = MqttState(
                        status = MqttConnectionStatus.DISCONNECTED,
                        errorMessage = msg,
                        brokerUrl = normalizedUri
                    )
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 15
                keepAliveInterval = 30
                isAutomaticReconnect = true

                if (normalizedUri.startsWith("wss://") || normalizedUri.startsWith("ssl://")) {
                    try {
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, null, null)
                        socketFactory = sslContext.socketFactory
                    } catch (e: Exception) {
                        Log.e(tag, "Error setting SSL Socket Factory: ${e.message}", e)
                    }
                }
            }

            Log.d(tag, "Connecting to MQTT broker: $normalizedUri")
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(tag, "MQTT Connect Initiated Successfully.")
                    _mqttState.value = MqttState(
                        status = MqttConnectionStatus.CONNECTED,
                        brokerUrl = normalizedUri
                    )
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val errorMsg = exception?.message ?: exception?.cause?.message ?: "Failed to connect to $normalizedUri"
                    Log.e(tag, "MQTT Connect Failed: $errorMsg", exception)
                    _mqttState.value = MqttState(
                        status = MqttConnectionStatus.ERROR,
                        errorMessage = errorMsg,
                        brokerUrl = normalizedUri
                    )
                }
            })

        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            Log.e(tag, "Exception during MQTT setup: $err", e)
            _mqttState.value = MqttState(
                status = MqttConnectionStatus.ERROR,
                errorMessage = err,
                brokerUrl = normalizedUri
            )
        }
    }

    private fun normalizeBrokerUri(uri: String): String {
        var trimmed = uri.trim()
        if (trimmed.isEmpty()) return "wss://mqtt.gnfire.opik.net:443/"

        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
            trimmed = "wss://$trimmed"
        }

        return try {
            val parsed = URI(trimmed)
            val scheme = parsed.scheme ?: "wss"
            val host = parsed.host ?: return trimmed
            val port = parsed.port
            val path = if (parsed.path.isNullOrEmpty()) "/" else parsed.path

            if (port == -1) {
                val defaultPort = if (scheme == "wss") 443 else 80
                "$scheme://$host:$defaultPort$path"
            } else {
                trimmed
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    fun publish(topic: String, jsonPayload: String, qos: Int = 0): Boolean {
        val client = mqttClient ?: return false
        if (!client.isConnected) return false

        return try {
            val message = MqttMessage(jsonPayload.toByteArray(Charsets.UTF_8)).apply {
                this.qos = qos
            }
            client.publish(topic, message)
            true
        } catch (e: MqttException) {
            Log.e(tag, "Failed to publish payload: ${e.message}", e)
            false
        }
    }

    fun disconnect() {
        try {
            mqttClient?.let { client ->
                if (client.isConnected) {
                    client.disconnect()
                }
                client.close()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error disconnecting MQTT: ${e.message}", e)
        } finally {
            mqttClient = null
            _mqttState.value = MqttState(status = MqttConnectionStatus.DISCONNECTED)
        }
    }
}
