#!/usr/bin/env python3
"""
Train and compare YOLO models on the same Roboflow/Ultralytics dataset.

Example:
    python tools/yolo_model_ab_test.py --dataset-zip .v3-v1.yolov8.zip --train
"""

from __future__ import annotations

import argparse
import ast
import csv
import json
import shutil
import statistics
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


DEFAULT_MODELS = (
    "yolov8n=yolov8n.pt",
    "yolo26n=yolo26n.pt",
)
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


@dataclass(frozen=True)
class ModelSpec:
    name: str
    weights: str


@dataclass
class EvalSummary:
    name: str
    weights: str
    trained_weights: str
    split: str
    imgsz: int
    mAP50: float | None
    mAP50_95: float | None
    precision: float | None
    recall: float | None
    preprocess_ms: float | None
    inference_ms: float | None
    postprocess_ms: float | None
    mean_predict_ms: float | None
    p95_predict_ms: float | None
    images: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare YOLOv8 and YOLO26 on the same 8 Ball Pool dataset."
    )
    parser.add_argument(
        "--dataset-zip",
        type=Path,
        default=Path(".v3-v1.yolov8.zip"),
        help="Roboflow YOLOv8 zip. Default: .v3-v1.yolov8.zip",
    )
    parser.add_argument(
        "--dataset-dir",
        type=Path,
        default=None,
        help="Already extracted YOLO dataset directory. Overrides --dataset-zip.",
    )
    parser.add_argument(
        "--work-dir",
        type=Path,
        default=Path("build/yolo_ab_test"),
        help="Directory for extracted data, runs, and reports.",
    )
    parser.add_argument(
        "--model",
        dest="models",
        action="append",
        default=None,
        help="Model spec as name=weights. Can be repeated.",
    )
    parser.add_argument(
        "--train",
        action="store_true",
        help="Fine-tune each model before evaluation. Recommended for YOLO26 comparison.",
    )
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=960)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--device", default=None, help="Ultralytics device, e.g. 0, cpu.")
    parser.add_argument("--split", default="test", choices=("train", "val", "test"))
    parser.add_argument("--conf", type=float, default=0.35)
    parser.add_argument("--iou", type=float, default=0.50)
    parser.add_argument(
        "--predict-limit",
        type=int,
        default=80,
        help="Number of images used for latency smoke test.",
    )
    parser.add_argument(
        "--force-prepare",
        action="store_true",
        help="Re-extract dataset even when the prepared copy already exists.",
    )
    parser.add_argument(
        "--prepare-only",
        action="store_true",
        help="Only extract/normalize the dataset and print split counts.",
    )
    return parser.parse_args()


def parse_models(model_args: Iterable[str] | None) -> list[ModelSpec]:
    specs = list(model_args or DEFAULT_MODELS)
    parsed: list[ModelSpec] = []
    for spec in specs:
        if "=" not in spec:
            raise ValueError(f"Invalid --model '{spec}'. Use name=weights.")
        name, weights = spec.split("=", 1)
        name = name.strip()
        weights = weights.strip()
        if not name or not weights:
            raise ValueError(f"Invalid --model '{spec}'. Use name=weights.")
        parsed.append(ModelSpec(name=name, weights=weights))
    return parsed


def prepare_dataset(args: argparse.Namespace) -> Path:
    if args.dataset_dir is not None:
        dataset_dir = args.dataset_dir.resolve()
        if not dataset_dir.exists():
            raise FileNotFoundError(f"Dataset directory not found: {dataset_dir}")
        return normalize_dataset_yaml(dataset_dir, args.work_dir)

    if not args.dataset_zip.exists():
        raise FileNotFoundError(f"Dataset zip not found: {args.dataset_zip}")

    dataset_dir = args.work_dir / "dataset"
    if args.force_prepare and dataset_dir.exists():
        shutil.rmtree(dataset_dir)
    if not dataset_dir.exists():
        dataset_dir.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.dataset_zip) as archive:
            archive.extractall(dataset_dir)
    return normalize_dataset_yaml(dataset_dir, args.work_dir)


