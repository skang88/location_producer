package com.example.location_producer.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.location_producer.ble.BleConnectionStatus
import com.example.location_producer.ble.MiBandProtocol
import com.example.location_producer.model.GpsData
import com.example.location_producer.model.HeartRateData
import com.example.location_producer.model.ImuData
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
    val miBandAuthKey by viewModel.miBandAuthKey.collectAsState()
    val miBandState by viewModel.miBandState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    val zeppEmail by viewModel.zeppEmail.collectAsState()
    val zeppPassword by viewModel.zeppPassword.collectAsState()
    val isFetchingZeppKey by viewModel.isFetchingZeppKey.collectAsState()

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
                            BleConnectionStatus.AUTHENTICATING -> "Authenticating" to false
                            BleConnectionStatus.ERROR -> "BLE Error" to false
                            BleConnectionStatus.DISCONNECTED -> "BLE Disconnected" to false
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Mi Band BLE Gateway (Heart Rate)",
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

                            val modeText = if (miBandAuthKey.isNotBlank()) {
                                "Mode: Mi Band 6 Auth (AES-128 + 0x2A39)"
                            } else {
                                "Mode: Standard BLE HRS (0x180D / 0x2A37)"
                            }
                            Text(
                                text = modeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            val bpmDisplay = if (miBandState.isHrValid && miBandState.bpm > 0) {
                                "${miBandState.bpm} BPM"
                            } else {
                                "-- BPM"
                            }
                            Text(
                                text = "Live Heart Rate: $bpmDisplay",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (miBandState.isHrValid) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
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

                        // Auth Key Input
                        val isAuthKeyValid = MiBandProtocol.isValidAuthKeyInput(miBandAuthKey)
                        OutlinedTextField(
                            value = miBandAuthKey,
                            onValueChange = { viewModel.updateMiBandAuthKey(it) },
                            label = { Text("Auth Key (32-hex, optional)") },
                            placeholder = { Text("예: 0x0123456789abcdef0123456789abcdef") },
                            isError = !isAuthKeyValid,
                            supportingText = {
                                if (!isAuthKeyValid) {
                                    Text(
                                        text = "32자리 16진수(선택적 0x 접두사) 또는 빈 값이어야 합니다.",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                } else {
                                    Text("입력 시 Mi Band 6 AES 인증 후 연속 측정 / 비워두면 표준 BLE(0x180D) 구독")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !serviceStatus.isRunning,
                            singleLine = true
                        )

                        // Zepp Cloud Auto-Fetch Section
                        var showZeppLogin by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { showZeppLogin = !showZeppLogin },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text(if (showZeppLogin) "▲ Hide Zepp Login" else "▼ Zepp 계정으로 Auth Key 자동 가져오기")
                        }

                        if (showZeppLogin) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Zepp (Huami) Cloud Auth Key Fetcher",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Zepp 계정 정보로 로그인하여 서버에 등록된 최신 Mi Band Auth Key를 즉시 자동으로 불러옵니다.",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                OutlinedTextField(
                                    value = zeppEmail,
                                    onValueChange = { viewModel.updateZeppEmail(it) },
                                    label = { Text("Zepp Email / ID") },
                                    placeholder = { Text("예: user@gmail.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isFetchingZeppKey && !serviceStatus.isRunning,
                                    singleLine = true
                                )

                                OutlinedTextField(
                                    value = zeppPassword,
                                    onValueChange = { viewModel.updateZeppPassword(it) },
                                    label = { Text("Zepp Password") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isFetchingZeppKey && !serviceStatus.isRunning,
                                    singleLine = true
                                )

                                Button(
                                    onClick = { viewModel.fetchAuthKeyFromZepp() },
                                    enabled = !isFetchingZeppKey && zeppEmail.isNotBlank() && zeppPassword.isNotBlank() && !serviceStatus.isRunning,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isFetchingZeppKey) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Zepp 서버에서 최신 Key 받는 중...")
                                    } else {
                                        Text("Fetch Auth Key from Zepp Cloud")
                                    }
                                }
                            }
                        }

                        // Connect / Disconnect button
                        val isBleBusyOrConnected = miBandState.status == BleConnectionStatus.CONNECTING ||
                            miBandState.status == BleConnectionStatus.AUTHENTICATING ||
                            miBandState.status == BleConnectionStatus.CONNECTED_STREAMING

                        if (isBleBusyOrConnected) {
                            OutlinedButton(
                                onClick = { viewModel.disconnectMiBand() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disconnect Mi Band")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.connectMiBand() },
                                    enabled = hasBluetoothPermission && miBandAddress.isNotBlank() && isAuthKeyValid,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Connect")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.pairMiBand() },
                                    enabled = hasBluetoothPermission && miBandAddress.isNotBlank() && isAuthKeyValid,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Pair/Bind (0x01)")
                                }
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
                        val heartRate = payload?.heartRate ?: HeartRateData()

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

                        // Heart Rate Section
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
                                    text = "Heart Rate (Mi Band BLE)",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (heartRate.isValid) "VALID HR" else "NO SIGNAL / INVALID",
                                    color = if (heartRate.isValid) Color(0xFF2E7D32) else Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val hrBpmText = if (heartRate.isValid && heartRate.bpm > 0) "${heartRate.bpm} BPM" else "-- BPM"
                            Text(
                                text = "Rate: $hrBpmText",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val sourceText = heartRate.source.ifBlank { "Not connected" }
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
