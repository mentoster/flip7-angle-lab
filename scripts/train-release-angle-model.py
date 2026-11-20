#!/usr/bin/env python3
"""Train and export the self-contained Flip7 hinge-angle model.

The input is the app-private eight-column supervised calibration cache:
magnetic XYZ, gravity XYZ, Android public hinge and *raw* Samsung angle.
It is NOT included in the public repository, and the exported payload
contains only gradient-boosted tree thresholds and leaf weights, not rows.

Example (private data is streamed directly from the connected phone):
  adb -s YOUR_FLIP7_SERIAL exec-out run-as com.mentoster.flip7anglelab \
    cat files/flip7-angle-calibration-v1.csv | \
    uv run --with numpy --with scikit-learn \
      python scripts/train-release-angle-model.py --validate --emit

The output between MODEL_BASE64_START and MODEL_BASE64_END is zlib-compressed
little-endian F7GB model data, suitable for embedding in Kotlin source.
"""

from __future__ import annotations

import argparse
import base64
import collections
import csv
import struct
import sys
import textwrap
import zlib
from pathlib import Path

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.model_selection import GroupShuffleSplit


def features(samples: np.ndarray) -> np.ndarray:
    magnetic = samples[:, :3]
    gravity = samples[:, 3:6]
    hinge = samples[:, 6]
    magnitude = np.linalg.norm(magnetic, axis=1)
    projection = np.sum(magnetic * gravity, axis=1) / np.maximum(
        0.1, np.linalg.norm(gravity, axis=1)
    )
    perpendicular = np.sqrt(np.maximum(0.0, magnitude**2 - projection**2))
    return np.column_stack(
        [magnetic, gravity, hinge, magnitude, projection, perpendicular]
    )


def load_capture(path: Path) -> tuple[np.ndarray, np.ndarray]:
    """Stream private 2026-09-26 recording without loading an ~835 MB CSV.

    Accept only timestamp-fresh Samsung labels paired with a fresh gravity
    vector and an Android public hinge state. Aggregate sensor noise and
    repeated frames by orientation, motion speed, direction and 2° bins.
    Returns a supervised matrix and the six named capture orientations.
    Nothing is written to disk.
    """
    grouped: dict[tuple[str, str, str, str, int], list[list[float]]] = (
        collections.defaultdict(list)
    )
    last_gravity: tuple[int, tuple[float, float, float]] | None = None
    last_hinge: float | None = None
    with path.open(newline="") as source:
        rows = csv.DictReader(line for line in source if not line.startswith("#"))
        for row in rows:
            kind = row["type"]
            if kind == "9":
                last_gravity = (
                    int(row["timestamp_nanos"]),
                    (float(row["v0"]), float(row["v1"]), float(row["v2"])),
                )
                continue
            if kind == "36":
                last_hinge = float(row["v0"])
                continue
            if kind != "14" or last_gravity is None or last_hinge is None:
                continue
            tag = row["recording_tag"]
            if "mode=dynamic" not in tag:
                continue
            tags = dict(
                field.split("=", 1) for field in tag.split("|") if "=" in field
            )
            if not all(k in tags for k in ("orientation", "speed", "cycle", "phase")):
                continue
            if not row["reference_angle_deg"] or not row["reference_age_ms"]:
                continue
            if abs(float(row["reference_age_ms"])) > 100:
                continue
            if abs(int(row["timestamp_nanos"]) - last_gravity[0]) > 100_000_000:
                continue
            angle = float(row["reference_angle_deg"])
            if not (0 <= angle <= 180):
                continue
            key = (
                tags["orientation"],
                tags["speed"],
                tags["cycle"],
                tags["phase"],
                round(angle / 2),
            )
            if len(grouped[key]) < 18:
                grouped[key].append(
                    [
                        float(row["v0"]), float(row["v1"]), float(row["v2"]),
                        *last_gravity[1], last_hinge, angle,
                    ]
                )
    assert grouped, "No timestamp-aligned labeled sensor observations were found"
    samples = []
    orientations = []
    for key, values in sorted(grouped.items()):
        samples.append(np.median(np.asarray(values, dtype=np.float64), axis=0))
        orientations.append(key[0])
    return np.asarray(samples), np.asarray(orientations)


def validate_orientations(
    inputs: np.ndarray, labels: np.ndarray, orientations: np.ndarray,
) -> None:
    """Hold out every named orientation, including all its motion cycles."""
    combined = []
    for orientation in sorted(set(orientations)):
        test = np.where(orientations == orientation)[0]
        train = np.where(orientations != orientation)[0]
        model = new_model().fit(
            inputs[train], labels[train], sample_weight=weights(labels[train])
        )
        error = np.abs(np.clip(model.predict(inputs[test]), 0, 180) - labels[test])
        combined.extend(error.tolist())
        print(
            f"orientation_holdout={orientation} n={len(test)} "
            f"MAE={error.mean():.2f}° P90={np.percentile(error, 90):.2f}° "
            f"max={error.max():.2f}°"
        )
    combined = np.asarray(combined)
    print(
        f"orientation_holdout_combined: n={len(combined)}, "
        f"MAE={combined.mean():.2f}°, P90={np.percentile(combined, 90):.2f}°"
    )
    print(
        "Caveat: all six orientations are from one recording session. "
        "Whole-device rotation at a fixed hinge angle remains unverified."
    )


def new_model() -> HistGradientBoostingRegressor:
    return HistGradientBoostingRegressor(
        max_iter=120,
        max_leaf_nodes=14,
        l2_regularization=3,
        learning_rate=0.07,
        random_state=7,
    )


