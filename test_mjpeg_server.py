#!/usr/bin/env python3
"""Simple HTTP MJPEG server for testing.

Serves local camera as MJPEG stream on http://localhost:8080/stream
"""

import argparse
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import cv2
import numpy as np


class MJPEGHandler(BaseHTTPRequestHandler):
    camera = None

    def do_GET(self):
        if self.path == "/stream":
            self.send_response(200)
            self.send_header(
                "Content-Type",
                "multipart/x-mixed-replace; boundary=--frame",
            )
            self.end_headers()
            try:
                while True:
                    ret, frame = self.camera.read()
                    if not ret:
                        break
                    _, buffer = cv2.imencode(".jpg", frame)
                    self.wfile.write(b"--frame\r\n")
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(b"Content-Length: " + str(len(buffer)).encode() + b"\r\n\r\n")
                    self.wfile.write(buffer)
                    self.wfile.write(b"\r\n")
                    time.sleep(0.01)
            except Exception as e:
                print(f"Client disconnected: {e}")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass


def run_server(camera_source=0, port=8080):
    cap = cv2.VideoCapture(camera_source)
    if not cap.isOpened():
        print(f"Failed to open camera: {camera_source}")
        return

    MJPEGHandler.camera = cap

    server = HTTPServer(("0.0.0.0", port), MJPEGHandler)
    print(f"MJPEG server running on http://127.0.0.1:{port}/stream")
    print("Press Ctrl+C to stop")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
    finally:
        cap.release()
        server.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Simple HTTP MJPEG server")
    parser.add_argument(
        "--source",
        type=int,
        default=0,
        help="Camera index (default: 0)",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8080,
        help="Server port (default: 8080)",
    )
    args = parser.parse_args()
    run_server(args.source, args.port)
