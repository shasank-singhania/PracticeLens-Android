# PracticeLens Android

PracticeLens is a foreground-camera Android practice assistant for self-study, mock tests, formative assessment, revision, and authorized practice material.

It is not a live-exam assistant, cross-application answer tool, proctoring-evasion tool, overlay, automation utility, or APK updater.

## Product Boundary

PracticeLens uses only the in-app foreground rear camera. It does not declare or implement AccessibilityService, MediaProjection, screenshots, system overlays, floating windows, clipboard monitoring, notification listener capture, automated taps, package installation, or background camera capture.

Default learner flow:

1. Open PracticeLens and grant camera permission.
2. Frame one visible multiple-choice question with the rear camera and tap `Capture question`.
3. Freeze a private still image, stop camera analysis, and confirm the crop.
4. Run on-device OCR on the confirmed crop and let the learner correct the question/options.
5. Optionally analyze the confirmed crop with production Firebase AI image interpretation.
6. Let the learner confirm the editable question, then choose and revise an answer.
7. Evaluate only after the selected option remains unchanged through the grace period.
8. Show correctness, explanation, warning text, history, and a stoppable auto-next countdown.

## Variants

- `demo`: deterministic fake evaluator and offline/manual question review, no Firebase configuration, no Gemini calls, Drive disabled. Suitable for public CI and demo APK releases.
- `production`: Firebase AI Logic with App Check for confirmed-crop interpretation/evaluation, Google Identity authorization, Drive API backup, and production signing.

The demo debug APK application ID is `app.practicelens.android.autopractice.debug`.

State flow: `SCANNING -> CAPTURING -> CROP_REVIEW -> QUESTION_INTERPRETATION -> QUESTION_REVIEW -> ANSWERING -> GRACE_PERIOD -> EVALUATING -> RESULT` or `FAILED`. OCR is draft assistance only; camera capture never depends on OCR/parser validity.

## Build

```bash
./gradlew testDemoDebugUnitTest
./gradlew lintDemoDebug
./gradlew assembleDemoDebug
```

Install and launch the demo debug APK on a connected device:

```bash
adb install -r app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb shell am start -n app.practicelens.android.autopractice.debug/app.practicelens.android.MainActivity
```

Production release builds require protected signing and Firebase inputs. See `docs/RELEASE_SIGNING.md` and `docs/FIREBASE_SETUP.md`.

## License

GNU AGPL-3.0. See `LICENSE` and `ATTRIBUTION.md`.
