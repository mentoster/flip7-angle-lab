# Research methodology — measuring hinge angle without ADB

## Question and hypothesis

Can an unprivileged Android application estimate the physical 0–180°
hinge angle of a Samsung Galaxy Z Flip7 (SM-F766B), **without** ADB, root,
private APIs, or Samsung's privileged sensors?

**Hypothesis:** changes in a phone's publicly accessible magnetometer,
gravity and coarse Android hinge state contain information about mechanical
hinge movement. The observation is not unique: turning the *entire* phone
changes many of those readings too. Our research evaluates both predictive
accuracy and the cost of avoiding false hinge motion.

Two deliberately separate paths are implemented:

1. **Ordinary no-ADB operation:** Android magnetometer, gravity and gyro,
   with the public coarse hinge sensor, feed an embedded Boost model.
   An optional device-local kNN corrects the estimate using previously
   acquired labels. Temporal stabilization and a rotation guard distinguish
   the last **held estimate** from a new model reading. No computer,
   external service or READ_LOGS permission is required for this path.
2. **Reference-assisted development:** Samsung's precise angle may be
   accessed through an authorized SensorsHub diagnostic stream or
   developer ADB. This provides labeled ground truth and a separate
   comparison display. It is **not** an input to the ordinary Boost model.

The tested firmware exposes public coarse hinge states near 0°, 90° and
180°. Its precise proprietary Folding Angle sensor and second-half
accelerometer require privileged `SSENSOR` permissions. They cannot
be assumed available to ordinary applications. In particular, a
reported public 180° does **not** prove a precise 180° physical angle.

## Feature sets and model

The deployed Boost v2 ensemble contains 120 small regression trees,
evaluated directly by the Android Kotlin interpreter. Its 10 inputs:

- magnetic field X, Y, Z from the standard Android magnetometer;
- gravity X, Y, Z;
- magnetic field magnitude;
- projection of the magnetic field along gravity;
- magnitude of the magnetic component perpendicular to gravity;
- coarse public Android hinge value.

The separately trained k-nearest-neighbor correction layer can retain
up to 1,500 Samsung-labeled sensor samples in app-private storage. It is
not an additional universal pretrained model.

During a detected whole-device rotation, the app can **hold** its last
accepted hinge angle instead of displaying a pose-contaminated prediction.
This avoids certain false movements but also creates a measurable
coverage/latency cost: slow simultaneous turning and folding can be
ambiguous. **A held result is not a new physical measurement.**

## Data and evaluation

Two internal datasets were used during development; the raw data is
private and is **not** included in GitHub:

| Dataset | Rows | Description |
| --- | ---: | --- |
| Recent device-local calibration cache | 1,500 | Samsung-reference-labeled magnetic XYZ, gravity XYZ, coarse hinge, exact reference |
| Earlier multi-orientation sensor capture | 4,440 | Labeled readings in six named orientations: portrait, inverted portrait, both landscapes, face-up, face-down |

### Bundled Boost v2

The v2 training script performed three gravity-pose-group holdouts on
the recent 1,500-row device-local cache. Combined results across
**1,414 validation predictions** were **MAE 7.17° and P90 16.11°**.
The worst individual errors exceeded 50°. This is grouped testing
within **one device**, not an independent capture-session benchmark
or proof of Samsung-equivalent measurement.

### Rotation-related feature experiments

An experimental 100-tree `HistGradientBoostingRegressor`, with maximum
12 leaves, learning rate 0.07 and L2 regularization 3, evaluated
feature alternatives on the earlier 4,440-row capture. Each physical
orientation was held out in turn:

| Feature family | MAE | P90 |
| --- | ---: | ---: |
| Magnetic magnitude, coarse hinge | 22.88° | 58.88° |
| Magnetic magnitude, gravity projection and perpendicular magnitude, coarse hinge | 17.67° | 45.14° |
| Original XYZ/gravity-derived inputs and coarse hinge | 10.36° | 28.11° |

These numbers describe a **feature experiment**, not a comparison of
separately deployed APK versions. Rotation-invariant magnitude alone
performed considerably worse than the full pose-sensitive inputs.

Cross-session domain shift was also substantial: when magnitude-only
models were trained on the archived recording and evaluated on the
newer cache, MAE was **37.03°**; in the opposite direction it was
**40.31°**. The magnetic magnitude near a physically open hinge
was roughly **352–442 µT** in the archive versus **153–215 µT**
in recent calibration examples. A generic normalization or static
offset does not reliably solve this shift.

## Reproduce from your own data

The **unit tests, static checks and APK build** need no attached phone:

```bash
bash scripts/gradle-local.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

They check source parsing, model serialization, rotation guards and
output stability, not independent physics. For supervised validation
and retraining, collect your **own consented reference labels**;
the archived private captures cannot be reconstructed from the model
weights. When an eight-column calibration cache is available:

```bash
uv run --with numpy --with scikit-learn \
  python scripts/train-release-angle-model.py --validate --emit \
  < your-own-calibration.csv
```

The legacy MLP research can also be reproduced using newly collected
sensor and reference CSVs with
`scripts/train-calibration-v2-mlp.py`. Neither script uploads sensor
data by itself.

For stronger independent validation, keep **entire sessions, complete
fold/unfold cycles and individual devices** out of training.
Report MAE, P90, maximum absolute error, response latency, and the
percentage of time a value is explicitly **held or uncertain**.
Separately measure the performance of the raw model and displayed
angle, because post-processing can disguise model errors.

## Suggested falsification tests

1. Hold the physical hinge fixed at about 90°, 155° and 177°.
   Rotate the entire phone in yaw, pitch and roll. Compare the raw
   model, displayed angle and independently measured Samsung label.
2. Keep the body orientation fixed. Slowly fold and unfold across
   transitions of the coarse public Android hinge state.
3. Fold the hinge **while rotating the entire phone**. This is the
   difficult non-identifiable case for a single-half motion sensor.
4. Repeat in different locations and near different magnetic
   environments. Watch for domain shift and falsely stable held values.

The core unresolved issue is **observability**: the accessible signals
do not always uniquely encode a hinge angle under unconstrained
orientation and environmental magnetic interference. This project
does not claim to remove that physical limitation.

## Data privacy and licensing

Training captures can encode movement, timestamps and device metadata.
They are excluded from version control. The portable trained model
artifact is published; raw samples and pairing credentials are not.
Developer-only `READ_LOGS` access can expose sensitive phone logs and
must never be silently enabled for a user.

This is independent, experimental research, not a Samsung product
or a certified angle-measuring instrument.
