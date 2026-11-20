#!/usr/bin/env python3
"""Recompute aggregate angle-lab research metrics without publishing raw data.

Input: eight-column, Samsung-labeled calibration cache from stdin.
Optional: --archive-sensors points to the older multi-orientation recording.

This script prints only aggregate errors, binned counts and distributions
as JSON to stdout. It does not write or upload source recordings.

  python scripts/research-benchmarks.py --archive-sensors captures/OWN-sensors.csv \
      < captures/OWN-eight-column-cache.csv
"""

import argparse
import json
import runpy
import sys
from pathlib import Path

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.model_selection import GroupShuffleSplit


def rounded(value):
    return round(float(value), 2)


def error_stats(errors):
    return {
        "mae": rounded(np.mean(errors)),
        "p90": rounded(np.percentile(errors, 90)),
        "max": rounded(np.max(errors)),
    }


def magnetic_distribution(rows):
    near_open = (rows[:, 7] >= 174) & (rows[:, 7] <= 180)
    magnitude = np.linalg.norm(rows[near_open, :3], axis=1)
    return {
        "n": int(len(magnitude)),
        "median_uT": rounded(np.median(magnitude)),
        "p05_uT": rounded(np.percentile(magnitude, 5)),
        "p95_uT": rounded(np.percentile(magnitude, 95)),
        "counts_50uT": [
            int(value) for value in np.histogram(
                magnitude, bins=np.arange(0, 551, 50),
            )[0]
        ],
    }


def evaluate_recent(samples, train):
    features = train["features"](samples)
    labels = samples[:, 7]
    poses = np.unique(
        np.rint(samples[:, 3:6] / 4).astype(int), axis=0,
        return_inverse=True,
    )[1]
    all_errors, all_labels, splits = [], [], []
    splitter = GroupShuffleSplit(n_splits=3, test_size=0.25, random_state=43)
    for index, (train_rows, test_rows) in enumerate(
        splitter.split(features, labels, poses), 1
    ):
        model = train["new_model"]().fit(
            features[train_rows], labels[train_rows],
            sample_weight=train["weights"](labels[train_rows]),
        )
        predictions = np.clip(model.predict(features[test_rows]), 0, 180)
        errors = np.abs(labels[test_rows] - predictions)
        splits.append({"split": index, "n": len(test_rows), **error_stats(errors)})
        all_errors.extend(errors.tolist())
        all_labels.extend(labels[test_rows].tolist())
    all_errors = np.array(all_errors)
    all_labels = np.array(all_labels)
    bins = []
    for index in range(9):
        low = index * 20
        high = min(180, (index + 1) * 20)
        mask = (
            (all_labels >= low) &
            (all_labels <= high if index == 8 else all_labels < high)
        )
        train_mask = (
            (labels >= low) &
            (labels <= high if index == 8 else labels < high)
        )
        bin_errors = all_errors[mask]
        bins.append({
            "range": f"{low}-{high}",
            "n": int(train_mask.sum()),
            "test_n": int(mask.sum()),
            **(error_stats(bin_errors) if len(bin_errors) else {
                "mae": None, "p90": None, "max": None,
            }),
        })
    return {
        "rows": len(samples),
        "gravity_pose_cells": int(len(np.unique(poses))),
        "splits": splits,
        "holdout": {
            "n": len(all_errors),
            **error_stats(all_errors),
            **{
                f"p{q}": rounded(np.percentile(all_errors, q))
                for q in (50, 75, 95, 99)
            },
        },
        "angle_bins": bins,
        "magnetic_near_open": magnetic_distribution(samples),
    }


def evaluate_archive(samples, orientations, train):
    features = train["features"](samples)
    labels = samples[:, 7]
    orientations = np.asarray(orientations)
    results = []
    for orientation in sorted(set(orientations)):
        test_rows = orientations == orientation
        model = train["new_model"]().fit(
            features[~test_rows], labels[~test_rows],
            sample_weight=train["weights"](labels[~test_rows]),
        )
        errors = np.abs(
            np.clip(model.predict(features[test_rows]), 0, 180) -
            labels[test_rows]
        )
        results.append({
            "orientation": str(orientation),
            "n": int(test_rows.sum()),
            **error_stats(errors),
        })

    mag, gravity = samples[:, :3], samples[:, 3:6]
    norm = np.linalg.norm(mag, axis=1)
    projected = np.sum(mag * gravity, axis=1) / np.maximum(
        0.1, np.linalg.norm(gravity, axis=1),
    )
    perpendicular = np.sqrt(np.maximum(0, norm**2 - projected**2))
    inputs = {
        "Magnitude + coarse hinge": np.column_stack([norm, samples[:, 6]]),
        "Magnitude + gravity relative": np.column_stack([
            norm, projected, perpendicular, samples[:, 6],
        ]),
        "Full XYZ / gravity features": features,
    }
    ablation = []
    for name, x in inputs.items():
        errors = []
        for orientation in sorted(set(orientations)):
            test_rows = orientations == orientation
            model = HistGradientBoostingRegressor(
                max_iter=100, max_leaf_nodes=12, learning_rate=0.07,
                l2_regularization=3, random_state=7,
            ).fit(x[~test_rows], labels[~test_rows])
            errors.extend(np.abs(
                np.clip(model.predict(x[test_rows]), 0, 180) -
                labels[test_rows]
            ))
        ablation.append({"name": name, "n": len(errors), **error_stats(errors)})
    return {
        "rows": len(samples),
        "orientations": results,
        "feature_ablation": ablation,
        "magnetic_near_open": magnetic_distribution(samples),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive-sensors", type=Path)
    args = parser.parse_args()
    module = runpy.run_path(str(Path(__file__).with_name(
        "train-release-angle-model.py",
    )))
    samples = np.loadtxt(sys.stdin, delimiter=",", ndmin=2)
    if samples.ndim != 2 or samples.shape[1] != 8 or len(samples) < 100:
        parser.error("expected at least 100 labeled rows with exactly 8 columns")
    if not np.isfinite(samples).all():
        parser.error("sample cache contains non-finite numeric values")
    if not np.all((samples[:, 7] >= 0) & (samples[:, 7] <= 180)):
        parser.error("reference angles outside the 0..180 degree range")
    summary = {
        "method": "Replayed training recipe; not fixed APK model evaluation",
        "recent": evaluate_recent(samples, module),
    }
    if args.archive_sensors is not None:
        archived, orientations = module["load_capture"](args.archive_sensors)
        summary["archive"] = evaluate_archive(archived, orientations, module)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