def normalize_dataset_yaml(dataset_dir: Path, work_dir: Path) -> Path:
    source_yaml = dataset_dir / "data.yaml"
    if not source_yaml.exists():
        raise FileNotFoundError(f"data.yaml not found in {dataset_dir}")

    data = load_simple_data_yaml(source_yaml)
    normalized = dict(data)
    for split in ("train", "val", "test"):
        folder = "valid" if split == "val" else split
        if (dataset_dir / folder / "images").exists():
            normalized[split] = str((dataset_dir / folder / "images").resolve())

    output_yaml = work_dir / "data.normalized.yaml"
    output_yaml.parent.mkdir(parents=True, exist_ok=True)
    write_simple_data_yaml(output_yaml, normalized)
    return output_yaml


def train_model(spec: ModelSpec, data_yaml: Path, args: argparse.Namespace) -> Path:
    YOLO = load_ultralytics_yolo()
    project = args.work_dir / "runs" / "train"
    model = YOLO(spec.weights)
    train_kwargs = {
        "data": str(data_yaml),
        "imgsz": args.imgsz,
        "epochs": args.epochs,
        "batch": args.batch,
        "seed": args.seed,
        "project": str(project),
        "name": spec.name,
        "exist_ok": True,
    }
    if args.device:
        train_kwargs["device"] = args.device
    model.train(**train_kwargs)

    best = project / spec.name / "weights" / "best.pt"
    if not best.exists():
        raise FileNotFoundError(f"Training finished but best.pt was not found: {best}")
    return best


def evaluate_model(
    spec: ModelSpec,
    weights: Path,
    data_yaml: Path,
    args: argparse.Namespace,
) -> EvalSummary:
    YOLO = load_ultralytics_yolo()
    model = YOLO(str(weights))
    val_kwargs = {
        "data": str(data_yaml),
        "split": args.split,
        "imgsz": args.imgsz,
        "conf": args.conf,
        "iou": args.iou,
        "project": str(args.work_dir / "runs" / "val"),
        "name": spec.name,
        "exist_ok": True,
        "verbose": False,
    }
    if args.device:
        val_kwargs["device"] = args.device
    metrics = model.val(**val_kwargs)

    image_paths = list(split_images(data_yaml, args.split))[: args.predict_limit]
    predict_times = measure_predict_latency(model, image_paths, args)
    speed = getattr(metrics, "speed", {}) or {}
    box = getattr(metrics, "box", None)

    return EvalSummary(
        name=spec.name,
        weights=spec.weights,
        trained_weights=str(weights),
        split=args.split,
        imgsz=args.imgsz,
        mAP50=safe_float(getattr(box, "map50", None)),
        mAP50_95=safe_float(getattr(box, "map", None)),
        precision=safe_float(mean_or_value(getattr(box, "mp", None))),
        recall=safe_float(mean_or_value(getattr(box, "mr", None))),
        preprocess_ms=safe_float(speed.get("preprocess")),
        inference_ms=safe_float(speed.get("inference")),
        postprocess_ms=safe_float(speed.get("postprocess")),
        mean_predict_ms=safe_float(statistics.mean(predict_times) if predict_times else None),
        p95_predict_ms=safe_float(percentile(predict_times, 0.95) if predict_times else None),
        images=len(image_paths),
    )


def split_images(data_yaml: Path, split: str) -> Iterable[Path]:
    data = load_simple_data_yaml(data_yaml)
    image_dir = Path(data[split])
    return (
        path
        for path in sorted(image_dir.iterdir())
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )


def measure_predict_latency(model: object, image_paths: list[Path], args: argparse.Namespace) -> list[float]:
    times: list[float] = []
    for image_path in image_paths:
        started = time.perf_counter()
        predict_kwargs = {
            "source": str(image_path),
            "imgsz": args.imgsz,
            "conf": args.conf,
            "iou": args.iou,
            "verbose": False,
            "save": False,
        }
        if args.device:
            predict_kwargs["device"] = args.device
        model.predict(**predict_kwargs)
        times.append((time.perf_counter() - started) * 1000.0)
    return times


def load_ultralytics_yolo() -> object:
    try:
        from ultralytics import YOLO
    except ImportError as exc:
        raise SystemExit(
            "Ultralytics is not installed. Run: python -m pip install -r requirements-yolo.txt"
        ) from exc
    return YOLO


