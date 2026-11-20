#!/usr/bin/env python3
"""Analyze Flip7 Angle Lab raw sensor captures against Samsung exact hinge reference.

The capture format is produced by DirectSensorLab.  This script deliberately
validates across whole physical fold cycles instead of random rows so that a
model cannot win by memorizing adjacent samples from the same sweep.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import struct

import numpy as np
import pandas as pd
from scipy.spatial.transform import Rotation
from sklearn.ensemble import ExtraTreesRegressor, HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.neighbors import KNeighborsRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import PolynomialFeatures, StandardScaler
from sklearn.svm import SVR


TYPE_ACCEL = 1
TYPE_MAG_CAL = 2
TYPE_GYRO = 4
TYPE_ROTATION = 11
TYPE_MAG_UNCAL = 14
TYPE_GAME_ROTATION = 15
TYPE_HINGE = 36
TYPE_GRAVITY = 9


@dataclass(frozen=True)
class OpenCluster:
    start_ms: float
    end_ms: float
    duration_ms: float
    count: int


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("csv", type=Path)
    parser.add_argument(
        "--exact-csv",
        type=Path,
        help="Optional Calibration v2 120 Hz exact-angle sidecar. When supplied, labels are timestamp-aligned from this stream.",
    )
    parser.add_argument(
        "--max-reference-age-ms",
        type=float,
        default=150.0,
        help="Maximum label age for per-degree moving samples.",
    )
    parser.add_argument(
        "--print-svr-params",
        action="store_true",
        help="Train the selected compact SVR on all cycle samples and print Kotlin-ready parameters.",
    )
    return parser.parse_args()


def read_capture(path: Path, exact_path: Path | None = None) -> pd.DataFrame:
    df = pd.read_csv(path, comment="#")
    required = {"timestamp_nanos", "elapsed_ms", "type", "reference_angle_deg", "reference_age_ms"}
    missing = sorted(required - set(df.columns))
    if missing:
        raise SystemExit(f"capture is missing required columns: {', '.join(missing)}")

    before = len(df)
    df = df[df["elapsed_ms"] >= 0].copy()
    if exact_path is not None:
        df = align_exact_stream(df, exact_path)
    print(f"rows={before:,} usable_rows={len(df):,} dropped_pre_record={before - len(df):,}")
    print(
        "reference="
        f"{df['reference_angle_deg'].min():.1f}..{df['reference_angle_deg'].max():.1f} deg "
        f"unique={df['reference_angle_deg'].nunique()} "
        f"duration={df['elapsed_ms'].max() / 1000.0:.2f}s"
    )
    return df


def align_exact_stream(df: pd.DataFrame, exact_path: Path) -> pd.DataFrame:
    exact = pd.read_csv(exact_path, comment="#")
    required = {"timestamp_nanos", "angle_deg", "source_age_ms"}
    missing = sorted(required - set(exact.columns))
    if missing:
        raise SystemExit(f"exact stream is missing required columns: {', '.join(missing)}")
    exact = exact.dropna(subset=["timestamp_nanos", "angle_deg"]).copy()
    exact = exact.sort_values("timestamp_nanos").drop_duplicates("timestamp_nanos", keep="last")
    if len(exact) < 2:
        raise SystemExit("exact stream must contain at least two timestamped samples")

    sensor_t = df["timestamp_nanos"].to_numpy(dtype=np.float64)
    exact_t = exact["timestamp_nanos"].to_numpy(dtype=np.float64)
    exact_angle = exact["angle_deg"].to_numpy(dtype=np.float64)
    exact_age = exact["source_age_ms"].fillna(0.0).to_numpy(dtype=np.float64)
    in_range = (sensor_t >= exact_t[0]) & (sensor_t <= exact_t[-1])

    aligned_angle = np.full(len(df), np.nan, dtype=np.float64)
    aligned_age = np.full(len(df), np.nan, dtype=np.float64)
    aligned_angle[in_range] = np.interp(sensor_t[in_range], exact_t, exact_angle)
    aligned_age[in_range] = np.interp(sensor_t[in_range], exact_t, exact_age)

    out = df.copy()
    out["reference_angle_deg"] = aligned_angle
    out["reference_age_ms"] = aligned_age
    out = out[in_range].copy()
    print(
        f"exact_alignment={exact_path.name} samples={len(exact):,} "
        f"coverage={in_range.mean() * 100.0:.2f}% "
        f"source_age_p95={np.nanpercentile(out['reference_age_ms'], 95):.2f}ms"
    )
    return out


def sensor_values(df: pd.DataFrame, sensor_type: int, prefix: str, values: int) -> pd.DataFrame:
    cols = ["elapsed_ms"] + [f"v{i}" for i in range(values)]
    out = df.loc[df["type"] == sensor_type, cols].copy()
    out = out.rename(columns={f"v{i}": f"{prefix}{i}" for i in range(values)})
    return out.sort_values("elapsed_ms")


def merge_nearest(base: pd.DataFrame, other: pd.DataFrame, tolerance_ms: float = 5.0) -> pd.DataFrame:
    return pd.merge_asof(
        base.sort_values("elapsed_ms"),
        other.sort_values("elapsed_ms"),
        on="elapsed_ms",
        direction="nearest",
        tolerance=tolerance_ms,
    )


def build_wide(df: pd.DataFrame) -> pd.DataFrame:
    base_cols = [
        "elapsed_ms",
        "reference_angle_deg",
        "reference_age_ms",
        "v0",
        "v1",
        "v2",
        "v3",
        "v4",
        "v5",
    ]
    wide = df.loc[df["type"] == TYPE_MAG_UNCAL, base_cols].copy()
    wide = wide.rename(
        columns={
            "v0": "mu_x",
            "v1": "mu_y",
            "v2": "mu_z",
            "v3": "mag_bias_x",
            "v4": "mag_bias_y",
            "v5": "mag_bias_z",
        }
    ).sort_values("elapsed_ms")

    for sensor_type, prefix, values in [
        (TYPE_MAG_CAL, "mc_", 3),
        (TYPE_ROTATION, "rv_", 5),
        (TYPE_GAME_ROTATION, "grv_", 4),
        (TYPE_GRAVITY, "grav_", 3),
        (TYPE_ACCEL, "acc_", 3),
        (TYPE_GYRO, "gyro_", 3),
    ]:
        part = sensor_values(df, sensor_type, prefix, values)
        wide = merge_nearest(wide, part)

    wide["mu_norm"] = np.linalg.norm(wide[["mu_x", "mu_y", "mu_z"]].to_numpy(), axis=1)
    wide["mc_norm"] = np.linalg.norm(wide[["mc_0", "mc_1", "mc_2"]].to_numpy(), axis=1)
    wide["mag_bias_norm"] = np.linalg.norm(
        wide[["mag_bias_x", "mag_bias_y", "mag_bias_z"]].to_numpy(), axis=1
    )

    gravity = wide[["grav_0", "grav_1", "grav_2"]].to_numpy(dtype=float)
    gravity_norm = np.linalg.norm(gravity, axis=1)
    ghat = gravity / np.maximum(gravity_norm[:, None], 1e-9)
    mag = wide[["mu_x", "mu_y", "mu_z"]].to_numpy(dtype=float)
    wide["mu_dot_gravity"] = np.sum(mag * ghat, axis=1)
    wide["mu_perp_gravity"] = np.sqrt(
        np.maximum(wide["mu_norm"].to_numpy() ** 2 - wide["mu_dot_gravity"].to_numpy() ** 2, 0.0)
    )

    hinge = df.loc[df["type"] == TYPE_HINGE, ["elapsed_ms", "v0"]].copy()
    hinge = hinge.rename(columns={"v0": "public_hinge"}).sort_values("elapsed_ms")
    wide = pd.merge_asof(wide, hinge, on="elapsed_ms", direction="backward")
    initial = float(round(wide.iloc[0]["reference_angle_deg"] / 90.0) * 90.0)
    wide["public_hinge"] = wide["public_hinge"].fillna(initial).clip(0.0, 180.0)
    return wide


def find_open_clusters(wide: pd.DataFrame) -> list[OpenCluster]:
    open_rows = wide.loc[wide["reference_angle_deg"] >= 165.0, ["elapsed_ms"]].copy()
    if open_rows.empty:
        return []
    group = open_rows["elapsed_ms"].diff().fillna(0.0).gt(1000.0).cumsum()
    clusters: list[OpenCluster] = []
    for _, rows in open_rows.groupby(group):
        start = float(rows["elapsed_ms"].iloc[0])
        end = float(rows["elapsed_ms"].iloc[-1])
        duration = end - start
        if duration >= 150.0:
            clusters.append(OpenCluster(start, end, duration, len(rows)))
    return clusters


def normalized_quaternion(rows: pd.DataFrame, columns: list[str]) -> np.ndarray | None:
    q = rows[columns].dropna().to_numpy(dtype=float)
    if len(q) == 0:
        return None
    reference = q[0]
    signs = np.where((q @ reference) < 0.0, -1.0, 1.0)
    q = q * signs[:, None]
    mean = q.mean(axis=0)
    norm = np.linalg.norm(mean)
    if norm < 1e-9:
        return None
    return mean / norm


def assign_cycles(wide: pd.DataFrame, clusters: list[OpenCluster]) -> pd.DataFrame:
    wide = wide.copy()
    wide["cycle"] = 0
    wide["direction"] = "outside"
    wide["baseline_qx"] = np.nan
    wide["baseline_qy"] = np.nan
    wide["baseline_qz"] = np.nan
    wide["baseline_qw"] = np.nan

    for cycle, (left, right) in enumerate(zip(clusters, clusters[1:]), start=1):
        mask = wide["elapsed_ms"].between(left.end_ms, right.start_ms)
        if not mask.any():
            continue
        wide.loc[mask, "cycle"] = cycle

        baseline_start = max(left.start_ms, left.end_ms - 500.0)
        baseline_rows = wide[wide["elapsed_ms"].between(baseline_start, left.end_ms)]
        for axis in "xyz":
            median = float(baseline_rows[f"mu_{axis}"].median())
            wide.loc[mask, f"mu_open_{axis}"] = median
            wide.loc[mask, f"dmu_{axis}"] = wide.loc[mask, f"mu_{axis}"] - median
        for index, axis in enumerate("xyz"):
            median = float(baseline_rows[f"mc_{index}"].median())
            wide.loc[mask, f"mc_open_{axis}"] = median

        q0 = normalized_quaternion(baseline_rows, ["grv_0", "grv_1", "grv_2", "grv_3"])
        if q0 is not None:
            for column, value in zip(
                ["baseline_qx", "baseline_qy", "baseline_qz", "baseline_qw"], q0
            ):
                wide.loc[mask, column] = value

        cycle_rows = wide.loc[mask]
        min_angle = float(cycle_rows["reference_angle_deg"].min())
        bottom = cycle_rows[cycle_rows["reference_angle_deg"] <= min_angle + 1.0]
        first_bottom = float(bottom["elapsed_ms"].iloc[0])
        last_bottom = float(bottom["elapsed_ms"].iloc[-1])
        wide.loc[mask & (wide["elapsed_ms"] < first_bottom), "direction"] = "closing"
        wide.loc[mask & (wide["elapsed_ms"] > last_bottom), "direction"] = "opening"
        wide.loc[
            mask & wide["elapsed_ms"].between(first_bottom, last_bottom), "direction"
        ] = "bottom"

    wide["dmu_norm"] = np.linalg.norm(wide[["dmu_x", "dmu_y", "dmu_z"]].to_numpy(), axis=1)
    wide = add_relative_game_rotation(wide)
    return add_earth_compensated_magnetic_delta(wide)


def add_relative_game_rotation(wide: pd.DataFrame) -> pd.DataFrame:
    wide = wide.copy()
    wide[["rel_rot_x", "rel_rot_y", "rel_rot_z"]] = np.nan
    for cycle in sorted(c for c in wide["cycle"].unique() if c > 0):
        mask = wide["cycle"] == cycle
        rows = wide.loc[mask]
        q0 = rows[["baseline_qx", "baseline_qy", "baseline_qz", "baseline_qw"]].iloc[0].to_numpy(float)
        if not np.isfinite(q0).all():
            continue
        q = rows[["grv_0", "grv_1", "grv_2", "grv_3"]].to_numpy(float)
        valid = np.isfinite(q).all(axis=1) & (np.linalg.norm(q, axis=1) > 1e-9)
        if not valid.any():
            continue
        q_valid = q[valid]
        q_valid = q_valid / np.linalg.norm(q_valid, axis=1)[:, None]
        rel = Rotation.from_quat(q_valid) * Rotation.from_quat(q0).inv()
        rotvec = rel.as_rotvec()
        indices = rows.index.to_numpy()[valid]
        wide.loc[indices, ["rel_rot_x", "rel_rot_y", "rel_rot_z"]] = rotvec
    return wide


def add_earth_compensated_magnetic_delta(wide: pd.DataFrame) -> pd.DataFrame:
    """Remove the estimated Earth field before measuring hinge-magnet delta.

    The calibrated magnetometer is badly distorted while folding, but at the
    fully-open public-hinge pose it is still useful as a one-shot estimate of
    the ambient Earth field.  TYPE_GAME_ROTATION_VECTOR then transports that
    baseline field into the current device frame without consulting the
    magnetometer again.  Subtracting the open raw-minus-calibrated bias leaves
    a runtime-feasible hinge-only delta that should be less sensitive to whole
    handset orientation changes than raw XYZ or a simple open-pose delta.
    """
    wide = wide.copy()
    wide[["ec_dmu_x", "ec_dmu_y", "ec_dmu_z", "ec_dmu_norm"]] = np.nan
    for cycle in sorted(c for c in wide["cycle"].unique() if c > 0):
        mask = wide["cycle"] == cycle
        rows = wide.loc[mask]
        first = rows.iloc[0]
        q0 = first[["baseline_qx", "baseline_qy", "baseline_qz", "baseline_qw"]].to_numpy(float)
        mc_open = first[["mc_open_x", "mc_open_y", "mc_open_z"]].to_numpy(float)
        mu_open = first[["mu_open_x", "mu_open_y", "mu_open_z"]].to_numpy(float)
        if not (np.isfinite(q0).all() and np.isfinite(mc_open).all() and np.isfinite(mu_open).all()):
            continue

        q = rows[["grv_0", "grv_1", "grv_2", "grv_3"]].to_numpy(float)
        mag = rows[["mu_x", "mu_y", "mu_z"]].to_numpy(float)
        valid = (
            np.isfinite(q).all(axis=1)
            & (np.linalg.norm(q, axis=1) > 1e-9)
            & np.isfinite(mag).all(axis=1)
        )
        if not valid.any():
            continue

        q_valid = q[valid]
        q_valid = q_valid / np.linalg.norm(q_valid, axis=1)[:, None]
        r0 = Rotation.from_quat(q0)
        current = Rotation.from_quat(q_valid)
        earth_world = r0.apply(mc_open)
        earth_device = current.inv().apply(np.broadcast_to(earth_world, (len(q_valid), 3)))
        open_bias = mu_open - mc_open
        corrected = mag[valid] - earth_device - open_bias
        indices = rows.index.to_numpy()[valid]
        wide.loc[indices, ["ec_dmu_x", "ec_dmu_y", "ec_dmu_z"]] = corrected
        wide.loc[indices, "ec_dmu_norm"] = np.linalg.norm(corrected, axis=1)
    return wide


def print_cycle_summary(wide: pd.DataFrame, clusters: list[OpenCluster]) -> None:
    print("open clusters:")
    for i, cluster in enumerate(clusters):
        print(
            f"  open[{i}] {cluster.start_ms/1000:.2f}..{cluster.end_ms/1000:.2f}s "
            f"duration={cluster.duration_ms/1000:.2f}s n={cluster.count}"
        )

    print("cycles:")
    for cycle in sorted(c for c in wide["cycle"].unique() if c > 0):
        rows = wide[wide["cycle"] == cycle]
        print(
            f"  cycle {cycle}: {rows.elapsed_ms.min()/1000:.2f}..{rows.elapsed_ms.max()/1000:.2f}s "
            f"angle={rows.reference_angle_deg.min():.0f}..{rows.reference_angle_deg.max():.0f} "
            f"rows={len(rows)}"
        )

    print("open-pose game-rotation distance (deg):")
    baselines: dict[int, Rotation] = {}
    for cycle in sorted(c for c in wide["cycle"].unique() if c > 0):
        row = wide[wide["cycle"] == cycle].iloc[0]
        q = row[["baseline_qx", "baseline_qy", "baseline_qz", "baseline_qw"]].to_numpy(float)
        if np.isfinite(q).all():
            baselines[cycle] = Rotation.from_quat(q)
    for left in baselines:
        values = []
        for right in baselines:
            angle = np.degrees((baselines[right] * baselines[left].inv()).magnitude())
            values.append(f"c{right}={angle:5.1f}")
        print(f"  c{left}: " + " ".join(values))


def per_degree_samples(wide: pd.DataFrame, max_reference_age_ms: float) -> pd.DataFrame:
    valid = wide[
        (wide["cycle"] > 0)
        & wide["direction"].isin(["closing", "opening"])
        & wide["reference_age_ms"].between(-50.0, max_reference_age_ms)
    ].copy()
    valid["angle_bin"] = valid["reference_angle_deg"].round().astype(int)
    numeric = valid.select_dtypes(include=[np.number]).columns.tolist()
    group_cols = ["cycle", "direction", "angle_bin"]
    numeric = [c for c in numeric if c not in group_cols]
    agg = valid.groupby(group_cols, as_index=False)[numeric].median()
    agg["target"] = agg["reference_angle_deg"]
    return agg


def metrics(y_true: np.ndarray, y_pred: np.ndarray) -> tuple[float, float, float, float]:
    error = np.abs(y_pred - y_true)
    return (
        float(error.mean()),
        float(np.median(error)),
        float(np.percentile(error, 95)),
        float(error.max()),
    )


FEATURE_SETS: dict[str, list[str]] = {
    "public": ["public_hinge"],
    "cal_xyz": ["mc_0", "mc_1", "mc_2"],
    "uncal_norm": ["mu_norm"],
    "uncal_xyz": ["mu_x", "mu_y", "mu_z"],
    "uncal_xyz+public": ["mu_x", "mu_y", "mu_z", "public_hinge"],
    "uncal_xyz+norm+public": ["mu_x", "mu_y", "mu_z", "mu_norm", "public_hinge"],
    "delta_xyz": ["dmu_x", "dmu_y", "dmu_z"],
    "delta_xyz+public": ["dmu_x", "dmu_y", "dmu_z", "public_hinge"],
    "delta+norm+public": ["dmu_x", "dmu_y", "dmu_z", "dmu_norm", "public_hinge"],
    "delta+gravity+public": [
        "dmu_x",
        "dmu_y",
        "dmu_z",
        "grav_0",
        "grav_1",
        "grav_2",
        "public_hinge",
    ],
    "earthcorr_xyz": ["ec_dmu_x", "ec_dmu_y", "ec_dmu_z"],
    "earthcorr+norm+public": [
        "ec_dmu_x",
        "ec_dmu_y",
        "ec_dmu_z",
        "ec_dmu_norm",
        "public_hinge",
    ],
    "delta+relative-rotation+public": [
        "dmu_x",
        "dmu_y",
        "dmu_z",
        "rel_rot_x",
        "rel_rot_y",
        "rel_rot_z",
        "public_hinge",
    ],
    "all-static-public": [
        "dmu_x",
        "dmu_y",
        "dmu_z",
        "dmu_norm",
        "grav_0",
        "grav_1",
        "grav_2",
        "rel_rot_x",
        "rel_rot_y",
        "rel_rot_z",
        "public_hinge",
    ],
}


def model_factories() -> dict[str, object]:
    return {
        "knn": lambda: make_pipeline(
            StandardScaler(),
            KNeighborsRegressor(n_neighbors=5, weights="distance"),
        ),
        "poly2-ridge": lambda: make_pipeline(
            PolynomialFeatures(degree=2, include_bias=False),
            StandardScaler(),
            Ridge(alpha=3.0),
        ),
        "extra-trees": lambda: ExtraTreesRegressor(
            n_estimators=300,
            min_samples_leaf=2,
            random_state=7,
            n_jobs=-1,
        ),
        "hist-gb": lambda: HistGradientBoostingRegressor(
            max_iter=250,
            max_leaf_nodes=15,
            learning_rate=0.06,
            l2_regularization=1.0,
            random_state=7,
        ),
    }


def evaluate_split(
    samples: pd.DataFrame,
    train_cycles: list[int],
    test_cycles: list[int],
    label: str,
) -> pd.DataFrame:
    train = samples[samples["cycle"].isin(train_cycles)]
    test = samples[samples["cycle"].isin(test_cycles)]
    records: list[dict[str, object]] = []

    for feature_name, features in FEATURE_SETS.items():
        if feature_name == "public":
            subset = test.dropna(subset=features + ["target"])
            if subset.empty:
                continue
            result = metrics(subset["target"].to_numpy(), subset["public_hinge"].to_numpy())
            records.append(
                dict(split=label, model="identity", features=feature_name, n=len(subset), **metric_dict(result))
            )
            continue

        train_clean = train.dropna(subset=features + ["target"])
        test_clean = test.dropna(subset=features + ["target"])
        if len(train_clean) < 20 or len(test_clean) < 20:
            continue
        x_train = train_clean[features].to_numpy(float)
        y_train = train_clean["target"].to_numpy(float)
        x_test = test_clean[features].to_numpy(float)
        y_test = test_clean["target"].to_numpy(float)

        for model_name, factory in model_factories().items():
            model = factory()
            model.fit(x_train, y_train)
            pred = model.predict(x_test)
            result = metrics(y_test, pred)
            records.append(
                dict(
                    split=label,
                    model=model_name,
                    features=feature_name,
                    n=len(test_clean),
                    **metric_dict(result),
                )
            )
    return pd.DataFrame.from_records(records)


def metric_dict(values: tuple[float, float, float, float]) -> dict[str, float]:
    mae, median, p95, worst = values
    return {"mae": mae, "median": median, "p95": p95, "worst": worst}


def hysteresis_summary(samples: pd.DataFrame) -> None:
    print("hysteresis / repeatability (uncalibrated magnetic vector):")
    for cycle in sorted(samples["cycle"].unique()):
        rows = samples[samples["cycle"] == cycle]
        closing = rows[rows["direction"] == "closing"].set_index("angle_bin")
        opening = rows[rows["direction"] == "opening"].set_index("angle_bin")
        common = closing.index.intersection(opening.index)
        if len(common) < 3:
            continue
        delta = (
            closing.loc[common, ["mu_x", "mu_y", "mu_z"]].to_numpy()
            - opening.loc[common, ["mu_x", "mu_y", "mu_z"]].to_numpy()
        )
        distance = np.linalg.norm(delta, axis=1)
        print(
            f"  cycle {cycle}: common_angles={len(common)} "
            f"median_vector_gap={np.median(distance):.1f}uT "
            f"p95={np.percentile(distance,95):.1f}uT max={distance.max():.1f}uT"
        )


def svr_group_search(samples: pd.DataFrame) -> pd.DataFrame:
    feature_sets = {
        name: FEATURE_SETS[name]
        for name in [
            "uncal_xyz",
            "uncal_xyz+public",
            "delta+norm+public",
            "delta+gravity+public",
            "all-static-public",
        ]
    }
    cycles = sorted(int(c) for c in samples["cycle"].unique())
    records: list[dict[str, object]] = []
    for feature_name, features in feature_sets.items():
        for c_value in [10.0, 30.0, 100.0, 300.0]:
            for gamma in ["scale", 0.03, 0.1, 0.3]:
                for epsilon in [0.5, 1.5, 3.0]:
                    fold_metrics = []
                    support_counts = []
                    for test_cycle in cycles:
                        train = samples[samples["cycle"] != test_cycle].dropna(
                            subset=features + ["target"]
                        )
                        test = samples[samples["cycle"] == test_cycle].dropna(
                            subset=features + ["target"]
                        )
                        if len(train) < 20 or len(test) < 10:
                            continue
                        model = make_pipeline(
                            StandardScaler(),
                            SVR(C=c_value, gamma=gamma, epsilon=epsilon),
                        )
                        model.fit(train[features].to_numpy(float), train["target"].to_numpy(float))
                        pred = model.predict(test[features].to_numpy(float))
                        fold_metrics.append(metrics(test["target"].to_numpy(float), pred))
                        support_counts.append(len(model.named_steps["svr"].support_))
                    if len(fold_metrics) != len(cycles):
                        continue
                    metric_array = np.asarray(fold_metrics)
                    records.append(
                        {
                            "features": feature_name,
                            "C": c_value,
                            "gamma": gamma,
                            "epsilon": epsilon,
                            "mae": float(metric_array[:, 0].mean()),
                            "median": float(metric_array[:, 1].mean()),
                            "p95": float(metric_array[:, 2].mean()),
                            "worst": float(metric_array[:, 3].max()),
                            "support": float(np.mean(support_counts)),
                        }
                    )
    return pd.DataFrame.from_records(records).sort_values(["mae", "p95"])


def print_final_svr_params(samples: pd.DataFrame) -> None:
    features = ["mu_x", "mu_y", "mu_z"]
    train = samples.dropna(subset=features + ["target"])
    scaler = StandardScaler()
    x = scaler.fit_transform(train[features].to_numpy(float))
    # Prefer the smoother compact point on the group-CV Pareto front: it is only
    # ~0.25 degree MAE behind the best search result while using materially fewer
    # support vectors and slightly improving the held-out error tail.
    model = SVR(C=100.0, gamma="scale", epsilon=1.5)
    model.fit(x, train["target"].to_numpy(float))

    def kotlin_array(name: str, values: np.ndarray) -> None:
        text = ", ".join(f"{float(value):.9g}f" for value in values.ravel())
        print(f"private val {name} = floatArrayOf({text})")

    print(
        f"// trained rows={len(train)} support={len(model.support_)} "
        f"gamma={float(model._gamma):.12g}"
    )
    kotlin_array("FEATURE_MEAN", scaler.mean_)
    kotlin_array("FEATURE_SCALE", scaler.scale_)
    print(f"private const val RBF_GAMMA = {float(model._gamma):.12g}f")
    print(f"private const val INTERCEPT = {float(model.intercept_[0]):.12g}f")
    kotlin_array("SUPPORT_VECTORS", model.support_vectors_)
    kotlin_array("DUAL_COEFFICIENTS", model.dual_coef_[0])

    payload_values = np.concatenate(
        [
            scaler.mean_.astype(np.float32),
            scaler.scale_.astype(np.float32),
            np.asarray([model._gamma, model.intercept_[0]], dtype=np.float32),
            model.support_vectors_.astype(np.float32).ravel(),
            model.dual_coef_[0].astype(np.float32),
        ]
    )
    payload = struct.pack("<I", len(model.support_)) + struct.pack(
        f"<{len(payload_values)}f", *payload_values
    )
    print(f"private const val MODEL_HEX = \"{payload.hex()}\"")


def calibrated_bias_summary(wide: pd.DataFrame) -> None:
    print("magnetometer calibration behavior:")
    print(
        f"  calibrated |B| median={wide.mc_norm.median():.1f}uT "
        f"p95={wide.mc_norm.quantile(.95):.1f}uT max={wide.mc_norm.max():.1f}uT"
    )
    print(
        f"  uncalibrated |B| median={wide.mu_norm.median():.1f}uT "
        f"p95={wide.mu_norm.quantile(.95):.1f}uT max={wide.mu_norm.max():.1f}uT"
    )
    print(
        f"  reported bias norm median={wide.mag_bias_norm.median():.1f}uT "
        f"p95={wide.mag_bias_norm.quantile(.95):.1f}uT max={wide.mag_bias_norm.max():.1f}uT"
    )


def main() -> None:
    args = parse_args()
    df = read_capture(args.csv, args.exact_csv)
    wide = build_wide(df)
    clusters = find_open_clusters(wide)
    if len(clusters) < 2:
        raise SystemExit("need at least two full-open clusters to identify a fold cycle")
    wide = assign_cycles(wide, clusters)
    calibrated_bias_summary(wide)
    print_cycle_summary(wide, clusters)

    samples = per_degree_samples(wide, args.max_reference_age_ms)
    print(f"per-degree validation rows={len(samples)}")
    for cycle in sorted(samples["cycle"].unique()):
        rows = samples[samples["cycle"] == cycle]
        by_direction = rows.groupby("direction")["angle_bin"].agg(["count", "min", "max"])
        print(f"cycle {cycle} degree coverage:\n{by_direction.to_string()}")
    hysteresis_summary(samples)

    if args.print_svr_params:
        print_final_svr_params(samples)
        return

    cycles = sorted(int(c) for c in samples["cycle"].unique())
    reports = []
    for test_cycle in cycles:
        train_cycles = [c for c in cycles if c != test_cycle]
        reports.append(
            evaluate_split(samples, train_cycles, [test_cycle], f"LOCO test c{test_cycle}")
        )
    if len(cycles) >= 4:
        reports.append(evaluate_split(samples, cycles[:2], cycles[2:], "c1+c2 -> c3+c4"))
        reports.append(evaluate_split(samples, cycles[:2], [cycles[2]], "c1+c2 -> c3"))
        reports.append(evaluate_split(samples, cycles[:2], [cycles[3]], "c1+c2 -> c4"))
        reports.append(evaluate_split(samples, [cycles[2]], [cycles[3]], "c3 -> c4"))
        reports.append(evaluate_split(samples, [cycles[0]], [cycles[1]], "c1 -> c2"))

    report = pd.concat(reports, ignore_index=True)
    print("\nBest validation rows by split (sorted by MAE):")
    for split in report["split"].drop_duplicates():
        block = report[report["split"] == split].sort_values(["mae", "p95"]).head(12)
        print(f"\n[{split}]")
        print(
            block[["model", "features", "n", "mae", "median", "p95", "worst"]].to_string(
                index=False,
                float_format=lambda x: f"{x:.2f}",
            )
        )

    svr_report = svr_group_search(samples)
    print("\nSVR group-CV search (whole-cycle folds):")
    print(
        svr_report.head(15).to_string(
            index=False,
            float_format=lambda x: f"{x:.2f}",
        )
    )


if __name__ == "__main__":
    main()
