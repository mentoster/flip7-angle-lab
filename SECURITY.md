# Security and privacy

## Reporting a security issue

Please do **not** post credentials, personal device logs, pairing data or
other sensitive information in a public GitHub issue.

If a future public repository enables GitHub's private vulnerability
reporting feature, use **Security → Report a vulnerability**. Otherwise,
first open a minimal issue asking for a private reporting channel, without
including exploit details, keys or sensitive logs.

## Sensitive Android permissions

Flip7 Angle Lab can estimate a hinge angle using ordinary Android sensors,
without root or ADB. An **optional developer-only reference path** uses
`READ_LOGS` and local Wireless ADB to access the more precise Samsung
folding-angle reading.

`READ_LOGS` is sensitive: device logs may contain personal information.
Only enable it on devices you control. Android may ask for one-time permission
for each session. The project does not require this permission for ordinary
offline predictions.

## Data handling

Adaptive calibration data is stored in app-private storage; recorded CSVs are
exported to the device's Downloads directory when requested. Such captures
can contain timestamps, device information and sensor vectors.
Review and minimize this data before sharing it. Large local capture
collections and build files are excluded by `.gitignore`.

Never commit release keystores, signing passwords, environment credentials,
ADB pairing material or private captures to GitHub.
