# Contributing to Flip7 Angle Lab

Thanks for helping improve continuous hinge-angle estimation on foldable
Android devices.

## Before filing an issue

Please specify the device model, Android/One UI version, application version
and whether you're describing the **Samsung reference**, **offline MLP**,
**adaptive kNN**, or **coarse Android hinge** reading. These are different
sources and should not be mixed when reporting errors.

Useful steps to reproduce include the approximate hinge angle, phone
orientation, whether the hinge moved, whether the adaptive estimator was in
`strict`, `orientation-tolerant`, `held` or `coarse` mode, and whether a
Samsung reference source was available.

**Do not paste unfiltered `adb logcat` output.** Android device logs can
contain personal information, identifiers or tokens. Prefer the limited app
tag `Flip7AngleLab`, inspect it locally, and redact anything sensitive.
Do not attach raw calibration CSVs without reviewing their contents and
consenting to make them public.

## Local development

The supported device for physical hinge validation is currently Samsung
Galaxy Z Flip7 (SM-F766B). Build prerequisites are in
[README.md](README.md#quick-start).

```bash
bash scripts/gradle-local.sh :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

There is also an emulator-only smoke suite, but emulator success is not
evidence of physical hinge accuracy. Run relevant unit tests after changing
model logic, and add regression tests for new bugs.

Sensor recording uses the public Android `Sensor.type` identifier.
The legacy `sensor_handle` CSV field is set to `-1` on modern Android,
because the actual handle is not exposed by the public SDK. Do not restore
blocked reflection against `Sensor.getHandle`, `mHandle` or
`getRequiredPermission`; both Python training scripts use `type` and
continue to support older recordings that contain that field.

## Model contributions

- Test predictions on **entire unseen sessions/orientations**, not just
  randomized rows drawn from the same recording.
- Compare raw predicted angles with raw Samsung labels. Do not train against
  the endpoint-normalized display value.
- Record MAE, high-percentile errors, model coverage, and how often the app
  holds or falls back when matching fails.
- Keep the offline no-ADB inference path working.
- Avoid committing original physical-device captures, personal logs,
  keystores, APKs or Gradle build output.

## License and attribution

Contributions to this repository are submitted under the project's
[MIT License](LICENSE), unless another agreement is explicitly stated.
Only contribute code or data you have the right to redistribute, and retain
original third-party copyright and license notices.
