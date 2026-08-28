# Test Plan

Implemented unit coverage:

- disclosure routing before settings, permission, and camera access
- persisted disclosure acceptance and deterministic test reset
- answer revision reducer
- stale selection rejection
- result validation
- blur/exposure rejection
- stable-frame single acceptance
- automatic ImageAnalysis enablement with `STRATEGY_KEEP_ONLY_LATEST`
- analyzer frame closure after acceptance, rejection, metric failure, disposal, and busy drops
- automatic capture waits for camera readiness and minimum focus settle
- single automatic capture per stability event
- bounded 4-second fallback automatic capture with a quality warning
- capture error recovery and automatic retry
- first question result to changed second question result
- three complete consecutive automatic questions
- unchanged scene and one noisy changed frame do not recapture
- two consecutive changed observations re-arm exactly once
- fallback timer recreation on every automatic cycle
- second-cycle capture error recovery
- stale first/second-cycle capture callbacks cannot update the current cycle
- stop/background cancellation of pending automatic capture
- stop/background cancellation of scene polling
- no duplicate model request from duplicate capture callbacks
- no duplicate model request after recomposition or rotation
- accepted analysis fingerprint is preferred for scene comparison
- portrait, landscape, and Auto rotation propagation policy
- rotation changes during an automatic session do not duplicate or stop the loop
- captured image rotation/fingerprint helper math
- UTF-8 OCR bullet and math-symbol regression literals
- tolerant OCR stability across whitespace and punctuation drift
- slow-device OCR cadence inside the stability window
- manual acceptance of the latest readable OCR frame
- manual capture enters crop review without OCR validity
- invalid OCR after crop remains editable and does not resume scanning
- confirmed question keeps crop media and app-owned option IDs
- crop bounds and rotation dimension math
- interpretation JSON status/options/confidence validation
- evaluator JSON option-ID/confidence validation
- single transient evaluator retry and image delivery to the gateway
- same-question deduplication
- Drive scope/default policy
- worker camera-dependency guard

Required device/emulator coverage before release:

- install signed production APK with `adb install -r`
- rear camera only in foreground
- manual high-resolution `Capture question`
- disclosure appears before settings or camera permission prompts on a clean install
- automatic practice starts CameraX preview plus analysis after disclosure and permission
- automatic practice captures once after a stable/acceptable frame without requiring OCR text
- after the first result, changing to a second and third MCQ triggers exactly one new capture per question
- leaving the same MCQ framed does not trigger another capture
- automatic practice submits a fallback still within 4 seconds when no stable frame arrives
- rotate the device in Auto, Portrait, and Landscape settings and verify Preview, capture, and answer submission continue
- stop the session and background the app during focusing/capture; return and verify no stale duplicate capture or model request appears
- frozen still crop review with move/resize, rotate, reset, full image, retake, and confirm
- OCR-assisted question review and correction after crop confirmation
- visible blur, exposure, motion, and framing guidance
- production `Analyze image with AI` only after crop confirmation
- answer revision during grace period
- Gemini evaluation after confirmed question and locked answer
- stoppable five-second auto-next

Planned device coverage after the surfaces are wired:

- local history across update
- explicit Drive consent and backup
# Local Real-Image OCR Regression

Real captured question images must stay local and uncommitted. Put developer-only fixtures in `local-ocr-fixtures/`; this directory is gitignored. Optional OCR sidecars can be added beside each image as `<image-name>.jpg.ocr.txt`.

Run:

```powershell
python tools\real_image_regression.py
```

The report is written to `real-image-regression-reports/latest.json`, also gitignored. By default it records fixture ID, file name, dimensions, OCR character count from the optional sidecar, parser validity, detected option count, processing latency, rejection category, and whether manual review remains reachable. It does not upload images or log complete question text. For local debugging only, add `--include-ocr-text`.
