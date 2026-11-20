# Flip7 Hinge Angle SDK — integrate into another Android APK

The experimental Android SDK is a **precompiled AAR** with 120-tree Boost
v2 weights included in its assets. It is built from
[`hinge-angle-sdk/`](../hinge-angle-sdk), uses public Android sensors,
does **not** require ADB, root, READ_LOGS, Samsung SSENSOR, internet,
API keys or a cloud server, and targets Galaxy Z Flip7 (SM-F766B).

It is a build-time **Android library**, not a plug-in that can be inserted
into an already signed APK without rebuilding it. Other Android apps
can depend on the AAR and invoke its public Kotlin/Java API.

## 1. Gradle dependency (public, no login)

Add the public Maven repository to your other APK project's
`settings.gradle.kts`, in its existing dependency repository block:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://raw.githubusercontent.com/mentoster/flip7-angle-lab/maven")
    }
}
```

Then in that APK module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.mentoster:flip7-hinge-sdk:0.1.0")
}
```

The Maven artifact includes Kotlin stdlib dependency metadata, Android
manifest, compiled code, model file and versioned sources. It is
hosted on this repository's manually maintained `maven` branch, with
**no GitHub token required**. Published versions are immutable.

Alternative: download the
[`flip7-hinge-sdk-0.1.0.aar`](https://github.com/mentoster/flip7-angle-lab/releases/tag/sdk-v0.1.0)
from GitHub Releases, put it into your app's `libs/` and use
`implementation(files("libs/flip7-hinge-sdk-0.1.0.aar"))`. With
direct AAR use, your app must also provide Kotlin stdlib (normally
already present in Kotlin Android applications).

## 2. Automatic angle stream (recommended)

The observer starts its own Android sensor worker, evaluates the model
off the UI thread and reports updates on the main thread (~30 fps):

```kotlin
import com.mentoster.flip7angle.sdk.Flip7HingeSensor
import com.mentoster.flip7angle.sdk.HingeAngleStatus

class MyActivity : Activity() {
    private lateinit var hinge: Flip7HingeSensor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hinge = Flip7HingeSensor(this)
    }

    override fun onStart() {
        super.onStart()
        val started = hinge.start { reading ->
            val angle = reading.angleDegrees
            when (reading.status) {
                HingeAngleStatus.LIVE_ESTIMATE -> {
                    // New experimental model estimate, e.g. 113.4°
                    if (angle != null) showHingeAngle(angle)
                }
                HingeAngleStatus.HELD_DURING_ROTATION -> {
                    // Angle has NOT been remeasured. Label as held.
                    showHeldHingeAngle(angle)
                }
                HingeAngleStatus.WAITING_FOR_SENSORS -> showUnavailable()
            }
            // reading.rawModelDegrees is always the fresh ML output
            // (may be inaccurate during whole-phone rotation).
            // reading.coarseHingeDegrees is Android's 0/90/180 state.
        }
        if (!started) {
            // Required public sensors are unavailable; see hinge.availability.
            showUnavailable()
        }
    }

    override fun onStop() {
        hinge.stop()
        super.onStop()
    }
}
```

The receiver reports `HingeAngleReading`:

| Property | Meaning |
| --- | --- |
| `angleDegrees: Float?` | Current estimate 0–180; null until ready |
| `rawModelDegrees: Float?` | Model-only result before rotation protection |
| `status: HingeAngleStatus` | `LIVE_ESTIMATE`, `HELD_DURING_ROTATION`, `WAITING_FOR_SENSORS` |
| `coarseHingeDegrees: Float?` | Android public sensor state, not precise hinge measurement |
| `timestampNanos: Long` | Original magnetometer sensor timestamp |
| `reason: String` | Diagnostic heuristic; never physical ground truth |
| `testedOnDevice: Boolean` | Whether this is the SM-F766* device family |

`hinge.availability` tells you which public sensors are present;
`hinge.latest` returns the last reading; `stop()` unregisters all
listeners and closes the sensor worker. Never leave an observer running
without a lifecycle owner. On some Android builds, the screen must be
awake to keep receiving non-wakeup sensor events.

## 3. Direct model prediction without SensorManager

If your app already has a sensor-fusion pipeline, you can call the
model directly instead:

```kotlin
import com.mentoster.flip7angle.sdk.Flip7HingePredictor

val boost = Flip7HingePredictor.from(applicationContext)
val predictedDegrees: Float? = boost.predict(
    magneticX = mag[0], magneticY = mag[1], magneticZ = mag[2],
    gravityX = grav[0], gravityY = grav[1], gravityZ = grav[2],
    coarseHingeDegrees = androidPublicHinge,
)
```

**Direct predictions have no rotation protection.** Do not equate
`androidPublicHinge=180f` with a mechanically exact 180°. The model
was trained on Galaxy Z Flip7 and may have substantial error in a
different orientation or magnetic environment.

## 4. Tested in a second APK

The repository includes a separate, minimal
[`sample-app/`](../sample-app) with application ID
`com.mentoster.flip7anglesdk.sample`. Its APK uses the library API
and no privileged system access. Build and test:

```bash
bash scripts/gradle-local.sh :hinge-angle-sdk:testDebugUnitTest :sample-app:assembleDebug
adb -s YOUR_FLIP7_SERIAL install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb -s YOUR_FLIP7_SERIAL shell am start -n \
  com.mentoster.flip7anglesdk.sample/.SampleActivity
```

**Verified on SM-F766B:** the independent APK emitted
`LIVE_ESTIMATE` readings and `HELD_DURING_ROTATION` statuses as
the model changed, then resumed on the next public 90° hinge state.
The original Flip7 Angle Lab app and its private calibration examples
were untouched.

## 5. Reliability, limitations and publication

- The SDK contains no hidden Samsung reference, calibration CSV or
  user data. The AAR only includes model **thresholds/weights** and code.
- It requires public uncalibrated magnetometer, gravity, gyroscope and
  Android public hinge sensor; `start()` returns false when unavailable.
- Sensor registration uses `SENSOR_DELAY_GAME`, rather than the
  `SENSOR_DELAY_FASTEST` option that would require Android's
  `HIGH_SAMPLING_RATE_SENSORS` permission on the tested phone.
- It **cannot guarantee Samsung-grade precision**. A held output is a
  previous estimate, not new ground truth. Simultaneous slow hinge
  motion and phone rotation may be ambiguous.
- For detailed single-device benchmarks (including error distributions
  and magnetic session shift), see
  [research methodology](research-methodology.md).
- SDK versions use separate tags such as `sdk-v0.1.0` and remain
  independent of the application release `v0.1.13`.

Manual SDK publication (no CI/CD):

```bash
bash scripts/gradle-local.sh \
  :hinge-angle-sdk:testDebugUnitTest \
  :hinge-angle-sdk:publishReleasePublicationToLocalReleaseRepository
python scripts/publish-sdk-maven.py
```

The publisher uploads only public AAR, POM, Gradle module metadata and
checksums, not secret files or raw device observations.
