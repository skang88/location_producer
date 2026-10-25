import math
import time
from typing import Optional, List, Dict, Any

class FloorTracker:
    """
    Robust Barometer + Accelerometer Fusion Floor & Stair Tracker:
    - ISA Formula Altitude Estimation with Exponential Smoothing
    - Accelerometer Stair Climbing/Descending Motion Classifier (Vertical peak & impact)
    - Deadband for Door Opening / Air Draft / HVAC Noises
    - Hysteresis Floor Transition with Sustained Steps Confirmation (3~5 steps)
    """

    def __init__(
        self,
        floor_height: float = 3.2,
        hysteresis_threshold: float = 1.8,
        deadband_height: float = 0.8,
        required_sustained_steps: int = 4
    ):
        self.floor_height = floor_height
        self.hysteresis_threshold = hysteresis_threshold
        self.deadband_height = deadband_height
        self.required_sustained_steps = required_sustained_steps

        # Altitude State
        self.anchor_pressure: Optional[float] = None
        self.current_pressure: Optional[float] = None
        self.raw_altitude_m: float = 0.0
        self.filtered_altitude_m: float = 0.0
        self.last_baro_time: Optional[float] = None

        # Floor State
        self.current_floor: int = 1 # 1 = 1F (Ground entrance)
        self.snapped_z_m: float = 0.0
        self.vertical_status: str = "LEVEL" # "LEVEL", "CLIMBING", "DESCENDING"

        # Hysteresis & Sustained Step Confirmation Buffer
        self.candidate_floor: int = 1
        self.sustained_step_count: int = 0

        # Accel Stair Motion Analysis
        self.stair_motion_detected: bool = False
        self.recent_accel_peaks: List[float] = []

    def set_anchor_pressure(self, pressure_hpa: float):
        """Set reference barometric pressure at ground entrance (1F = 0.0m)"""
        self.anchor_pressure = pressure_hpa
        self.raw_altitude_m = 0.0
        self.filtered_altitude_m = 0.0
        self.snapped_z_m = 0.0
        self.current_floor = 1
        self.candidate_floor = 1
        self.sustained_step_count = 0
        self.vertical_status = "LEVEL"

    def calibrate_floor(self, floor: int):
        """
        Manually calibrate current floor and re-anchor reference pressure
        so the current ambient pressure represents this target floor.
        """
        f = int(floor)
        self.current_floor = f
        self.candidate_floor = f
        self.sustained_step_count = 0
        target_z = (f - 1) * self.floor_height
        self.snapped_z_m = target_z
        self.filtered_altitude_m = target_z
        self.raw_altitude_m = target_z
        self.vertical_status = "LEVEL"

        # Re-anchor reference pressure so that current pressure corresponds to target_z
        p_now = self.current_pressure or self.anchor_pressure or 1013.25
        try:
            # h_curr = 44330 * (1 - (P_now / 1013.25)^(1/5.255))
            # We want: h_curr - h_anchor = target_z  =>  h_anchor = h_curr - target_z
            # P_anchor = 1013.25 * (1 - h_anchor / 44330)^5.255
            h_curr = 44330.0 * (1.0 - math.pow(p_now / 1013.25, 1.0 / 5.255))
            h_anchor = h_curr - target_z
            self.anchor_pressure = 1013.25 * math.pow(1.0 - (h_anchor / 44330.0), 5.255)
        except Exception:
            pass

    def update_barometer(self, pressure_hpa: float, timestamp: Optional[float] = None):
        """Update barometric altitude using ISA formula with air draft noise rejection"""
        if not pressure_hpa or pressure_hpa <= 0:
            return

        self.current_pressure = pressure_hpa
        if self.anchor_pressure is None:
            self.set_anchor_pressure(pressure_hpa)
            return

        now = timestamp / 1000.0 if (timestamp and timestamp > 1e10) else (timestamp or time.time())
        dt = 0.1 # default 10Hz
        if self.last_baro_time is not None:
            delta = now - self.last_baro_time
            if 0.005 < delta < 3.0:
                dt = delta
        self.last_baro_time = now

        # International Standard Atmosphere (ISA) barometric altitude formula:
        # h = 44330 * (1 - (P / P0)^(1/5.255))
        try:
            h_anchor = 44330.0 * (1.0 - math.pow(self.anchor_pressure / 1013.25, 1.0 / 5.255))
            h_curr = 44330.0 * (1.0 - math.pow(pressure_hpa / 1013.25, 1.0 / 5.255))
            dz_raw = h_curr - h_anchor

            # Transient Air Draft / Door-Opening Spike Limiter:
            # Human climbing speed cannot exceed 2.0 m/s.
            # Clamp instantaneous altitude jump to reject sudden door swings
            max_rate = 2.0 # m/s
            max_step = max_rate * dt
            diff = dz_raw - self.filtered_altitude_m
            step = math.copysign(max_step, diff) if abs(diff) > max_step else diff
            
            # Smooth altitude update
            self.raw_altitude_m = dz_raw
            self.filtered_altitude_m += step * 0.85
        except Exception:
            pass

        self._update_vertical_status()

    def update_accel_features(self, accel_window: List[float], norm_a: float):
        """
        Analyze vertical acceleration and step impact characteristics for stair detection.
        Stair climbing exhibits higher vertical peak-to-peak amplitude (>= 3.2 m/s^2)
        and strong landing impact impulses.
        """
        if len(accel_window) < 5:
            self.stair_motion_detected = False
            return

        a_diff = max(accel_window) - min(accel_window)
        # Stair climbing threshold: increased vertical peak-to-peak amplitude
        self.stair_motion_detected = (a_diff >= 3.2)

        self.recent_accel_peaks.append(a_diff)
        if len(self.recent_accel_peaks) > 10:
            self.recent_accel_peaks.pop(0)

    def on_step_detected(self):
        """
        Called on every detected step to evaluate sustained hysteresis floor transition.
        Only transitions when sustained steps (>= 3~5) confirm the altitude change.
        """
        current_base_z = (self.current_floor - 1) * self.floor_height
        delta_from_base = self.filtered_altitude_m - current_base_z

        # Check Ascent Candidate (e.g. >= +1.8m above current floor)
        if delta_from_base >= self.hysteresis_threshold:
            potential_floor = self.current_floor + 1
            if potential_floor == self.candidate_floor:
                self.sustained_step_count += 1
            else:
                self.candidate_floor = potential_floor
                self.sustained_step_count = 1

            # Threshold met: Transition to next floor
            if self.sustained_step_count >= self.required_sustained_steps:
                self.current_floor = potential_floor
                self.sustained_step_count = 0
                self.candidate_floor = self.current_floor

        # Check Descent Candidate (e.g. <= -1.8m below current floor)
        elif delta_from_base <= -self.hysteresis_threshold:
            potential_floor = self.current_floor - 1
            if potential_floor == self.candidate_floor:
                self.sustained_step_count += 1
            else:
                self.candidate_floor = potential_floor
                self.sustained_step_count = 1

            # Threshold met: Transition to lower floor
            if self.sustained_step_count >= self.required_sustained_steps:
                self.current_floor = potential_floor
                self.sustained_step_count = 0
                self.candidate_floor = self.current_floor

        else:
            # Within deadband or returning towards base: reset candidate counter
            self.candidate_floor = self.current_floor
            self.sustained_step_count = 0

        self._update_vertical_status()

    def _update_vertical_status(self):
        """Update vertical movement status and 3D snapped altitude"""
        current_base_z = (self.current_floor - 1) * self.floor_height
        delta = self.filtered_altitude_m - current_base_z

        # Deadband around floor slab: within +/- 0.8m is flat floor walking
        if abs(delta) < self.deadband_height:
            self.vertical_status = "LEVEL"
            # Smoothly snap to exact floor elevation for 3D visualization
            self.snapped_z_m = 0.85 * self.snapped_z_m + 0.15 * current_base_z
        else:
            if delta >= self.deadband_height:
                self.vertical_status = "CLIMBING"
            else:
                self.vertical_status = "DESCENDING"
            # Continuous smooth height representation while climbing stairs
            self.snapped_z_m = 0.8 * self.snapped_z_m + 0.2 * self.filtered_altitude_m

    def get_status(self) -> Dict[str, Any]:
        floor_str = f"{self.current_floor}F" if self.current_floor > 0 else f"B{abs(self.current_floor)+1}F"
        return {
            "floor": self.current_floor,
            "floor_str": floor_str,
            "vertical_status": self.vertical_status,
            "rel_z": round(self.snapped_z_m, 2),
            "raw_altitude_m": round(self.raw_altitude_m, 2),
            "filtered_altitude_m": round(self.filtered_altitude_m, 2),
            "stair_motion_detected": self.stair_motion_detected,
            "sustained_steps": self.sustained_step_count
        }
