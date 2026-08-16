# PracticeLens Android

PracticeLens is a foreground-camera Android practice assistant for self-study, mock tests, formative assessment, revision, and authorized practice material.

It is not a live-exam assistant, cross-application answer tool, proctoring-evasion tool, overlay, automation utility, or APK updater.

## Product Boundary

PracticeLens uses only the in-app foreground rear camera. It does not declare or implement AccessibilityService, MediaProjection, screenshots, system overlays, floating windows, clipboard monitoring, notification listener capture, automated taps, package installation, or background camera capture.

Default learner flow:

1. Open PracticeLens and grant camera permission.
2. Scan one visible multiple-choice question with the rear camera.
3. Freeze the accepted frame and stop camera analysis.
4. Run on-device OCR and let the learner correct the question/options.
5. Let the learner choose and revise an answer.
6. Evaluate only after the selected option remains unchanged through the grace period.
7. Show correctness, explanation, warning text, history, and a stoppable auto-next countdown.

## Variants

- `demo`: deterministic fake evaluator, no Firebase configuration, no Gemini calls, Drive disabled. Suitable for public CI and demo APK releases.
- `production`: Firebase AI Logic with App Check, Google Identity authorization, Drive API backup, and production signing.

## Build

```bash
./gradlew testDemoDebugUnitTest
./gradlew lintDemoDebug
./gradlew assembleDemoDebug
```

Production release builds require protected signing and Firebase inputs. See `docs/RELEASE_SIGNING.md` and `docs/FIREBASE_SETUP.md`.

## License

GNU AGPL-3.0. See `LICENSE` and `ATTRIBUTION.md`.
