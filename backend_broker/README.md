# 🚒 대원 위치추적 및 안전 모니터링 시스템 (ZUPT/PDR 스트리밍 파이프라인)

스마트폰 및 IoT 디바이스의 센서(가속도계, 자이로스코프, 지자기, 기압계, GPS) 데이터를 **MQTT + Kafka** 기반으로 실시간 수집하고, **실내 진입(GPS 단절) 시점부터 PDR(Pedestrian Dead Reckoning) 및 ZUPT 보조 알고리즘을 통해 실내 이동 경로를 추정**하는 스트리밍 아키텍처 파이프라인입니다.

---

## 🏗️ 전체 아키텍처

```
[Android SmartPhone] 
     │ (MQTT 50Hz Sensor Stream: sensors/{device_id}/data)
     ▼
[Eclipse Mosquitto (MQTT Broker)]
     │
     ▼
[MQTT-to-Kafka Bridge (Python)]
     │ (Topic: raw-sensor-data, Partition Key: device_id)
     ▼
[Apache Kafka (KRaft Broker)]
     │
     ▼
[Location Engine (Python Worker)]
     │  ├─ 1. GPS 실외/실내 전환 판정 (Fade-out / Loss 감지)
     │  ├─ 2. 마지막 유효 GPS 앵커 고정
     │  ├─ 3. IMU 기반 걸음 검출 (Peak Detection) & 보폭 추정 (Weinberg Model)
     │  └─ 4. 상대 변위 (X, Y) 및 실내 추정 위경도 매핑
     ▼
[Kafka Topic: rescuer-positions]
     │
     ▼
[Dashboard 관제 센터 (FastAPI + WebSocket + Leaflet Map)] (포트 8080)
     │
     ▼
[웹 브라우저 관제 뷰] 실시간 대원 위치 궤적 & 센서 수신 패킷 모니터링
```

---

## 📦 디렉터리 구조

```
ai-crew-team5/
├── docker-compose.yml                  # 전체 인프라 및 서비스 컨테이너 오케스트레이션
├── config/
│   └── mosquitto/
│       └── mosquitto.conf              # Mosquitto MQTT 설정
├── services/
│   ├── mqtt_bridge/                    # MQTT -> Kafka 전달 브리지
│   │   ├── Dockerfile
│   │   ├── requirements.txt
│   │   └── bridge.py
│   └── location_engine/                # GPS 감지 및 실내 PDR/ZUPT 위치 추정 엔진
│       ├── Dockerfile
│       ├── requirements.txt
│       ├── main.py
│       └── algorithms/
│           └── pdr_tracker.py
└── simulator/                          # 안드로이드 스마트폰 센서 시뮬레이터
    ├── requirements.txt
    └── phone_streamer.py
```

---

## 🚀 빠른 시작 가이드

### 1. 컨테이너 인프라 빌드 및 실행
```bash
docker compose up -d --build
```

### 2. 관제 뷰 웹 대시보드 및 MQTT 도메인 접속 주소
* **관제 모니터링 웹 콘솔**:
  - 서브도메인 접속: `http://dashboard.gnfire.opik.net/` (또는 `https://dashboard.gnfire.opik.net/`)
  - 메인 도메인 접속: `http://gnfire.opik.net/` (또는 `https://gnfire.opik.net/`)
* **안드로이드 센서 전송 MQTT over WebSocket 주소**:
  - 서브도메인 방식: `ws://mqtt.gnfire.opik.net/` (또는 SSL: `wss://mqtt.gnfire.opik.net/`)
  - 단일 도메인 패스 방식: `ws://gnfire.opik.net/mqtt` (또는 SSL: `wss://gnfire.opik.net/mqtt`)
* **주요 모니터링 기능**:
  - 🗺️ **실시간 궤적 지도**: 대원 이동 경로(초록: 실외 GPS, 주황: 실내 PDR 추정선), 진입 앵커 포인트
  - 👥 **대원 카드 현황**: 보행 걸음 수, 총 이동 거리(m), 상대 변위 좌표 (X, Y), 실시간 방위각(°)
  - ⚡ **안드로이드 센서 수신 피드**: 폰에서 MQTT로 전송되는 원본 IMU/GPS/기압계 패킷 실시간 스트림

### 3. 컨테이너 상태 및 실시간 로그 확인
```bash
# 전체 상태 확인
docker compose ps

# 위치 추정 엔진 실시간 연산 로그 확인
docker compose logs -f location_engine
```

### 3. 안드로이드 센서 시뮬레이터 실행 (테스트)
안드로이드 앱 없이도 가상 보행 데이터를 50Hz로 MQTT에 쏴서 연산 엔진을 검증할 수 있습니다.
```bash
# 로컬 가상환경 또는 호스트에서
pip install -r simulator/requirements.txt
python simulator/phone_streamer.py --device RESCUER_01 --duration 25 --indoor 5
```
