# Contributing

Contributions must preserve the preparation-only boundary:

- No accessibility service, MediaProjection, screenshots, overlays, floating windows, clipboard automation, notification listener capture, automated input, background camera capture, or APK self-updater.
- Demo builds must require no private secrets.
- Production builds must use protected signing credentials and Firebase App Distribution only as external distribution.
- Drive backup must remain opt-in and limited to `drive.file`.

Run:

```bash
python tools/structural_guard.py
./gradlew testDemoDebugUnitTest lintDemoDebug assembleDemoDebug
```
