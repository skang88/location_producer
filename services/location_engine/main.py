import os
import json
import logging
import time
from kafka import KafkaConsumer, KafkaProducer
from kafka.errors import NoBrokersAvailable
from algorithms import RescuerTracker

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] (Location-Engine) %(message)s"
)
logger = logging.getLogger(__name__)

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "kafka:9092")
INPUT_TOPIC = os.getenv("INPUT_TOPIC", "raw-sensor-data")
OUTPUT_TOPIC = os.getenv("OUTPUT_TOPIC", "rescuer-positions")
COMMANDS_TOPIC = os.getenv("COMMANDS_TOPIC", "rescuer-commands")

def init_kafka(retries=10, delay=3):
    """Initialize Kafka Consumer and Producer"""
    for attempt in range(1, retries + 1):
        try:
            logger.info(f"Connecting to Kafka at {KAFKA_BROKER} (Attempt {attempt}/{retries})...")
            consumer = KafkaConsumer(
                INPUT_TOPIC,
                COMMANDS_TOPIC,
                bootstrap_servers=KAFKA_BROKER,
                group_id="location-processing-group",
                auto_offset_reset="latest",
                value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                key_deserializer=lambda k: k.decode("utf-8") if k else None
            )
            producer = KafkaProducer(
                bootstrap_servers=KAFKA_BROKER,
                key_serializer=lambda k: k.encode("utf-8") if k else None,
                value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8")
            )
            logger.info("Kafka Consumer and Producer connected successfully.")
            return consumer, producer
        except NoBrokersAvailable:
            logger.warning(f"Kafka not ready yet. Retrying in {delay} seconds...")
            time.sleep(delay)
        except Exception as e:
            logger.error(f"Error during Kafka init: {e}")
            time.sleep(delay)
    raise RuntimeError("Failed to connect to Kafka.")

def main():
    consumer, producer = init_kafka()
    trackers = {} # device_id -> RescuerTracker
    last_log_time = {}

    logger.info(f"Listening for sensor stream on topic '{INPUT_TOPIC}'...")

    for message in consumer:
        try:
            data = message.value
            device_id = data.get("device_id", message.key or "UNKNOWN")
            timestamp = data.get("timestamp", time.time() * 1000)

            # Handle Manual Commander Actions (Delete / Calibrate)
            if message.topic == COMMANDS_TOPIC:
                action = data.get("action")
                if action == "DELETE":
                    if device_id in trackers:
                        del trackers[device_id]
                        last_log_time.pop(device_id, None)
                        logger.info(f"🗑️ Rescuer {device_id} removed from Location Engine.")
                elif action == "CALIBRATE":
                    if device_id not in trackers:
                        trackers[device_id] = RescuerTracker(device_id)
                    tracker = trackers[device_id]
                    tracker.calibrate_position(
                        lat=data.get("lat"),
                        lng=data.get("lng"),
                        rel_x=data.get("rel_x"),
                        rel_y=data.get("rel_y"),
                        floor=data.get("floor")
                    )
                    estimate = tracker.get_current_estimate()
                    producer.send(OUTPUT_TOPIC, key=device_id, value=estimate)
                    logger.info(f"🎯 Rescuer {device_id} manually calibrated: Lat={estimate.get('lat')}, Lng={estimate.get('lng')}, Floor={estimate.get('floor_str')}")
                elif action == "RESET_CALIBRATION":
                    if device_id in trackers:
                        trackers[device_id].reset_calibration()
                        logger.info(f"🔄 Rescuer {device_id} calibration reset to auto GPS")
                continue

            if device_id not in trackers:
                logger.info(f"New rescuer device registered: {device_id}")
                trackers[device_id] = RescuerTracker(device_id)
                last_log_time[device_id] = 0

            tracker = trackers[device_id]

            # 1. Update GPS
            if "gps" in data:
                tracker.update_gps(data["gps"], timestamp)

            # 2. Update Barometer (Altitude & Floor)
            if "barometer" in data:
                tracker.update_barometer(data["barometer"], timestamp)

            # 3. Update IMU
            step_info = None
            if "imu" in data:
                step_info = tracker.update_imu(data["imu"], timestamp)

            # 4. Publish computed estimate periodically or on step
            now = time.time()
            is_step = step_info and step_info.get("step_detected")
            
            # Publish when a step is detected or every 1 second
            if is_step or (now - last_log_time[device_id] >= 1.0):
                last_log_time[device_id] = now
                estimate = tracker.get_current_estimate()
                estimate["timestamp"] = timestamp
                
                producer.send(OUTPUT_TOPIC, key=device_id, value=estimate)
                
                log_tag = "👟 STEP!" if is_step else "📍 UPDATE"
                snap_tag = " [GRID_SNAP]" if estimate.get('is_grid_snapped') else ""
                v_status = estimate.get('vertical_status', 'LEVEL')
                logger.info(
                    f"{log_tag} [{device_id}] Mode: {estimate['mode']} | "
                    f"Pos: ({estimate['rel_x']}m, {estimate['rel_y']}m, Z:{estimate.get('rel_z', 0)}m | {estimate.get('floor_str', '1F')} {v_status}) | "
                    f"Heading: {estimate.get('heading_deg')}° ({estimate.get('turn_state', 'STRAIGHT')}{snap_tag}) | "
                    f"Steps: {estimate['steps']} ({estimate['distance']}m)"
                )

        except Exception as e:
            logger.error(f"Error processing sensor message: {e}", exc_info=True)

if __name__ == "__main__":
    main()
