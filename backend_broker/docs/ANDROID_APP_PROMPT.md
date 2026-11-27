# 📱 대원 센서 스트리밍 안드로이드 앱 개발 프롬프트

> 아래 내용을 AI 코딩 모델(Claude, ChatGPT, Cursor 등)에 그대로 복사해서 전달하면, 백엔드와 100% 호환되는 안드로이드 센서 스트리밍 클라이언트 앱 프로젝트 전체 코드를 바로 생성할 수 있습니다.

---

```markdown
당신은 안드로이드 센서 처리 및 실시간 IoT 네트워킹 전문 시니어 안드로이드 엔지니어입니다.
소방관/구조대원의 실시간 위치 추적(ZUPT/PDR) 및 안전 모니터링을 위해 스마트폰의 센서 데이터를 수집하여 MQTT over WebSocket(WSS)으로 백엔드에 스트리밍하는 안드로이드 앱을 개발하려고 합니다.

다음 요구사항과 아키텍처 가이드라인에 맞추어 전체 동작 가능한 Kotlin 프로젝트 코드를 작성해 주세요.

---

### 1. 핵심 요구사항 및 제약사항

1. **통신 프로토콜**:
   - VM 방화벽 문제로 일반 TCP(1883) 포트가 차단되어 있으므로, 반드시 **MQTT over WebSocket (ws:// 또는 wss://, 포트 80/443)**을 사용해야 합니다.
   - Paho MQTT v3 (`org.eclipse.paho:org.eclipse.paho.client.mqttv3`) 또는 HiveMQ MQTT Client를 사용하여 아래 주소 중 하나로 연결합니다:
     * 서브도메인 사용 시: `wss://mqtt.gnfire.opik.net/` (또는 `ws://mqtt.gnfire.opik.net/`)
     * 단일 도메인 사용 시: `wss://gnfire.opik.net/mqtt` (또는 `ws://gnfire.opik.net/mqtt`)

2. **백그라운드 지속 동작 (필수)**:
   - 대원이 스마트폰 화면을 끄거나 주머니에 넣어도 센서 수집 및 스트리밍이 절대 중단되지 않아야 합니다.
   - Android 14+ 대응: `FOREGROUND_SERVICE` 및 `FOREGROUND_SERVICE_LOCATION` (또는 `SPECIAL_USE`) 권한과 지속 알림(Ongoing Notification), `PARTIAL_WAKE_LOCK`을 적용한 Foreground Service로 구현해 주세요.

3. **센서 수집 주기 및 스펙**:
   - 수집 주기: `SensorManager.SENSOR_DELAY_GAME` (약 20ms ~ 50Hz)
   - 가속도계 (`Sensor.TYPE_ACCELEROMETER`): x, y, z
   - 자이로스코프 (`Sensor.TYPE_GYROSCOPE`): x, y, z
   - 방위각/자세 (`Sensor.TYPE_ROTATION_VECTOR`): `SensorManager.getRotationMatrixFromVector` 및 `SensorManager.getOrientation`을 통해 360도 방위각 `yaw` 산출
   - 기압계 (`Sensor.TYPE_PRESSURE`): hPa (층수 판정용)
   - GPS (`FusedLocationProviderClient`): Priority HIGH_ACCURACY (위도, 경도, 고도, accuracy, GPS 유효성 판단)

4. **MQTT 발행 스펙**:
   - 토픽: `sensors/{DEVICE_ID}/data` (예: `sensors/RESCUER_01/data`)
   - QoS: 0 (네트워크 오버헤드 최소화)
   - 메시지 형식: JSON

---

### 2. 백엔드 수신 JSON 페이로드 스키마

반드시 아래 JSON 필드 구조와 정확히 일치해야 합니다:

```json
{
  "device_id": "RESCUER_01",
  "timestamp": 1729822420123,
  "gps": {
    "lat": 37.497912,
    "lng": 127.027631,
    "alt": 35.2,
    "accuracy": 4.5,
    "is_valid": true
  },
  "imu": {
    "accel": {
      "x": 0.12,
      "y": 9.81,
      "z": 0.54
    },
    "gyro": {
      "x": 0.01,
      "y": -0.02,
      "z": 0.05
    },
    "yaw": 90.0
  },
  "barometer": {
    "pressure": 1013.25
  }
}
```
* GPS가 실내로 진입하여 끊기거나 수신 불가일 때는 `"is_valid": false`, `"accuracy": 999.0`으로 전송해야 백엔드 연산 엔진이 PDR 실내 추정 모드로 자동 전환합니다.

---

### 3. 사용자 인터페이스 (Jetpack Compose UI)

간결하고 직관적인 현장용 UI:
- **연결 설정**: MQTT WSS 브로커 URL 입력창 (기본값: `wss://mqtt.yourdomain.com/mqtt`), 대원 ID 입력창 (`RESCUER_01`)
- **원터치 스트리밍 제어**: [스트리밍 시작] / [스트리밍 중지] 토글 버튼 (시작 시 Foreground Service 기동)
- **실시간 상태 대시보드**:
  - 통신 연결 상태 (🟢 연결됨 / 🔴 끊김)
  - GPS 상태 (위경도 수신 상태 및 정밀도 오차)
  - 가속도 크기($\sqrt{x^2+y^2+z^2}$) 및 방위각($^\circ$) 실시간 표시
  - 전송 패킷 카운터

---

### 4. 산출물 요청

1. `build.gradle.kts` (필요한 의존성: Paho MQTT, Google Play Services Location, Coroutines, Compose 등)
2. `AndroidManifest.xml` (필요 권한 선언: INTERNET, FOREGROUND_SERVICE, ACCESS_FINE_LOCATION, WAKE_LOCK, HIGH_SAMPLING_RATE_SENSORS 등)
3. `SensorStreamService.kt` (센서 및 GPS 수집, MQTT WSS 연결 및 백그라운드 전송 담당 Foreground Service)
4. `MainActivity.kt` & Compose UI 화면
```
