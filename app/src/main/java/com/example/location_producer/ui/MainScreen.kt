package com.example.location_producer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.location_producer.ble.BleConnectionStatus
import com.example.location_producer.model.GpsData
import com.example.location_producer.model.ImuData
import com.example.location_producer.model.VitalsData
import com.example.location_producer.mqtt.MqttConnectionStatus
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    hasLocationPermission: Boolean,
    hasBluetoothPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBluetoothPermissions: () -> Unit
) {
    val brokerUrl by viewModel.brokerUrl.collectAsState()
    val deviceId by viewModel.deviceId.collectAsState()
    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val mqttState by viewModel.mqttState.collectAsState()
    val miBandAddress by viewModel.miBandAddress.collectAsState()
    val miBandName by viewModel.miBandName.collectAsState()
    val miBandState by viewModel.miBandState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    var showGuideBanner by remember { mutableStateOf(false) }

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
                            label = { Text("Producer ID (Rescuer ID)") },
                            placeholder = { Text("e.g. RESCUER_01 or FIRE_02") },
                            supportingText = {
                                if (deviceId.trim().isEmpty()) {
                                    Text(
                                        text = "* Required: Please enter a unique ID for this device",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text("Unique device identifier for MQTT streaming")
                                }
                            },
                            isError = deviceId.trim().isEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !serviceStatus.isRunning,
                            singleLine = true
                        )
                    }
                }
            }

            // Mi Band BLE Gateway Card
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
                        val (bleLabel, isBleActive) = when (miBandState.status) {
                            BleConnectionStatus.CONNECTED_STREAMING -> "BLE Streaming" to true
                            BleConnectionStatus.CONNECTING -> "BLE Connecting" to false
                            BleConnectionStatus.ERROR -> "BLE Error" to false
                            BleConnectionStatus.DISCONNECTED -> "BLE Disconnected" to false
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mi Band BLE Gateway (Vitals)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            StatusBadge(label = bleLabel, isActive = isBleActive)
                        }

                        if (!hasBluetoothPermission) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.errorContainer,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Mi Band 검색 및 연결을 위해 블루투스 권한(BLUETOOTH_SCAN / CONNECT)이 필요합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = onRequestBluetoothPermissions,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Grant Bluetooth Permissions")
                                }
                            }
                        }

                        // Selected Device & Live Status
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val selectedSummary = if (miBandAddress.isBlank()) {
                                    "Not selected"
                                } else {
                                    "${miBandName.ifBlank { "Mi Band" }} ($miBandAddress)"
                                }
                                Text(
                                    text = "Target: $selectedSummary",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (miBandAddress.isNotBlank() && !serviceStatus.isRunning) {
                                    TextButton(onClick = { viewModel.clearSelectedMiBand() }) {
                                        Text("Clear")
                                    }
                                }
                            }

                            val bpmDisplay = if (miBandState.isVitalsValid && miBandState.bpm > 0) {
                                "${miBandState.bpm} BPM"
                            } else {
                                "-- BPM"
                            }
                            val spo2Display = if (miBandState.isVitalsValid && miBandState.spo2 > 0) {
                                "${miBandState.spo2} %"
                            } else {
                                "-- %"
                            }

                            Text(
                                text = "Live Vitals: $bpmDisplay · SpO2: $spo2Display",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (miBandState.isVitalsValid) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                            )

                            miBandState.statusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (miBandState.status == BleConnectionStatus.ERROR) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        // Paired Devices & BLE Scan buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.refreshBondedDevices() },
                                enabled = hasBluetoothPermission && !serviceStatus.isRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Paired Devices")
                            }
                            OutlinedButton(
                                onClick = { viewModel.toggleBleScan() },
                                enabled = hasBluetoothPermission && !serviceStatus.isRunning,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (isScanning) "Stop Scan" else "BLE Scan")
                            }
                        }

                        // Discovered / Paired device list
                        if (discoveredDevices.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(discoveredDevices, key = { it.address }) { dev ->
                                        val isSelected = dev.address == miBandAddress
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    }
                                                )
                                                .clickable(enabled = !serviceStatus.isRunning) {
                                                    viewModel.selectMiBandDevice(dev)
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = dev.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    text = dev.address,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 12.sp
                                                )
                                            }
                                            val tagParts = buildList {
                                                if (dev.isBonded) add("Paired")
                                                dev.rssi?.let { add("${it} dBm") }
                                            }.joinToString(" · ")
                                            if (tagParts.isNotEmpty()) {
                                                Text(
                                                    text = tagParts,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Guide Banner Toggle Button
                        TextButton(
                            onClick = { showGuideBanner = !showGuideBanner },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(if (showGuideBanner) "▲ 가이드 닫기" else "❓ Mi Band 설정 & 초단위 라이브 수신 가이드")
                        }

                        if (showGuideBanner) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "💡 Mi Band 초단위 실시간 연결 설정 가이드",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "1. Zepp Life 앱 ➔ [프로필] ➔ [Mi Smart Band 6] ➔ [활동 심박수 공유] 옵션을 켜기(ON) 해주세요.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "2. 저희 앱에서 기기를 선택 후 [Connect Mi Band]를 누릅니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    text = "3. 초단위 실시간 심박수 수신: Mi Band 화면 ➔ [운동(Workout)] ➔ [자유 훈련(Free training)]을 실행하면 1초 간격으로 실시간 심박수가 무제한 송신됩니다!",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        // Connect / Disconnect button
                        val isBleBusyOrConnected = miBandState.status == BleConnectionStatus.CONNECTING ||
                            miBandState.status == BleConnectionStatus.CONNECTED_STREAMING

                        if (isBleBusyOrConnected) {
                            OutlinedButton(
                                onClick = { viewModel.disconnectMiBand() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disconnect Mi Band")
                            }
                        } else {
                            Button(
                                onClick = { viewModel.connectMiBand() },
                                enabled = hasBluetoothPermission && miBandAddress.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Connect Mi Band")
                            }
                        }
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
                        val vitals = payload?.vitals ?: VitalsData()

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

                        // Vitals Section (Heart Rate & SpO2)
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
                                    text = "Vitals (Mi Band BLE)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (vitals.isValid) "VALID VITALS" else "NO SIGNAL / INVALID",
                                    color = if (vitals.isValid) Color(0xFF2E7D32) else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val hrBpmText = if (vitals.isValid && vitals.heartRate > 0) "${vitals.heartRate} BPM" else "-- BPM"
                            val spo2Text = if (vitals.isValid && vitals.spo2 > 0) "${vitals.spo2} %" else "-- %"
                            Text(
                                text = "Heart Rate: $hrBpmText · SpO2: $spo2Text",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val sourceText = vitals.source.ifBlank { "Not connected" }
                            Text(
                                text = "Source: $sourceText",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Operational Logs Card
            item {
                val context = LocalContext.current
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Operation Logs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row {
                                TextButton(
                                    onClick = {
                                        val logsText = serviceStatus.logs.joinToString("\n")
                                        if (logsText.isNotBlank()) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Operation Logs", logsText)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "로그가 클립보드에 복사되었습니다!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "복사할 로그가 없습니다.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Text("Copy Logs")
                                }
                                TextButton(
                                    onClick = { viewModel.clearLogs() }
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            LazyColumn {
                                items(serviceStatus.logs) { logLine ->
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
