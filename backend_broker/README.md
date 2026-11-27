# Backend Broker Server (Monorepo Node)

This directory is reserved for the backend MQTT Broker consumer, ZUPT/PDR trajectory computation, and database persistence services.

## MQTT Connection Details
- **Broker URI**: `ws://mqtt.gnfire.opik.net:80/`
- **Telemetry Data Topic**: `sensors/{device_id}/data`
- **Lifecycle Status Topic**: `sensors/{device_id}/status`
- **Schema Reference**: See `../docs/mqtt_schema.json`

## Suggested Tech Stack
- **Node.js / Express / Aedes** or **Python / FastAPI / paho-mqtt**
- **InfluxDB / PostgreSQL (TimescaleDB)** for time-series trajectory storage
