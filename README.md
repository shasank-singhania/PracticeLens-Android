# PracticeLens Android

PracticeLens is a foreground-camera Android practice assistant for self-study, mock tests, formative assessment, revision, and authorized practice material.

It is not a live-exam assistant, cross-application answer tool, proctoring-evasion tool, overlay, automation utility, or APK updater.

## Product Boundary

PracticeLens uses only the in-app foreground rear camera. It does not declare or implement AccessibilityService, MediaProjection, screenshots, system overlays, floating windows, clipboard monitoring, notification listener capture, automated taps, package installation, or background camera capture.

Default learner flow:

1. Accept the foreground camera disclosure before settings or camera access.
2. Choose automatic AI practice, manual capture, or manual crop review.
3. In automatic practice, start the rear camera, wait for readiness and focus settle, capture on an acceptable stable frame, or use a bounded 4-second fallback still with a quality warning.
4. Normalize captured JPEG orientation before fingerprinting and model submission.
5. In manual crop review, run on-device OCR on the confirmed crop and let the learner correct the question/options. OCR remains diagnostic/editable assistance only.
6. Optionally analyze the confirmed crop with production Firebase AI image interpretation.
7. Let the learner confirm the editable question, then choose and revise an answer.
8. Evaluate only after the selected option remains unchanged through the grace period.
9. Show correctness, explanation, warning text, and a stoppable auto-next countdown.

## Variants

- `demo`: deterministic fake evaluator and offline/manual question review, no Firebase configuration, no Gemini calls, Drive disabled. Suitable for public CI and demo APK releases.
- `production`: Firebase AI Logic with App Check for confirmed-crop interpretation/evaluation and production signing. History and Google Drive backup are planned surfaces until their user-facing wiring is completed.

The demo debug APK application ID is `app.practicelens.android.autopractice.debug`.

Manual state flow: `DISCLOSURE -> SETTINGS -> SCANNING -> CAPTURING -> CROP_REVIEW -> QUESTION_INTERPRETATION -> QUESTION_REVIEW -> ANSWERING -> GRACE_PERIOD -> EVALUATING -> RESULT` or `FAILED`.

Automatic state flow: `DISCLOSURE -> SETTINGS -> START -> CAMERA_STARTING -> WAITING_FOR_FOCUS -> CAPTURING -> PREPARING_IMAGE -> ANALYZING -> SHOWING_RESULT -> WAITING_FOR_SCENE_CHANGE -> WAITING_FOR_FOCUS -> CAPTURING`, repeated for each changed question. CameraX `ImageAnalysis` remains active while the answer is displayed and while waiting for scene change, uses `STRATEGY_KEEP_ONLY_LATEST`, and requires two consecutive materially changed scene fingerprints before re-arming the next capture. Still capture and model submission never depend on OCR/parser validity.

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
