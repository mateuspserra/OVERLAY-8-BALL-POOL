#!/usr/bin/env python3
"""
Small YOLO HTTP backend compatible with the Android app generic_json provider.

It accepts:
    {"image": "<base64-jpeg>"} or {"imageBase64": "<base64-jpeg>"}

It returns:
    {"detections": [{"className": "...", "confidence": 0.9, "x": ..., ...}]}
"""

from __future__ import annotations

import argparse
import base64
import json
import tempfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a local YOLO detection server.")
    parser.add_argument("--model", required=True, help="Path to trained YOLO weights, e.g. runs/detect/train/weights/best.pt")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--conf", type=float, default=0.25)
    parser.add_argument("--iou", type=float, default=0.50)
    parser.add_argument("--device", default=None, help="Ultralytics device, e.g. 0 or cpu.")
    return parser.parse_args()


def load_yolo(model_path: str) -> Any:
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise SystemExit(
            "Ultralytics is not installed. Run: python -m pip install -r requirements-yolo.txt"
        ) from exc

    path = Path(model_path)
    if not path.exists():
        raise SystemExit(f"Model file not found: {path}")
    return YOLO(str(path))


class DetectionHandler(BaseHTTPRequestHandler):
    model: Any = None
    imgsz: int = 960
    conf: float = 0.25
    iou: float = 0.50
    device: str | None = None

    def do_GET(self) -> None:
        if self.path == "/health":
            self.write_json({"ok": True})
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if self.path not in {"/detect", "/"}:
            self.send_error(404)
            return

        try:
            payload = self.read_json()
            image_bytes = decode_image(payload)
            started = time.perf_counter()
            detections = self.detect(image_bytes)
            latency_ms = round((time.perf_counter() - started) * 1000)
            self.write_json({"detections": detections, "latencyMs": latency_ms})
        except ValueError as exc:
            self.write_json({"detections": [], "error": str(exc)}, status=400)
        except Exception as exc:
            self.write_json({"detections": [], "error": f"{type(exc).__name__}: {exc}"}, status=500)

    def read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            raise ValueError("Empty request body")
        body = self.rfile.read(length)
        try:
            value = json.loads(body.decode("utf-8"))
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise ValueError("Request body must be a JSON object")
        return value

    def detect(self, image_bytes: bytes) -> list[dict[str, float | str]]:
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
            tmp.write(image_bytes)
            image_path = tmp.name

        predict_kwargs: dict[str, Any] = {
            "source": image_path,
            "imgsz": self.imgsz,
            "conf": self.conf,
            "iou": self.iou,
            "verbose": False,
        }
        if self.device:
            predict_kwargs["device"] = self.device

        results = self.model.predict(**predict_kwargs)
        output: list[dict[str, float | str]] = []
        for result in results:
            names = result.names
            boxes = getattr(result, "boxes", None)
            if boxes is None:
                continue

            xyxy = boxes.xyxy.cpu().tolist()
            confs = boxes.conf.cpu().tolist()
            classes = boxes.cls.cpu().tolist()
            for box, confidence, class_id in zip(xyxy, confs, classes):
                x1, y1, x2, y2 = box
                class_name = names.get(int(class_id), str(int(class_id)))
                output.append(
                    {
                        "className": normalize_class_name(class_name),
                        "confidence": float(confidence),
                        "x": float(x1),
                        "y": float(y1),
                        "width": float(max(0.0, x2 - x1)),
                        "height": float(max(0.0, y2 - y1)),
                    }
                )
        return output

    def write_json(self, payload: dict[str, Any], status: int = 200) -> None:
        encoded = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, format: str, *args: Any) -> None:
        print(f"{self.address_string()} - {format % args}")


def decode_image(payload: dict[str, Any]) -> bytes:
    encoded = payload.get("image") or payload.get("imageBase64")
    if not isinstance(encoded, str) or not encoded.strip():
        raise ValueError("Missing image or imageBase64")

    if "," in encoded and encoded.lstrip().startswith("data:"):
        encoded = encoded.split(",", 1)[1]

    try:
        return base64.b64decode(encoded, validate=True)
    except Exception as exc:
        raise ValueError("Invalid base64 image") from exc


def normalize_class_name(value: str) -> str:
    return value.strip().lower().replace(" ", "_").replace("-", "_")


def main() -> None:
    args = parse_args()
    DetectionHandler.model = load_yolo(args.model)
    DetectionHandler.imgsz = args.imgsz
    DetectionHandler.conf = args.conf
    DetectionHandler.iou = args.iou
    DetectionHandler.device = args.device

    server = ThreadingHTTPServer((args.host, args.port), DetectionHandler)
    print(f"YOLO detect server listening on http://{args.host}:{args.port}/detect")
    server.serve_forever()


if __name__ == "__main__":
    main()
