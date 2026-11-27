# Location Producer Monorepo

Rescuer location & vital signs streaming platform for emergency response operations.

## Monorepo Layout

```
location_producer/
├── android/                    # Android Kotlin App (Foreground Streaming Service)
│   └── app/                    # Production source code
├── backend_broker/             # Backend MQTT Consumer & Trajectory Processor
├── docs/                       # Shared MQTT JSON Schema & System Architecture
├── settings.gradle.kts         # Root Gradle Settings with project(":app").projectDir = file("android/app")
└── build.gradle.kts            # Root Gradle Build Configuration
```

## System Components

1. **Android App (`android/app`)**:
   - Streams GPS location, 50Hz IMU (ESP32 Foot Module or Internal), Barometer, and BLE Mi Band 6 Heart Rate over MQTT/WebSocket.
   - Foreground Service on Android 14+ with ongoing notification & Partial WakeLock.

2. **ESP32 Sensor Node (`scratch/ESP32_ZUPT_PDR_BLE.ino`)**:
   - ESP32-C6 / ESP32 BLE firmware streaming 50Hz foot-mounted ZUPT/PDR IMU data over BLE Nordic UART Service.

3. **Backend Broker (`backend_broker/`)**:
   - Consumes 50Hz telemetry, computes ZUPT/PDR trajectory, and serves real-time dashboards.

## Quick Build & Test

```bash
./gradlew testDebugUnitTest assembleDebug
```