def load_simple_data_yaml(path: Path) -> dict[str, object]:
    data: dict[str, object] = {}
    with path.open("r", encoding="utf-8") as file:
        for raw_line in file:
            line = raw_line.strip()
            if not line or line.startswith("#") or ":" not in line:
                continue
            key, value = line.split(":", 1)
            key = key.strip()
            value = value.strip()
            if not key or not value:
                continue
            if key == "names":
                data[key] = ast.literal_eval(value)
            elif key == "nc":
                data[key] = int(value)
            elif key in {"train", "val", "test"}:
                data[key] = value.strip("'\"")
    return data


def write_simple_data_yaml(path: Path, data: dict[str, object]) -> None:
    ordered_keys = ["train", "val", "test", "nc", "names"]
    with path.open("w", encoding="utf-8") as file:
        for key in ordered_keys:
            if key not in data:
                continue
            value = data[key]
            if isinstance(value, str):
                file.write(f"{key}: {value}\n")
            else:
                file.write(f"{key}: {value!r}\n")


def summarize_dataset(data_yaml: Path) -> None:
    data = load_simple_data_yaml(data_yaml)
    names = data.get("names", [])
    print(f"Prepared dataset: {data_yaml}")
    print(f"Classes ({data.get('nc', len(names))}): {names}")
    for split in ("train", "val", "test"):
        if split not in data:
            continue
        image_dir = Path(str(data[split]))
        label_dir = image_dir.parent / "labels"
        images = [
            path
            for path in image_dir.iterdir()
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        ]
        labels = list(label_dir.glob("*.txt")) if label_dir.exists() else []
        print(f"{split}: {len(images)} images, {len(labels)} labels")


def write_reports(summaries: list[EvalSummary], args: argparse.Namespace) -> None:
    report_dir = args.work_dir / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)

    json_path = report_dir / "summary.json"
    with json_path.open("w", encoding="utf-8") as file:
        json.dump([summary.__dict__ for summary in summaries], file, indent=2)

    csv_path = report_dir / "summary.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=list(EvalSummary.__annotations__.keys()))
        writer.writeheader()
        for summary in summaries:
            writer.writerow(summary.__dict__)

    print(f"\nWrote reports:\n  {json_path}\n  {csv_path}")


def safe_float(value: object) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def mean_or_value(value: object) -> object:
    if value is None:
        return None
    if isinstance(value, (list, tuple)):
        return statistics.mean(value) if value else None
    if hasattr(value, "mean"):
        return value.mean()
    return value


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        raise ValueError("percentile requires at least one value")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * ratio)))
    return ordered[index]


def print_summary(summaries: list[EvalSummary]) -> None:
    print("\nModel comparison")
    print(
        "name,mAP50,mAP50-95,precision,recall,inference_ms,"
        "postprocess_ms,mean_predict_ms,p95_predict_ms"
    )
    for summary in summaries:
        print(
            f"{summary.name},"
            f"{fmt(summary.mAP50)},"
            f"{fmt(summary.mAP50_95)},"
            f"{fmt(summary.precision)},"
            f"{fmt(summary.recall)},"
            f"{fmt(summary.inference_ms)},"
            f"{fmt(summary.postprocess_ms)},"
            f"{fmt(summary.mean_predict_ms)},"
            f"{fmt(summary.p95_predict_ms)}"
        )


def fmt(value: float | None) -> str:
    return "" if value is None else f"{value:.4f}"


def main() -> None:
    args = parse_args()
    specs = parse_models(args.models)
    data_yaml = prepare_dataset(args)
    if args.prepare_only:
        summarize_dataset(data_yaml)
        return

    summaries: list[EvalSummary] = []
    for spec in specs:
        print(f"\n=== {spec.name} ({spec.weights}) ===")
        weights = train_model(spec, data_yaml, args) if args.train else Path(spec.weights)
        summaries.append(evaluate_model(spec, weights, data_yaml, args))

    print_summary(summaries)
    write_reports(summaries, args)


if __name__ == "__main__":
    main()
