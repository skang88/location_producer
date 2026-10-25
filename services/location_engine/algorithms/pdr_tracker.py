import math
import time
import numpy as np

class RescuerTracker:
    def __init__(self, device_id: str):
        self.device_id = device_id
        
        # State: "GPS_OUTDOOR" vs "INDOOR_DEAD_RECKONING"
        self.mode = "GPS_OUTDOOR"
        
        # Latest GPS
        self.last_gps = None
        self.anchor_gps = None # The exit GPS point when entering indoors
        
        # Local Coordinates relative to anchor GPS (in meters)
        self.rel_x = 0.0 # East (meters)
        self.rel_y = 0.0 # North (meters)
        self.rel_z = 0.0 # Height/Floor altitude (meters)
        
        # Barometer & Floor Tracking
        self.anchor_pressure = None # Reference pressure at entrance (hPa)
        self.current_pressure = None
        self.floor_height = 3.2 # Standard floor height in meters
        self.estimated_floor = 1 # 1 = 1st floor (ground floor)
        self.vertical_status = "LEVEL" # LEVEL, CLIMBING_STAIRS, DESCENDING_STAIRS
        
        # Heading (radians, 0 = North, pi/2 = East)
        self.current_heading = 0.0
        
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
        
        # GPS validity criteria (e.g. valid flag and accuracy <= 15m)
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

        self.current_pressure = pressure

        # Set reference pressure when entering indoors or on first valid reading
        if self.anchor_pressure is None or self.mode == "GPS_OUTDOOR":
            self.anchor_pressure = pressure
            self.rel_z = 0.0
            self.estimated_floor = 1
            return

        # International Standard Atmosphere (ISA) formula to compute altitude difference:
        # h = 44330 * (1 - (P / P0)^(1/5.255))
        # rel_z = h(current) - h(anchor)
        try:
            h_anchor = 44330.0 * (1.0 - math.pow(self.anchor_pressure / 1013.25, 1.0 / 5.255))
            h_curr = 44330.0 * (1.0 - math.pow(self.current_pressure / 1013.25, 1.0 / 5.255))
            dz = h_curr - h_anchor
            
            # Simple exponential moving average filter for noise reduction
            self.rel_z = 0.8 * self.rel_z + 0.2 * dz
            
            # Estimate floor level relative to entrance (1F = 0m ~ 3.2m)
            floor_idx = int(math.floor((self.rel_z + (self.floor_height / 2.0)) / self.floor_height))
            self.estimated_floor = 1 + floor_idx

            # Vertical movement status (e.g. stairs)
            if self.rel_z > 1.2:
                self.vertical_status = "CLIMBING"
            elif self.rel_z < -1.2:
                self.vertical_status = "DESCENDING"
            else:
                self.vertical_status = "LEVEL"
        except Exception:
            pass

    def update_imu(self, imu_data: dict, timestamp: float):
        """Process IMU data (Accel, Gyro) for PDR/ZUPT"""
        accel = imu_data.get("accel", {"x": 0, "y": 9.8, "z": 0})
        gyro = imu_data.get("gyro", {"x": 0, "y": 0, "z": 0})
        
        # Heading angle update:
        # If smartphone provides pre-fused orientation (yaw/heading), use it.
        # Otherwise, integrate gyro z or use device yaw.
        if "yaw" in imu_data:
            # yaw in degrees or radians
            self.current_heading = math.radians(imu_data["yaw"])
        else:
            # Simple gyro integration as fallback
            if self.last_accel_time is not None:
                dt = (timestamp - self.last_accel_time) / 1000.0 # ms to s
                if 0 < dt < 1.0:
                    self.current_heading += gyro.get("z", 0.0) * dt
                    # Normalize to -pi ~ pi
                    self.current_heading = (self.current_heading + math.pi) % (2 * math.pi) - math.pi

        self.last_accel_time = timestamp

        # Acceleration magnitude (total norm minus standard gravity)
        ax = accel.get("x", 0.0)
        ay = accel.get("y", 0.0)
        az = accel.get("z", 0.0)
        norm_a = math.sqrt(ax**2 + ay**2 + az**2)
        
        self.accel_window.append(norm_a)
        if len(self.accel_window) > self.window_size:
            self.accel_window.pop(0)

        # Zero Velocity detection (ZUPT-like condition for smartphones)
        if len(self.accel_window) == self.window_size:
            var_a = float(np.var(self.accel_window))
            self.is_stationary = bool(var_a < self.stationary_variance_threshold)
        else:
            self.is_stationary = False

        # Only accumulate dead reckoning displacement when indoors and not stationary
        step_detected = False
        step_length = 0.0

        if not self.is_stationary and len(self.accel_window) == self.window_size:
            mid = self.window_size // 2
            # Check local peak condition
            is_peak = (
                self.accel_window[mid] == max(self.accel_window) and
                self.accel_window[mid] > 11.2 # > 1.15g threshold
            )
            
            # Minimum step interval check (refractory period >= 0.35s)
            now_sec = timestamp / 1000.0 if timestamp > 1e10 else timestamp
            if is_peak and (now_sec - self.last_step_time) > 0.35:
                step_detected = True
                self.last_step_time = now_sec
                self.step_count += 1
                
                # Weinberg Step Length Estimation model:
                # L = k * (max_a - min_a)^(1/4)
                a_diff = max(self.accel_window) - min(self.accel_window)
                k = 0.45 # Calibration constant for smartphone
                step_length = max(0.4, min(1.0, k * (a_diff ** 0.25)))
                self.total_distance += step_length

                # Accumulate displacement
                # heading 0 = North (+Y), 90 deg = East (+X)
                dx = step_length * math.sin(self.current_heading)
                dy = step_length * math.cos(self.current_heading)
                self.rel_x += dx
                self.rel_y += dy

        return {
            "step_detected": step_detected,
            "step_length": step_length,
            "is_stationary": self.is_stationary
        }

    def get_current_estimate(self):
        """Convert anchor GPS + relative displacement to global coordinates"""
        if self.anchor_gps is None:
            return {
                "device_id": self.device_id,
                "mode": "WAITING_GPS_LOCK",
                "rel_x": self.rel_x,
                "rel_y": self.rel_y,
                "lat": None,
                "lng": None,
                "steps": self.step_count,
                "distance": round(self.total_distance, 2)
            }

        # Conversion: 1 degree latitude ~ 111,320m
        # 1 degree longitude ~ 111,320m * cos(lat)
        anchor_lat = self.anchor_gps["lat"]
        anchor_lng = self.anchor_gps["lng"]
        
        lat_offset = self.rel_y / 111320.0
        lng_offset = self.rel_x / (111320.0 * math.cos(math.radians(anchor_lat)))
        
        current_lat = anchor_lat + lat_offset
        current_lng = anchor_lng + lng_offset

        floor_str = f"{self.estimated_floor}F" if self.estimated_floor > 0 else f"B{abs(self.estimated_floor)+1}F"

        return {
            "device_id": self.device_id,
            "mode": self.mode,
            "anchor_gps": self.anchor_gps,
            "rel_x": round(self.rel_x, 2),
            "rel_y": round(self.rel_y, 2),
            "rel_z": round(self.rel_z, 2),
            "floor": self.estimated_floor,
            "floor_str": floor_str,
            "vertical_status": self.vertical_status,
            "lat": round(current_lat, 7),
            "lng": round(current_lng, 7),
            "heading_deg": round(math.degrees(self.current_heading), 1),
            "steps": self.step_count,
            "distance": round(self.total_distance, 2),
            "is_stationary": self.is_stationary
        }
