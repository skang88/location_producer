package com.example.location_producer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.location_producer.model.GpsData
import com.example.location_producer.model.ImuData
import com.example.location_producer.mqtt.MqttConnectionStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasLocationPermission: Boolean,
    onRequestPermissions: () -> Unit
) {
    val brokerUrl by viewModel.brokerUrl.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val mqttState by viewModel.mqttState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Rescuer Location Producer",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Alert Banner if missing
            if (!hasLocationPermission) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Location Permission Required",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Sensors and GPS collection require fine location permission.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRequestPermissions,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Grant Permissions")
                            }
                        }
                    }
                }
            }

            // Connection Configuration Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "MQTT WebSocket Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = brokerUrl,
                            onValueChange = { viewModel.updateBrokerUrl(it) },
                            label = { Text("Broker URL (ws:// or wss://)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !serviceStatus.isRunning,
                            singleLine = true
                        )

                        // Quick Presets
                        Text(
                            text = "Preset URIs (Port 80 WS):",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = brokerUrl.startsWith("ws://mqtt.gnfire.opik.net"),
                                onClick = { viewModel.updateBrokerUrl("ws://mqtt.gnfire.opik.net:80/") },
                                label = { Text("Subdomain WS (80)") },
                                enabled = !serviceStatus.isRunning
                            )
                            FilterChip(
                                selected = brokerUrl.startsWith("ws://gnfire.opik.net"),
                                onClick = { viewModel.updateBrokerUrl("ws://gnfire.opik.net:80/mqtt") },
                                label = { Text("Single Domain WS (80)") },
                                enabled = !serviceStatus.isRunning
                            )
                        }

                        OutlinedTextField(
                            value = deviceId,
                            onValueChange = { viewModel.updateDeviceId(it) },
                            label = { Text("Device ID") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !serviceStatus.isRunning,
                            singleLine = true
                        )
                    }
                }
            }

            // Status Badges & Controls Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Foreground Streaming Service",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusBadge(
                                label = if (serviceStatus.isRunning) "Service RUNNING" else "Service STOPPED",
                                isActive = serviceStatus.isRunning
                            )

                            val (mqttText, isMqttActive) = when (mqttState.status) {
                                MqttConnectionStatus.CONNECTED -> "MQTT Connected" to true
                                MqttConnectionStatus.CONNECTING -> "MQTT Connecting..." to false
                                MqttConnectionStatus.DISCONNECTED -> "MQTT Disconnected" to false
                                MqttConnectionStatus.ERROR -> "MQTT Error" to false
                            }
                            StatusBadge(label = mqttText, isActive = isMqttActive)
                        }

                        if (serviceStatus.isRunning) {
                            Button(
                                onClick = { viewModel.stopService() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Stop Streaming Service")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startService() },
                                enabled = hasLocationPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Start Foreground Streaming Service")
                            }
                        }
                    }
                }
            }

            // Telemetry Preview Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Real-time Telemetry Data",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "Sent: ${serviceStatus.sentPacketsCount} pkts",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        val payload = serviceStatus.latestPayload
                        val gps = payload?.gps ?: GpsData()
                        val imu = payload?.imu ?: ImuData()
                        val baro = payload?.barometer?.pressure ?: 0.0f

                        // GPS Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "GPS Location",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (gps.isValid) "VALID GPS" else "NO FIX / INVALID",
                                    color = if (gps.isValid) Color(0xFF2E7D32) else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Lat: ${String.format(Locale.US, "%.6f", gps.lat)}, Lng: ${String.format(Locale.US, "%.6f", gps.lng)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Alt: ${String.format(Locale.US, "%.1f", gps.alt)} m, Accuracy: ${String.format(Locale.US, "%.1f", gps.accuracy)} m",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }

                        // IMU Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "IMU Sensors (~50Hz)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Accel (x, y, z): ${String.format(Locale.US, "%.2f, %.2f, %.2f", imu.accel.x, imu.accel.y, imu.accel.z)} m/s²",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Gyro  (x, y, z): ${String.format(Locale.US, "%.2f, %.2f, %.2f", imu.gyro.x, imu.gyro.y, imu.gyro.z)} rad/s",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Yaw (Azimuth): ${String.format(Locale.US, "%.1f°", imu.yaw)}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Barometer Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Barometer (Floor Estimation)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Pressure: ${String.format(Locale.US, "%.2f", baro)} hPa",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // Operational Logs Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Operation Logs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            LazyColumn {
                                items(serviceStatus.logs.takeLast(20)) { logLine ->
                                    Text(
                                        text = logLine,
                                        color = Color.Green,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(label: String, isActive: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (isActive) Color(0xFF2E7D32) else Color(0xFF757575),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
