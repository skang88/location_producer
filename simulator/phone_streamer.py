import time
import json
import math
import random
import argparse
import paho.mqtt.client as mqtt

def generate_sensor_stream(
    broker_host="localhost",
    broker_port=1883,
    device_id="RESCUER_ALPHA",
    duration_sec=30,
    indoor_start_sec=5
):
    print(f"Connecting to MQTT broker at {broker_host}:{broker_port}...")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sim_{device_id}")
    client.connect(broker_host, broker_port, keepalive=60)
    client.loop_start()

    topic = f"sensors/{device_id}/data"
    print(f"Started streaming sensor data for '{device_id}' to topic '{topic}'...")

    # Anchor GPS (e.g., Gangnam Fire Station)
    base_lat = 37.4979
    base_lng = 127.0276
    base_alt = 35.0

    hz = 50.0  # 50 Hz (20ms interval)
    interval = 1.0 / hz
    total_ticks = int(duration_sec * hz)
    indoor_tick = int(indoor_start_sec * hz)

    heading_deg = 90.0 # Heading East initially
    step_freq = 1.8 # ~1.8 steps per second (normal walk)

    try:
        for tick in range(total_ticks):
            t_sec = tick / hz
            now_ms = int(time.time() * 1000)

            # Determine GPS state
            is_indoors = tick >= indoor_tick

            if not is_indoors:
                # Outdoor GPS valid
                gps_data = {
                    "lat": base_lat + (t_sec * 0.00001), # moving slightly
                    "lng": base_lng,
                    "alt": base_alt,
                    "accuracy": 3.5 + random.uniform(-0.5, 0.5),
                    "is_valid": True
                }
            else:
                # Indoor: GPS lost / degraded
                gps_data = {
                    "lat": base_lat + (indoor_start_sec * 0.00001),
                    "lng": base_lng,
                    "alt": base_alt,
                    "accuracy": 999.0, # degraded
                    "is_valid": False # lost
                }

            # Simulate walking IMU (vertical oscillation + step acceleration)
            phase = 2 * math.pi * step_freq * t_sec
            accel_z = 9.8 + 2.8 * math.sin(phase) + random.gauss(0, 0.15)
            accel_x = 0.5 * math.sin(phase) + random.gauss(0, 0.1)
            accel_y = 0.8 * math.cos(phase) + random.gauss(0, 0.1)

            # Turn North after 15 seconds
            if t_sec > 15.0:
                heading_deg = 0.0 # North

            payload = {
                "device_id": device_id,
                "timestamp": now_ms,
                "gps": gps_data,
                "imu": {
                    "accel": {
                        "x": round(accel_x, 3),
                        "y": round(accel_y, 3),
                        "z": round(accel_z, 3)
                    },
                    "gyro": {
                        "x": 0.0,
                        "y": 0.0,
                        "z": 0.0
                    },
                    "yaw": heading_deg
                },
                "barometer": {
                    "pressure": round(1013.25 + random.gauss(0, 0.05), 2)
                }
            }

            client.publish(topic, json.dumps(payload), qos=0)
            time.sleep(interval)

    except KeyboardInterrupt:
        print("\nStreaming stopped by user.")
    finally:
        client.loop_stop()
        client.disconnect()
        print("Disconnected from MQTT broker.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Smartphone Sensor MQTT Streamer")
    parser.add_argument("--host", default="localhost", help="MQTT Broker Host")
    parser.add_argument("--port", type=int, default=1883, help="MQTT Broker Port")
    parser.add_argument("--device", default="RESCUER_ALPHA", help="Rescuer Device ID")
    parser.add_argument("--duration", type=int, default=30, help="Simulation duration in seconds")
    parser.add_argument("--indoor", type=int, default=5, help="Seconds before entering indoors")
    args = parser.parse_args()

    generate_sensor_stream(
        broker_host=args.host,
        broker_port=args.port,
        device_id=args.device,
        duration_sec=args.duration,
        indoor_start_sec=args.indoor
    )
