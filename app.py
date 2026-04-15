#!/usr/bin/env python3
"""TINKLET stream receiver + MediaPipe Pose MVP.

This script receives an RTMP/MJPEG stream from TINKLET, runs MediaPipe Pose,
and overlays upper-body landmarks in real time without writing any file.
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import signal
import sys
import time
import urllib.request
from pathlib import Path
from typing import Deque, Dict, List, Optional, Tuple

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python.core.base_options import BaseOptions
from mediapipe.tasks.python.vision.core.image import Image, ImageFormat
from mediapipe.tasks.python.vision.core.vision_task_running_mode import (
    VisionTaskRunningMode,
)
from mediapipe.tasks.python.vision import pose_landmarker


UPPER_BODY_POINTS = {
    "nose": 0,
    "left_eye": 2,
    "right_eye": 5,
    "left_ear": 7,
    "right_ear": 8,
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
    "left_knee": 25,
    "right_knee": 26,
    "left_ankle": 27,
    "right_ankle": 28,
}
UPPER_BODY_EDGES = [
    ("nose", "left_eye"),
    ("nose", "right_eye"),
    ("left_eye", "left_ear"),
    ("right_eye", "right_ear"),
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_hip"),
    ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_shoulder", "left_elbow"),
    ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"),
    ("right_elbow", "right_wrist"),
    ("left_hip", "left_knee"),
    ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"),
    ("right_knee", "right_ankle"),
]
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
    "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
)
MODEL_CACHE_PATH = Path.home() / ".cache" / "tinklet" / "pose_landmarker_lite.task"


@dataclasses.dataclass
class Stats:
    frame_count: int = 0
    detected_count: int = 0
    start_time: float = dataclasses.field(default_factory=time.time)

    def average_fps(self) -> float:
        elapsed = max(time.time() - self.start_time, 1e-6)
        return self.frame_count / elapsed

    def detection_rate(self) -> float:
        if self.frame_count == 0:
            return 0.0
        return self.detected_count / self.frame_count


class GracefulStop:
    def __init__(self) -> None:
        self._stop = False
        signal.signal(signal.SIGINT, self._handler)
        signal.signal(signal.SIGTERM, self._handler)

    def _handler(self, *_: object) -> None:
        self._stop = True

    @property
    def requested(self) -> bool:
        return self._stop


def ensure_pose_model() -> Path:
    MODEL_CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    if MODEL_CACHE_PATH.exists() and MODEL_CACHE_PATH.stat().st_size > 0:
        return MODEL_CACHE_PATH

    print(f"[INFO] Downloading pose model: {MODEL_URL}")
    urllib.request.urlretrieve(MODEL_URL, MODEL_CACHE_PATH)
    return MODEL_CACHE_PATH


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run real-time pose estimation from TINKLET stream without saving data."
    )
    parser.add_argument(
        "--source",
        default="rtmp://127.0.0.1:1935/left",
        help=(
            "Stream URL (RTMP/HTTP MJPEG) or camera index like 0 "
            "(default: rtmp://127.0.0.1:1935/left)"
        ),
    )
    parser.add_argument(
        "--reconnect-delay",
        type=float,
        default=2.0,
        help="Seconds to wait before reconnect attempt (default: 2.0)",
    )
    parser.add_argument(
        "--max-failures",
        type=int,
        default=20,
        help="Consecutive read failures before reconnect (default: 20)",
    )
    parser.add_argument(
        "--min-detection-confidence",
        type=float,
        default=0.5,
        help="MediaPipe min_detection_confidence (default: 0.5)",
    )
    parser.add_argument(
        "--min-tracking-confidence",
        type=float,
        default=0.5,
        help="MediaPipe min_tracking_confidence (default: 0.5)",
    )
    parser.add_argument(
        "--model-complexity",
        type=int,
        choices=[0, 1, 2],
        default=1,
        help="MediaPipe pose model complexity (default: 1)",
    )
    parser.add_argument(
        "--ring-buffer-size",
        type=int,
        default=120,
        help="In-memory landmark history size (default: 120)",
    )
    parser.add_argument(
        "--status-interval",
        type=float,
        default=5.0,
        help="Seconds between status prints (default: 5.0)",
    )
    parser.add_argument(
        "--window-name",
        default="TINKLET Pose MVP",
        help="OpenCV window name",
    )
    return parser.parse_args()


def open_capture(source: str) -> cv2.VideoCapture:
    src: str | int
    src = int(source) if source.isdigit() else source
    cap = cv2.VideoCapture(src)
    cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
    return cap


def to_xy(
    point: mp.framework.formats.landmark_pb2.NormalizedLandmark,
    width: int,
    height: int,
) -> Tuple[int, int]:
    x = int(np.clip(point.x * width, 0, width - 1))
    y = int(np.clip(point.y * height, 0, height - 1))
    return x, y


def extract_upper_body(
    result: pose_landmarker.PoseLandmarkerResult,
    frame_w: int,
    frame_h: int,
) -> Optional[Dict[str, List[float]]]:
    if not result.pose_landmarks:
        return None

    landmarks = result.pose_landmarks[0]
    output: Dict[str, List[float]] = {}
    for name, idx in UPPER_BODY_POINTS.items():
        lm = landmarks[int(idx)]
        output[name] = [
            float(lm.x),
            float(lm.y),
            float(lm.z),
            float(getattr(lm, "visibility", 1.0)),
        ]
    return output


def draw_upper_body(
    frame: np.ndarray,
    landmarks: Dict[str, List[float]],
    visibility_threshold: float = 0.4,
) -> None:
    h, w = frame.shape[:2]

    for start_name, end_name in UPPER_BODY_EDGES:
        s = landmarks[start_name]
        e = landmarks[end_name]
        if min(s[3], e[3]) < visibility_threshold:
            continue
        sx, sy = int(s[0] * w), int(s[1] * h)
        ex, ey = int(e[0] * w), int(e[1] * h)
        cv2.line(frame, (sx, sy), (ex, ey), (80, 240, 80), 2)

    for name, point in landmarks.items():
        if point[3] < visibility_threshold:
            continue
        x, y = int(point[0] * w), int(point[1] * h)
        cv2.circle(frame, (x, y), 5, (20, 20, 255), -1)
        cv2.putText(
            frame,
            name,
            (x + 6, y - 6),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.4,
            (255, 255, 255),
            1,
            cv2.LINE_AA,
        )


def draw_status(frame: np.ndarray, stats: Stats, connected: bool) -> None:
    fps = stats.average_fps()
    rate = stats.detection_rate() * 100.0
    status = "CONNECTED" if connected else "RECONNECTING"
    cv2.rectangle(frame, (10, 10), (400, 85), (0, 0, 0), -1)
    cv2.putText(
        frame,
        f"Status: {status}",
        (20, 35),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (120, 255, 120) if connected else (0, 165, 255),
        2,
        cv2.LINE_AA,
    )
    cv2.putText(
        frame,
        f"Avg FPS: {fps:.2f}  Detection: {rate:.1f}%",
        (20, 65),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.55,
        (255, 255, 255),
        1,
        cv2.LINE_AA,
    )


def print_final_stats(stats: Stats) -> None:
    print("\n--- Session Summary ---")
    print(f"Frames processed   : {stats.frame_count}")
    print(f"Pose detected      : {stats.detected_count}")
    print(f"Average FPS        : {stats.average_fps():.2f}")
    print(f"Detection rate     : {stats.detection_rate() * 100.0:.2f}%")


def run() -> int:
    args = parse_args()
    stopper = GracefulStop()
    stats = Stats()
    frame_id = 0
    ring_buffer: Deque[Dict[str, object]] = collections.deque(maxlen=args.ring_buffer_size)

    cap = open_capture(args.source)
    if not cap.isOpened():
        print(f"[WARN] Initial stream open failed: {args.source}")

    model_path = ensure_pose_model()
    pose_options = pose_landmarker.PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(model_path)),
        running_mode=VisionTaskRunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=args.min_detection_confidence,
        min_pose_presence_confidence=args.min_detection_confidence,
        min_tracking_confidence=args.min_tracking_confidence,
        output_segmentation_masks=False,
    )
    pose = pose_landmarker.PoseLandmarker.create_from_options(pose_options)

    failures = 0
    last_status = time.time()
    connected = cap.isOpened()

    while not stopper.requested:
        if not cap.isOpened():
            connected = False
            time.sleep(args.reconnect_delay)
            cap.release()
            cap = open_capture(args.source)
            if cap.isOpened():
                failures = 0
                connected = True
                print("[INFO] Reconnected to stream")
            continue

        ok, frame = cap.read()
        if not ok or frame is None:
            failures += 1
            if failures >= args.max_failures:
                print("[WARN] Stream read failures exceeded threshold. Reconnecting...")
                cap.release()
                connected = False
                failures = 0
            continue

        failures = 0
        connected = True

        frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        mp_image = Image(image_format=ImageFormat.SRGB, data=frame_rgb)
        result = pose.detect_for_video(mp_image, int(time.time() * 1000))
        upper = extract_upper_body(result, frame.shape[1], frame.shape[0])

        stats.frame_count += 1
        frame_id += 1
        timestamp = time.time()

        if upper is not None:
            stats.detected_count += 1
            draw_upper_body(frame, upper)
            ring_buffer.append(
                {
                    "timestamp": timestamp,
                    "frame_id": frame_id,
                    "landmarks": upper,
                }
            )

        draw_status(frame, stats, connected)
        cv2.imshow(args.window_name, frame)

        if time.time() - last_status >= args.status_interval:
            print(
                f"[INFO] fps={stats.average_fps():.2f}, "
                f"detection_rate={stats.detection_rate() * 100.0:.1f}%, "
                f"buffer={len(ring_buffer)}"
            )
            last_status = time.time()

        key = cv2.waitKey(1) & 0xFF
        if key in (ord("q"), 27):
            break

    cap.release()
    pose.close()
    cv2.destroyAllWindows()
    print_final_stats(stats)
    return 0


if __name__ == "__main__":
    sys.exit(run())
