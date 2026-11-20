# Flip7 Hinge Angle SDK v0.1.0

**Standalone Android AAR with the pretrained Galaxy Z Flip7 Boost v2 model.**

This is an SDK/library release for embedding continuous hinge-angle
estimation into **other** Android applications at build time. It is
independent of the Flip7 Angle Lab APK release version.

## One-line Gradle integration

Add the repository in your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://raw.githubusercontent.com/mentoster/flip7-angle-lab/maven")
    }
}
```

Then add to your app module:

```kotlin
implementation("com.mentoster:flip7-hinge-sdk:0.1.0")
```

No GitHub credentials are required to download the public Maven
artifact. You can also download `flip7-hinge-sdk-0.1.0.aar` below
and use `implementation(files("libs/flip7-hinge-sdk-0.1.0.aar"))`.

See the [complete SDK guide](https://github.com/mentoster/flip7-angle-lab/blob/main/docs/sdk.md)
for Kotlin and Java API usage, lifecycle handling, raw model predictions
and the independently buildable sample APK.

## What it contains

- Bundled **120-tree gradient-boosted Boost v2 model**; runs offline.
- Simple `Flip7HingePredictor.from(context).predict(...)` API to evaluate
  a supplied sensor vector without registering sensors.
- `Flip7HingeSensor(context).start { reading -> ... }` to subscribe
  automatically to public gravity, uncalibrated magnetic field, gyro and
  coarse Android hinge sensors. Updates arrive on the Android main
  thread, with the model evaluated on a worker thread.
- Explicit `LIVE_ESTIMATE`, `HELD_DURING_ROTATION` and
  `WAITING_FOR_SENSORS` statuses. Raw ML output remains available when
  the displayed estimate is held during device rotation.
- **No ADB, root, Samsung private `SSENSOR`, READ_LOGS,
  `HIGH_SAMPLING_RATE_SENSORS`, internet or remote servers.**
- Java 17 bytecode, Android minSdk 33. No Compose dependencies.

## Physical limitations

Model training and real-device QA were specific to
**Galaxy Z Flip7 SM-F766B**. The algorithm is not guaranteed
accurate on other foldables, across magnetic environments, or during
simultaneous body rotation and folding. A held reading is a previous
estimate — **not a new physical measurement**. The coarse public
hinge sensor is not a precise 0–180° measurement.

## Verified checks

- 5 new SDK unit tests plus 86 existing application unit tests:
  **91 passed, zero failures**.
- Independent sample APK installed and tested on a physical
  **SM-F766B**, receiving both live and held readings and reacting
  to a real coarse hinge-state change. The initial sample exposed a
  required permission for `SENSOR_DELAY_FASTEST`; the final SDK uses
  permission-free `SENSOR_DELAY_GAME` instead and passed the
  subsequent on-device test.
- Android library AAR contains compiled classes and the embedded
  `flip7_hinge_boost_v2.b64` model; Android manifest declares
  **no sensitive permissions**.
- Original Flip7 Angle Lab's private 1,500-example calibration cache
  remains intact. CI/CD is intentionally disabled.

The **original app release signing key is not part of this AAR** and
remains stored privately on the build laptop. A library AAR is not
an APK and does not need an Android app-signing certificate.
