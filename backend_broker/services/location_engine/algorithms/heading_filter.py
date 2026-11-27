import math
import time
from typing import Optional, Tuple, Dict, Any

def normalize_deg(deg: float) -> float:
    """Normalize angle in degrees to [0, 360)"""
    return deg % 360.0

def angle_diff_deg(target: float, current: float) -> float:
    """Shortest signed difference (target - current) in [-180, 180] degrees"""
    diff = (target - current + 180.0) % 360.0 - 180.0
    return diff

class HeadingStabilizer:
    """
    High-Reliability Heading Stabilizer with:
    1. Magnetic Anomaly Rejection (Kalman / Innovation Gating)
    2. Turn Detection (Differentiating straight hallway walking from turning)
    3. Building Grid Alignment / Manhattan Corridor Snap (Soft Snap to 0, 90, 180, 270)
    """

    def __init__(
        self,
        grid_baseline_deg: float = 0.0,
        snap_window_deg: float = 18.0,
        snap_alpha: float = 0.12,
        anomaly_threshold_deg: float = 25.0
    ):
        self.grid_baseline_deg = grid_baseline_deg
        self.snap_window_deg = snap_window_deg # Snap threshold around 0, 90, 180, 270 deg
        self.snap_alpha = snap_alpha           # Step-wise soft snap rate
        self.anomaly_threshold_deg = anomaly_threshold_deg

        # Filter State
        self.fused_heading_deg = 0.0
        self.is_initialized = False
        self.last_update_time: Optional[float] = None

        # Turn Detection State
        self.turn_state = "STRAIGHT" # "STRAIGHT" or "TURNING"
        self.turn_rate_dps = 0.0     # Degrees per second
        self.straight_step_count = 0
        self.last_turn_time = 0.0

        # Magnetic Anomaly State
        self.magnetic_anomaly_active = False
        self.is_grid_snapped = False
        self.nearest_axis_deg = 0.0

    def update(
        self,
        raw_yaw_deg: Optional[float],
        gyro_z_rad: float = 0.0,
        timestamp: Optional[float] = None,
        is_step: bool = False
    ) -> float:
        """
        Update heading estimate using gyro integration, raw yaw (magnetic/ROTATION_VECTOR),
        magnetic anomaly rejection, turn detection, and hallway grid snap.
        """
        now = timestamp / 1000.0 if (timestamp and timestamp > 1e10) else (timestamp or time.time())

        # First reading initialization
        if not self.is_initialized:
            if raw_yaw_deg is not None:
                self.fused_heading_deg = normalize_deg(raw_yaw_deg)
            self.last_update_time = now
            self.is_initialized = True
            return self.fused_heading_deg

        dt = 0.02 # default 50Hz (20ms)
        if self.last_update_time is not None:
            delta = now - self.last_update_time
            if 0.0 < delta < 1.0:
                dt = delta
        self.last_update_time = now

        # Convert gyro_z (rad/s around vertical axis) to degrees/s
        # Note: In standard coordinate frames, +gyro_z rotates counter-clockwise.
        # Yaw heading in navigation is clockwise from North (0° = N, 90° = E).
        # We respect device convention where yaw increases clockwise or matches gyro.
        gyro_z_dps = math.degrees(gyro_z_rad)
        self.turn_rate_dps = 0.8 * self.turn_rate_dps + 0.2 * abs(gyro_z_dps)

        # 1. Turn Detection
        # If angular rate > 12 deg/s, the user is turning or taking a corner
        if self.turn_rate_dps > 12.0 or abs(gyro_z_dps) > 15.0:
            self.turn_state = "TURNING"
            self.straight_step_count = 0
            self.last_turn_time = now
        else:
            # Settle back to STRAIGHT after 0.4s of quiet angular rate
            if (now - self.last_turn_time) > 0.4:
                self.turn_state = "STRAIGHT"

        # 2. Gyro Prediction Step
        predicted_heading = normalize_deg(self.fused_heading_deg + (gyro_z_dps * dt))

        # 3. Magnetic / Rotation Vector Fusion with Anomaly Rejection
        if raw_yaw_deg is not None:
            raw_yaw = normalize_deg(raw_yaw_deg)
            # Innovation (shortest angle difference between raw sensor and gyro prediction)
            innovation = angle_diff_deg(raw_yaw, predicted_heading)

            # Anomaly check:
            # If user is walking straight (turn rate low), but raw yaw jumped suddenly > threshold,
            # it is almost certainly a magnetic anomaly from metal doors, rebars, or electrical conduits.
            if abs(innovation) > self.anomaly_threshold_deg and self.turn_state == "STRAIGHT":
                self.magnetic_anomaly_active = True
                # Reject measurement: rely solely on gyro prediction
                self.fused_heading_deg = predicted_heading
            else:
                self.magnetic_anomaly_active = False
                # Complementary update / Kalman gain
                # Fast convergence if turning, gentle drift correction if straight
                gain = 0.15 if self.turn_state == "TURNING" else 0.04
                self.fused_heading_deg = normalize_deg(predicted_heading + (gain * innovation))
        else:
            self.fused_heading_deg = predicted_heading

        # 4. Building Corridor Grid Alignment (Manhattan Snap)
        # When walking straight along a corridor, snap smoothly to the nearest orthogonal wall axis
        self.is_grid_snapped = False
        if is_step:
            if self.turn_state == "STRAIGHT":
                self.straight_step_count += 1
                # After 2 steady straight steps, apply soft corridor alignment
                if self.straight_step_count >= 2:
                    nearest_axis, offset = self._find_nearest_grid_axis(self.fused_heading_deg)
                    self.nearest_axis_deg = nearest_axis

                    if abs(offset) <= self.snap_window_deg:
                        # Soft pull towards the corridor axis
                        snap_correction = self.snap_alpha * offset
                        self.fused_heading_deg = normalize_deg(self.fused_heading_deg + snap_correction)
                        self.is_grid_snapped = True
            else:
                self.straight_step_count = 0

        return self.fused_heading_deg

    def _find_nearest_grid_axis(self, heading_deg: float) -> Tuple[float, float]:
        """
        Find the nearest of the 4 orthogonal building axes [0, 90, 180, 270] + baseline,
        and return (nearest_axis, signed_offset)
        """
        axes = [
            normalize_deg(self.grid_baseline_deg + 0.0),
            normalize_deg(self.grid_baseline_deg + 90.0),
            normalize_deg(self.grid_baseline_deg + 180.0),
            normalize_deg(self.grid_baseline_deg + 270.0)
        ]

        best_axis = axes[0]
        min_abs_diff = 999.0
        best_diff = 0.0

        for axis in axes:
            diff = angle_diff_deg(axis, heading_deg)
            if abs(diff) < min_abs_diff:
                min_abs_diff = abs(diff)
                best_diff = diff
                best_axis = axis

        return best_axis, best_diff

    def get_status(self) -> Dict[str, Any]:
        return {
            "heading_deg": round(self.fused_heading_deg, 1),
            "turn_state": self.turn_state,
            "turn_rate_dps": round(self.turn_rate_dps, 1),
            "is_grid_snapped": self.is_grid_snapped,
            "nearest_axis_deg": round(self.nearest_axis_deg, 1),
            "magnetic_anomaly_active": self.magnetic_anomaly_active
        }