def weights(labels: np.ndarray) -> np.ndarray:
    bins = np.floor(labels / 10).astype(int).clip(0, 18)
    counts = np.bincount(bins, minlength=19)
    sample_weights = np.maximum(1, counts[bins]) ** (-0.7)
    return sample_weights / sample_weights.mean()


def validate(inputs: np.ndarray, labels: np.ndarray, poses: np.ndarray) -> None:
    errors = []
    splitter = GroupShuffleSplit(n_splits=3, test_size=0.25, random_state=43)
    for index, (train, test) in enumerate(splitter.split(inputs, labels, poses), start=1):
        model = new_model().fit(
            inputs[train], labels[train], sample_weight=weights(labels[train])
        )
        predictions = np.clip(model.predict(inputs[test]), 0.0, 180.0)
        error = np.abs(labels[test] - predictions)
        errors.extend(error.tolist())
        print(
            f"pose_holdout_{index}: n={len(test)}, MAE={error.mean():.2f}°, "
            f"P90={np.percentile(error, 90):.2f}°, max={error.max():.2f}°"
        )
    errors = np.asarray(errors)
    print(
        f"pose_holdout_combined: n={len(errors)}, MAE={errors.mean():.2f}°, "
        f"P90={np.percentile(errors, 90):.2f}°"
    )
    print(
        "Caveat: this is held-out gravity-pose testing on one device; "
        "it is not held-out session or cross-device validation."
    )


def pack_model(model: HistGradientBoostingRegressor) -> bytes:
    trees = [round_[0] for round_ in model._predictors]
    buffer = bytearray()
    buffer.extend(
        struct.pack(
            "<4siiid",
            b"F7GB",
            1,  # binary format version
            10,  # input feature count
            len(trees),
            float(model._baseline_prediction[0, 0]),
        )
    )
    for tree in trees:
        nodes = tree.nodes
        buffer.extend(struct.pack("<i", len(nodes)))
        for node in nodes:
            buffer.extend(
                struct.pack(
                    "<iiidd",
                    -1 if node["is_leaf"] else int(node["feature_idx"]),
                    int(node["left"]),
                    int(node["right"]),
                    float(node["num_threshold"]),
                    float(node["value"]),
                )
            )
    return bytes(buffer)


def packed_predict(data: bytes, inputs: np.ndarray) -> np.ndarray:
    # Independent interpreter to verify that the exported nodes reproduce
    # sklearn predictions before they are committed to the APK.
    magic, version, features_count, count, baseline = struct.unpack_from(
        "<4siiid", data, 0
    )
    assert (magic, version, features_count) == (b"F7GB", 1, 10)
    offset = struct.calcsize("<4siiid")
    trees = []
    for _ in range(count):
        (size,) = struct.unpack_from("<i", data, offset)
        offset += 4
        nodes = []
        for _ in range(size):
            nodes.append(struct.unpack_from("<iiidd", data, offset))
            offset += struct.calcsize("<iiidd")
        trees.append(nodes)
    assert offset == len(data)
    output = np.full(len(inputs), baseline, dtype=np.float64)
    for tree in trees:
        for index, vector in enumerate(inputs):
            node_index = 0
            while True:
                feature, left, right, threshold, value = tree[node_index]
                if feature < 0:
                    output[index] += value
                    break
                node_index = left if vector[feature] <= threshold else right
    return output


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--validate", action="store_true")
    parser.add_argument("--emit", action="store_true")
    parser.add_argument(
        "--capture-sensors",
        type=Path,
        help="Stream a private, fully labeled calibration capture instead of stdin.",
    )
    args = parser.parse_args()
    orientations = None
    if args.capture_sensors:
        samples, orientations = load_capture(args.capture_sensors)
    else:
        samples = np.loadtxt(sys.stdin, delimiter=",", ndmin=2)
    if samples.ndim != 2 or samples.shape[1] != 8:
        parser.error("expected 8 numeric fields per labeled calibration sample")
    if len(samples) < 100 or not np.isfinite(samples).all():
        parser.error("need at least 100 finite labeled calibration samples")
    labels = samples[:, 7]
    if np.any((labels < 0) | (labels > 180)):
        parser.error("Samsung reference angles must be between 0 and 180 degrees")
    inputs = features(samples)
    pose_cell = np.rint(samples[:, 3:6] / 4).astype(int)
    poses = np.unique(pose_cell, axis=0, return_inverse=True)[1]
    print(f"labeled_samples={len(labels)} gravity_pose_cells={len(np.unique(poses))}")
    if orientations is not None:
        print("capture_orientations=" + ",".join(sorted(set(orientations))))
    if args.validate:
        if orientations is not None:
            validate_orientations(inputs, labels, orientations)
        else:
            validate(inputs, labels, poses)
    model = new_model().fit(inputs, labels, sample_weight=weights(labels))
    data = pack_model(model)
    subset = inputs[::max(1, len(inputs) // 200)]
    reference = model.predict(subset)
    reproduced = packed_predict(data, subset)
    error = float(np.max(np.abs(reference - reproduced)))
    if error > 1e-5:
        raise SystemExit(f"model export parity FAILED: max difference {error:.6f}°")
    print(
        f"model_export_parity_max_deg={error:.9f} "
        f"trees={len(model._predictors)} raw_model_bytes={len(data)}"
    )
    compressed = zlib.compress(data, level=9)
    print(f"compressed_model_bytes={len(compressed)}")
    if args.emit:
        print("MODEL_BASE64_START")
        for line in textwrap.wrap(base64.b64encode(compressed).decode("ascii"), 120):
            print(line)
        print("MODEL_BASE64_END")


if __name__ == "__main__":
    main()
