import os
import json
import logging
import threading
import time
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse
from kafka import KafkaConsumer
from kafka.errors import NoBrokersAvailable

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] (Dashboard) %(message)s"
)
logger = logging.getLogger(__name__)

KAFKA_BROKER = os.getenv("KAFKA_BROKER", "kafka:9092")
POSITIONS_TOPIC = os.getenv("POSITIONS_TOPIC", "rescuer-positions")
RAW_SENSOR_TOPIC = os.getenv("RAW_SENSOR_TOPIC", "raw-sensor-data")

latest_rescuers = {}
sse_clients = set()
clients_lock = threading.Lock()

def broadcast_sse(event_type: str, data: dict):
    """Broadcast event to all connected SSE browser clients"""
    with clients_lock:
        if not sse_clients:
            return
        payload = f"data: {json.dumps({'type': event_type, 'data': data})}\n\n".encode("utf-8")
        dead_clients = set()
        for client in sse_clients:
            try:
                client.wfile.write(payload)
                client.wfile.flush()
            except Exception:
                dead_clients.add(client)
        for dead in dead_clients:
            sse_clients.discard(dead)

def kafka_consumer_worker():
    """Background worker to consume Kafka topics and forward to SSE clients"""
    logger.info(f"Initializing Kafka consumer at {KAFKA_BROKER}...")
    while True:
        try:
            consumer = KafkaConsumer(
                POSITIONS_TOPIC,
                RAW_SENSOR_TOPIC,
                bootstrap_servers=KAFKA_BROKER,
                group_id="dashboard-monitoring-group",
                auto_offset_reset="latest",
                value_deserializer=lambda m: json.loads(m.decode("utf-8")),
                key_deserializer=lambda k: k.decode("utf-8") if k else None
            )
            logger.info("Kafka consumer initialized successfully in Dashboard.")
            break
        except NoBrokersAvailable:
            logger.warning("Kafka not ready yet, retrying in 3 seconds...")
            time.sleep(3)
        except Exception as e:
            logger.error(f"Error connecting to Kafka: {e}")
            time.sleep(3)

    raw_sample_counter = 0

    for msg in consumer:
        try:
            topic = msg.topic
            data = msg.value
            device_id = data.get("device_id", msg.key or "UNKNOWN")

            if topic == POSITIONS_TOPIC:
                latest_rescuers[device_id] = data
                broadcast_sse("POSITION_UPDATE", data)

            elif topic == RAW_SENSOR_TOPIC:
                raw_sample_counter += 1
                if raw_sample_counter % 5 == 0:
                    broadcast_sse("RAW_SENSOR_FEED", data)

        except Exception as e:
            logger.error(f"Error handling Kafka message: {e}")

class DashboardHandler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory="static", **kwargs)

    def do_GET(self):
        parsed = urlparse(self.path)
        
        # 0. Handle /static/ prefix redirection to static directory
        if parsed.path.startswith("/static/"):
            self.path = parsed.path[7:]
            parsed = urlparse(self.path)

        # 1. Server-Sent Events stream for real-time updates
        if parsed.path == "/events":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()

            # Send initial state snapshot
            init_payload = f"data: {json.dumps({'type': 'INIT_STATE', 'data': latest_rescuers})}\n\n".encode("utf-8")
            try:
                self.wfile.write(init_payload)
                self.wfile.flush()
            except Exception:
                return

            with clients_lock:
                sse_clients.add(self)
                logger.info(f"SSE client connected. Total clients: {len(sse_clients)}")

            # Keep connection open with heartbeat
            try:
                while True:
                    time.sleep(15)
                    self.wfile.write(b": heartbeat\n\n")
                    self.wfile.flush()
            except Exception:
                pass
            finally:
                with clients_lock:
                    sse_clients.discard(self)
                    logger.info(f"SSE client disconnected. Total clients: {len(sse_clients)}")
            return

        # 2. REST API for rescuers snapshot
        if parsed.path == "/api/rescuers":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(latest_rescuers).encode("utf-8"))
            return

        # 3. Root path serve index.html
        if parsed.path == "/":
            self.path = "/index.html"

        return super().do_GET()

    def do_HEAD(self):
        parsed = urlparse(self.path)
        if parsed.path.startswith("/static/"):
            self.path = parsed.path[7:]
            parsed = urlparse(self.path)
        if parsed.path in ["/", "/index.html"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            return
        return super().do_HEAD()

    def log_message(self, format, *args):
        # Suppress verbose access log for SSE heartbeats
        if "heartbeat" not in str(args):
            logger.debug("%s - - [%s] %s" % (self.client_address[0], self.log_date_time_string(), format % args))

def main():
    # Start Kafka thread
    t = threading.Thread(target=kafka_consumer_worker, daemon=True)
    t.start()

    server = ThreadingHTTPServer(("0.0.0.0", 8080), DashboardHandler)
    logger.info("Dashboard HTTP & SSE server running on port 8080...")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.server_close()

if __name__ == "__main__":
    main()
