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

def on_message(client, userdata, msg):
    try:
        payload = json.loads(msg.payload.decode("utf-8"))
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
