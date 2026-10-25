import math
import time
from typing import Dict, Any, Optional
from .heading_filter import HeadingStabilizer
from .floor_tracker import FloorTracker

def calculate_variance(values: list) -> float:
    """Calculate sample variance using standard python"""
    if not values:
        return 0.0
    mean_val = sum(values) / len(values)
    return sum((x - mean_val) ** 2 for x in values) / len(values)

class RescuerTracker:
    def __init__(self, device_id: str, building_corridor_axis_deg: float = 0.0):
        self.device_id = device_id
        
        # Mode: "GPS_OUTDOOR" vs "INDOOR_DEAD_RECKONING"
        self.mode = "GPS_OUTDOOR"
        
        # Latest GPS
        self.last_gps = None
        self.anchor_gps = None # The exit GPS point when entering indoors
        
        # Local Coordinates relative to anchor GPS (in meters)
        self.rel_x = 0.0 # East (meters)
        self.rel_y = 0.0 # North (meters)
        
        # Submodules for Orientation & Altitude/Floor
        self.heading_stabilizer = HeadingStabilizer(
            grid_baseline_deg=building_corridor_axis_deg,
            snap_window_deg=18.0,
            snap_alpha=0.12,
            anomaly_threshold_deg=25.0
        )
        self.floor_tracker = FloorTracker(
            floor_height=3.2,
            hysteresis_threshold=1.8,
            deadband_height=0.8,
            required_sustained_steps=4
        )
        
        # PDR (Step Detection) internal state
        self.last_step_time = 0.0
        self.step_count = 0
        self.total_distance = 0.0
        self.accel_window = []
        self.window_size = 15 # ~0.3s at 50Hz
        self.last_accel_time = None
        
        # Zero-velocity (Stationary) detector
        self.is_stationary = True
        self.stationary_variance_threshold = 0.15 # m/s^2

    def update_gps(self, gps_data: dict, timestamp: float):
        """Process incoming GPS data"""
        is_valid = gps_data.get("is_valid", False)
        accuracy = gps_data.get("accuracy", 999.0)
        
        # GPS validity criteria (valid flag and accuracy <= 15m)
        if is_valid and accuracy <= 15.0:
            if self.mode == "INDOOR_DEAD_RECKONING":
                # Resumed GPS signal outdoors
                self.mode = "GPS_OUTDOOR"
                self.rel_x = 0.0
                self.rel_y = 0.0
            
            self.last_gps = {
                "lat": gps_data["lat"],
                "lng": gps_data["lng"],
                "alt": gps_data.get("alt", 0.0),
                "accuracy": accuracy,
                "timestamp": timestamp
            }
            # Continuously update anchor while outdoors
            self.anchor_gps = dict(self.last_gps)
            return True
        else:
            # GPS lost or degraded
            if self.mode == "GPS_OUTDOOR" and self.anchor_gps is not None:
                self.mode = "INDOOR_DEAD_RECKONING"
            return False

    def update_barometer(self, baro_data: dict, timestamp: float):
        """Process Barometer data to compute relative altitude and floor level"""
        pressure = baro_data.get("pressure")
        if not pressure or pressure <= 0:
            return

        # If transitioning from outdoors, anchor at entrance pressure
        if self.mode == "GPS_OUTDOOR" or self.floor_tracker.anchor_pressure is None:
            self.floor_tracker.set_anchor_pressure(pressure)
        else:
            self.floor_tracker.update_barometer(pressure, timestamp)

    def update_imu(self, imu_data: dict, timestamp: float):
        """Process IMU data (Accel, Gyro, Yaw) for PDR/ZUPT, heading stabilization, and floor detection"""
        accel = imu_data.get("accel", {"x": 0.0, "y": 9.8, "z": 0.0})
        gyro = imu_data.get("gyro", {"x": 0.0, "y": 0.0, "z": 0.0})
        
        # Parse yaw angle: from direct 'yaw' or Android ROTATION_VECTOR quaternion
        raw_yaw = imu_data.get("yaw")
        if raw_yaw is None:
            rv = imu_data.get("rotation_vector") or imu_data.get("quaternion")
            if rv:
                if isinstance(rv, dict):
                    qx, qy, qz, qw = rv.get("x", 0.0), rv.get("y", 0.0), rv.get("z", 0.0), rv.get("w", 1.0)
                elif isinstance(rv, (list, tuple)) and len(rv) >= 4:
                    qx, qy, qz, qw = rv[0], rv[1], rv[2], rv[3]
                else:
                    qx, qy, qz, qw = 0.0, 0.0, 0.0, 1.0
                siny_cosp = 2.0 * (qw * qz + qx * qy)
                cosy_cosp = 1.0 - 2.0 * (qy * qy + qz * qz)
                raw_yaw = math.degrees(math.atan2(siny_cosp, cosy_cosp)) % 360.0

        # Acceleration magnitude
        ax = accel.get("x", 0.0)
        ay = accel.get("y", 0.0)
        az = accel.get("z", 0.0)
        norm_a = math.sqrt(ax**2 + ay**2 + az**2)
        
        self.accel_window.append(norm_a)
        if len(self.accel_window) > self.window_size:
            self.accel_window.pop(0)

        # Update floor tracker with recent acceleration features
        self.floor_tracker.update_accel_features(self.accel_window, norm_a)

        # Zero Velocity detection (ZUPT-like condition for smartphones)
        if len(self.accel_window) == self.window_size:
            var_a = calculate_variance(self.accel_window)
            self.is_stationary = bool(var_a < self.stationary_variance_threshold)
        else:
            self.is_stationary = False

        # Step Detection
        step_detected = False
        step_length = 0.0

        if not self.is_stationary and len(self.accel_window) == self.window_size:
            mid = self.window_size // 2
            # Check local peak condition (> 11.2 m/s^2)
            is_peak = (
                self.accel_window[mid] == max(self.accel_window) and
                self.accel_window[mid] > 11.2
            )
            
            # Minimum step interval check (refractory period >= 0.35s)
            now_sec = timestamp / 1000.0 if timestamp > 1e10 else timestamp
            if is_peak and (now_sec - self.last_step_time) > 0.35:
                step_detected = True
                self.last_step_time = now_sec
                self.step_count += 1
                
                # Notify floor tracker of confirmed step for sustained hysteresis evaluation
                self.floor_tracker.on_step_detected()

                # Weinberg Step Length Estimation model:
                # L = k * (max_a - min_a)^(1/4)
                a_diff = max(self.accel_window) - min(self.accel_window)
                k = 0.45 # Calibration constant
                step_length = max(0.4, min(1.0, k * (a_diff ** 0.25)))
                self.total_distance += step_length

        # Update Heading Stabilizer (Turn detection + corridor grid snap + magnetic anomaly filter)
        gyro_z = gyro.get("z", 0.0)
        current_heading_deg = self.heading_stabilizer.update(
            raw_yaw_deg=raw_yaw,
            gyro_z_rad=gyro_z,
            timestamp=timestamp,
            is_step=step_detected
        )
        current_heading_rad = math.radians(current_heading_deg)

        # Accumulate displacement only if a step occurred
        if step_detected:
            # heading 0 = North (+Y), 90 deg = East (+X)
            dx = step_length * math.sin(current_heading_rad)
            dy = step_length * math.cos(current_heading_rad)
            self.rel_x += dx
            self.rel_y += dy

        return {
            "step_detected": step_detected,
            "step_length": step_length,
            "is_stationary": self.is_stationary,
            "heading_deg": current_heading_deg
        }

    def get_current_estimate(self) -> Dict[str, Any]:
        """Convert anchor GPS + relative displacement to global coordinates with floor and stabilization metrics"""
        floor_info = self.floor_tracker.get_status()
        heading_info = self.heading_stabilizer.get_status()

        if self.anchor_gps is None:
            return {
                "device_id": self.device_id,
                "mode": "WAITING_GPS_LOCK",
                "rel_x": round(self.rel_x, 2),
                "rel_y": round(self.rel_y, 2),
                "rel_z": floor_info["rel_z"],
                "floor": floor_info["floor"],
                "floor_str": floor_info["floor_str"],
                "vertical_status": floor_info["vertical_status"],
                "lat": None,
                "lng": None,
                "heading_deg": heading_info["heading_deg"],
                "steps": self.step_count,
                "distance": round(self.total_distance, 2),
                "is_stationary": self.is_stationary,
                "turn_state": heading_info["turn_state"],
                "is_grid_snapped": heading_info["is_grid_snapped"],
                "magnetic_anomaly": heading_info["magnetic_anomaly_active"]
            }

        # Global Coordinate Conversion:
        # 1 degree latitude ~ 111,320m
        # 1 degree longitude ~ 111,320m * cos(lat)
        anchor_lat = self.anchor_gps["lat"]
        anchor_lng = self.anchor_gps["lng"]
        
        lat_offset = self.rel_y / 111320.0
        lng_offset = self.rel_x / (111320.0 * math.cos(math.radians(anchor_lat)))
        
        current_lat = anchor_lat + lat_offset
        current_lng = anchor_lng + lng_offset

        return {
            "device_id": self.device_id,
            "mode": self.mode,
            "anchor_gps": self.anchor_gps,
            "rel_x": round(self.rel_x, 2),
            "rel_y": round(self.rel_y, 2),
            "rel_z": floor_info["rel_z"],
            "floor": floor_info["floor"],
            "floor_str": floor_info["floor_str"],
            "vertical_status": floor_info["vertical_status"],
            "lat": round(current_lat, 7),
            "lng": round(current_lng, 7),
            "heading_deg": heading_info["heading_deg"],
            "steps": self.step_count,
            "distance": round(self.total_distance, 2),
            "is_stationary": self.is_stationary,
            "turn_state": heading_info["turn_state"],
            "is_grid_snapped": heading_info["is_grid_snapped"],
            "magnetic_anomaly": heading_info["magnetic_anomaly_active"]
        }
