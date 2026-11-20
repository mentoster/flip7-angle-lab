# Local calibration recordings

This folder holds **private, device-generated sensor captures** used while
developing Flip7 Angle Lab. The original recordings are intentionally **not
part of the public repository**.

Raw captures can be very large and may contain timestamps, device metadata,
sensor readings and other contextual information. Do not publish a capture
without reviewing, minimizing and obtaining appropriate consent for its
contents.

To reproduce model training, record your own Samsung-labeled data on a Galaxy
Z Flip7 via the app's **Start calibration** workflow and put the resulting
`*-sensors.csv` and `*-exact.csv` files in this directory. The training
script is described in the project [README](../README.md).

The `.gitignore` excludes captures and CSV files by default; this README
remains tracked.
