import os
import json
import logging
import time
import paho.mqtt.client as mqtt
from kafka import KafkaProducer
from kafka.errors import NoBrokersAvailable

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] (MQTT-Bridge) %(message)s"
)
logger = logging.getLogger(__name__)

MQTT_BROKER = os.getenv("MQTT_BROKER", "mosquitto")
MQTT_PORT = int(os.getenv("MQTT_PORT", 1883))
MQTT_TOPIC = os.getenv("MQTT_TOPIC", "sensors/+/data")
MQTT_STATUS_TOPIC = os.getenv("MQTT_STATUS_TOPIC", "sensors/+/status")

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "kafka:9092")
KAFKA_TOPIC = os.getenv("KAFKA_TOPIC", "raw-sensor-data")
KAFKA_STATUS_TOPIC = os.getenv("KAFKA_STATUS_TOPIC", "rescuer-status")

def create_kafka_producer(retries=10, delay=3):
    """Wait for Kafka to be ready and initialize producer"""
    for attempt in range(1, retries + 1):
        try:
            logger.info(f"Connecting to Kafka at {KAFKA_BROKER} (Attempt {attempt}/{retries})...")
            producer = KafkaProducer(
                bootstrap_servers=KAFKA_BROKER,
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                value_serializer=lambda v: json.dumps(v).encode("utf-8"),
                acks=1
            )
            logger.info("Successfully connected to Kafka.")
            return producer
        except NoBrokersAvailable:
            logger.warning(f"Kafka not ready yet. Retrying in {delay} seconds...")
            time.sleep(delay)
        except Exception as e:
            logger.error(f"Error connecting to Kafka: {e}")
            time.sleep(delay)
    raise RuntimeError("Failed to connect to Kafka broker after multiple attempts.")

producer = create_kafka_producer()

def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        logger.info(f"Connected to Mosquitto broker at {MQTT_BROKER}:{MQTT_PORT}")
        client.subscribe(MQTT_TOPIC, qos=0)
        client.subscribe(MQTT_STATUS_TOPIC, qos=1)
        logger.info(f"Subscribed to MQTT topics: {MQTT_TOPIC}, {MQTT_STATUS_TOPIC}")
    else:
        logger.error(f"Failed to connect to MQTT broker, return code: {rc}")

def normalize_sensor_payload(payload: dict, device_id: str) -> dict:
    """Normalize incoming Android/ESP32/MiBand MQTT sensor payloads."""
    if not payload.get("timestamp"):
        payload["timestamp"] = int(time.time() * 1000)

    # Normalize vitals / heart_rate from Mi Band or compatible wearable payloads
    raw_vitals = payload.get("vitals")
    raw_hr = payload.get("heart_rate")
    if isinstance(raw_vitals, dict):
        hr_val = int(raw_vitals.get("heart_rate", 0) or 0)
        payload["vitals"] = {
            "heart_rate": hr_val,
            "is_valid": bool(raw_vitals.get("is_valid", hr_val > 0)) and hr_val > 0,
            "updated_at": int(raw_vitals.get("updated_at") or payload["timestamp"]),
            "source": str(raw_vitals.get("source") or "")
        }
    elif isinstance(raw_hr, dict):
        hr_val = int(raw_hr.get("heart_rate", 0) or 0)
        payload["vitals"] = {
            "heart_rate": hr_val,
            "is_valid": bool(raw_hr.get("is_valid", hr_val > 0)) and hr_val > 0,
            "updated_at": int(raw_hr.get("updated_at") or payload["timestamp"]),
            "source": str(raw_hr.get("source") or "")
        }
    elif isinstance(raw_hr, (int, float)):
        hr_val = int(raw_hr)
        payload["vitals"] = {
            "heart_rate": hr_val,
            "is_valid": hr_val > 0,
            "updated_at": payload["timestamp"],
            "source": "ble"
        }

    return payload


def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode("utf-8"))
        if not isinstance(payload, dict):
            logger.warning(f"Ignored non-object JSON payload on topic {msg.topic}")
            return

        device_id = payload.get("device_id")
        
        # Fallback to topic name if device_id not in json (e.g., sensors/{device_id}/data)
        if not device_id:
            parts = msg.topic.split("/")
            if len(parts) >= 2:
                device_id = parts[1]
            else:
                device_id = "UNKNOWN"
            payload["device_id"] = device_id

        if msg.topic.endswith("/status"):
            payload["retained"] = bool(msg.retain)
            producer.send(KAFKA_STATUS_TOPIC, key=device_id, value=payload)
            logger.info(f"Status message forwarded [{device_id}]: {payload.get('status')} (reason={payload.get('reason')}, retained={msg.retain})")
        else:
            payload = normalize_sensor_payload(payload, device_id)
            # Publish to Kafka partitioned by device_id to preserve time-series order
            future = producer.send(KAFKA_TOPIC, key=device_id, value=payload)
        
        # Non-blocking / batching confirmation if needed
        # future.add_errback(lambda err: logger.error(f"Kafka publish error: {err}"))
    except json.JSONDecodeError:
        logger.warning(f"Invalid JSON received on topic {msg.topic}: {msg.payload[:100]}")
    except Exception as e:
        logger.error(f"Error forwarding message: {e}")

def main():
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="mqtt_to_kafka_bridge")
    client.on_connect = on_connect
    client.on_message = on_message

    logger.info(f"Connecting to MQTT broker at {MQTT_BROKER}:{MQTT_PORT}...")
    while True:
        try:
            client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
            break
        except Exception as e:
            logger.warning(f"Mosquitto broker not ready yet ({e}). Retrying in 2 seconds...")
            time.sleep(2)

    client.loop_forever()

if __name__ == "__main__":
    main()
