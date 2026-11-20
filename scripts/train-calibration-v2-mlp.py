#!/usr/bin/env python3
"""Train the Flip7 magnetic hinge MLP from a Calibration v2 capture.

Example:
  uv run --with pandas --with scipy --with scikit-learn \
    python scripts/train-calibration-v2-mlp.py \
    captures/20260926-162723/flip7-calibration-v2-20260926-162723-sensors.csv \
    --exact-csv captures/20260926-162723/flip7-calibration-v2-20260926-162723-exact.csv \
    --validate
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.neural_network import MLPRegressor
from sklearn.preprocessing import StandardScaler


TYPE_MAG_UNCAL = 14
TYPE_HINGE = 36
TYPE_GRAVITY = 9
FEATURES = ["x", "y", "z", "dotg", "perpg", "public"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("sensors_csv", type=Path)
    parser.add_argument("--exact-csv", type=Path, required=True)
    parser.add_argument("--max-exact-age-ms", type=float, default=150.0)
    parser.add_argument(
        "--validate",
        action="store_true",
        help="Run slow leave-one-orientation-out validation before final training.",
    )
    return parser.parse_args()


def metrics(y_true: np.ndarray, y_pred: np.ndarray) -> tuple[float, float, float, float]:
    error = np.abs(np.clip(y_pred, 0.0, 180.0) - y_true)
    return (
        float(error.mean()),
        float(np.median(error)),
        float(np.percentile(error, 95)),
        float(error.max()),
    )


def load_features(
    sensors_csv: Path,
    exact_csv: Path,
    max_exact_age_ms: float,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    columns = [
        "timestamp_nanos",
        "elapsed_ms",
        "type",
        "v0",
        "v1",
        "v2",
        "recording_tag",
    ]
    events = pd.read_csv(sensors_csv, comment="#", usecols=columns)
    events = events[
        (events["elapsed_ms"] >= 0)
        & events["type"].isin([TYPE_MAG_UNCAL, TYPE_HINGE, TYPE_GRAVITY])
    ].copy()

    magnetic = events[events["type"] == TYPE_MAG_UNCAL].sort_values("timestamp_nanos").copy()
    hinge = (
        events.loc[events["type"] == TYPE_HINGE, ["timestamp_nanos", "v0"]]
        .sort_values("timestamp_nanos")
        .rename(columns={"v0": "public"})
    )
    gravity = (
        events.loc[
            events["type"] == TYPE_GRAVITY,
            ["timestamp_nanos", "v0", "v1", "v2"],
        ]
        .sort_values("timestamp_nanos")
        .rename(columns={"v0": "gx", "v1": "gy", "v2": "gz"})
    )

    magnetic = pd.merge_asof(magnetic, hinge, on="timestamp_nanos", direction="backward")
    magnetic = pd.merge_asof(
        magnetic,
        gravity,
        on="timestamp_nanos",
        direction="nearest",
        tolerance=20_000_000,
    )

    exact = (
        pd.read_csv(
            exact_csv,
            comment="#",
            usecols=["timestamp_nanos", "angle_deg", "source_age_ms"],
        )
        .dropna()
        .sort_values("timestamp_nanos")
    )
    sensor_time = magnetic["timestamp_nanos"].to_numpy(float)
    exact_time = exact["timestamp_nanos"].to_numpy(float)
    in_range = (sensor_time >= exact_time[0]) & (sensor_time <= exact_time[-1])
    magnetic = magnetic.loc[in_range].copy()
    sensor_time = sensor_time[in_range]
    magnetic["target"] = np.interp(sensor_time, exact_time, exact["angle_deg"].to_numpy(float))
    magnetic["age"] = np.interp(sensor_time, exact_time, exact["source_age_ms"].to_numpy(float))

    magnetic["x"] = magnetic["v0"]
    magnetic["y"] = magnetic["v1"]
    magnetic["z"] = magnetic["v2"]
    gravity_values = magnetic[["gx", "gy", "gz"]].to_numpy(float)
    gravity_norm = np.linalg.norm(gravity_values, axis=1)
    gravity_hat = gravity_values / np.maximum(gravity_norm[:, None], 1e-9)
    field = magnetic[["x", "y", "z"]].to_numpy(float)
    magnetic["dotg"] = np.sum(field * gravity_hat, axis=1)
    field_norm = np.linalg.norm(field, axis=1)
    magnetic["perpg"] = np.sqrt(
        np.maximum(field_norm**2 - magnetic["dotg"].to_numpy() ** 2, 0.0)
    )
    magnetic["public"] = magnetic["public"].ffill().bfill().clip(0.0, 180.0)

    dynamic_meta = magnetic["recording_tag"].str.extract(
        r"mode=dynamic\|stage=(?P<stage>\d+)/18\|orientation=(?P<orientation>[^|]+)"
        r"\|speed=(?P<speed>[^|]+)\|cycle=(?P<cycle>\d)/4\|phase=(?P<direction>close|open)"
    )
    dynamic = pd.concat([magnetic, dynamic_meta], axis=1)
    dynamic = dynamic[
        dynamic["orientation"].notna() & (dynamic["age"] <= max_exact_age_ms)
    ].copy()
    dynamic["angle_bin"] = dynamic["target"].round().astype(int)
    dynamic = (
        dynamic.groupby(
            ["orientation", "speed", "cycle", "direction", "angle_bin"],
            as_index=False,
        )[["target"] + FEATURES]
        .median()
        .groupby(
            ["orientation", "speed", "direction", "angle_bin"],
            as_index=False,
        )[["target"] + FEATURES]
        .median()
        .dropna()
    )

    static_meta = magnetic["recording_tag"].str.extract(
        r"mode=static\|index=(?P<index>\d+)/37\|target=(?P<commanded>\d+)\|phase=hold"
    )
    static = pd.concat([magnetic, static_meta], axis=1)
    static = static[
        static["index"].notna() & (static["age"] <= max_exact_age_ms)
    ].copy()
    static = (
        static.groupby(["index"], as_index=False)[["target"] + FEATURES]
        .median()
        .dropna()
    )
    return dynamic, static


def new_model() -> MLPRegressor:
    return MLPRegressor(
        hidden_layer_sizes=(8,),
        activation="tanh",
        solver="lbfgs",
        alpha=0.001,
        max_iter=2000,
        random_state=7,
    )


def validate(dynamic: pd.DataFrame) -> None:
    rows = []
    for orientation in sorted(dynamic["orientation"].unique()):
        train = dynamic[dynamic["orientation"] != orientation]
        test = dynamic[dynamic["orientation"] == orientation]
        scaler = StandardScaler().fit(train[FEATURES])
        model = new_model().fit(scaler.transform(train[FEATURES]), train["target"])
        result = metrics(
            test["target"].to_numpy(float),
            model.predict(scaler.transform(test[FEATURES])),
        )
        rows.append(result)
        print(
            f"holdout={orientation:17s} n={len(test):4d} "
            f"mae={result[0]:6.2f} median={result[1]:6.2f} "
            f"p95={result[2]:6.2f} worst={result[3]:6.2f}"
        )
    values = np.asarray(rows)
    print(
        "orientation-holdout mean "
        f"mae={values[:, 0].mean():.2f} median={values[:, 1].mean():.2f} "
        f"p95={values[:, 2].mean():.2f} worst={values[:, 3].max():.2f}"
    )


def kotlin_array(name: str, values: np.ndarray) -> None:
    text = ", ".join(f"{float(value):.9g}f" for value in values.ravel())
    print(f"private val {name} = floatArrayOf({text})")


def main() -> None:
    args = parse_args()
    dynamic, static = load_features(
        args.sensors_csv,
        args.exact_csv,
        args.max_exact_age_ms,
    )
    print(f"dynamic_samples={len(dynamic)} static_holds={len(static)}")
    if args.validate:
        validate(dynamic)

    scaler = StandardScaler().fit(dynamic[FEATURES])
    model = new_model().fit(scaler.transform(dynamic[FEATURES]), dynamic["target"])
    dynamic_metrics = metrics(
        dynamic["target"].to_numpy(float),
        model.predict(scaler.transform(dynamic[FEATURES])),
    )
    static_metrics = metrics(
        static["target"].to_numpy(float),
        model.predict(scaler.transform(static[FEATURES])),
    )
    print(
        "final dynamic "
        f"mae={dynamic_metrics[0]:.2f} median={dynamic_metrics[1]:.2f} "
        f"p95={dynamic_metrics[2]:.2f} worst={dynamic_metrics[3]:.2f}"
    )
    print(
        "static check "
        f"mae={static_metrics[0]:.2f} median={static_metrics[1]:.2f} "
        f"p95={static_metrics[2]:.2f} worst={static_metrics[3]:.2f}"
    )
    print(f"iterations={model.n_iter_} loss={model.loss_:.6f}")
    print("\nKotlin parameters:")
    kotlin_array("featureMean", scaler.mean_)
    kotlin_array("featureScale", scaler.scale_)
    kotlin_array("hiddenWeights", model.coefs_[0])
    kotlin_array("hiddenBias", model.intercepts_[0])
    kotlin_array("outputWeights", model.coefs_[1])
    print(f"private const val outputBias = {float(model.intercepts_[1][0]):.9g}f")


if __name__ == "__main__":
    main()
