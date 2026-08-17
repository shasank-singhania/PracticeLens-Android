# Test Plan

Implemented unit coverage:

- answer revision reducer
- stale selection rejection
- result validation
- blur/exposure rejection
- stable-frame single acceptance
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
- frozen still crop review with move/resize, rotate, reset, full image, retake, and confirm
- OCR-assisted question review and correction after crop confirmation
- optional automatic scanner completion on visual stability only
- visible blur, exposure, motion, and OCR-readability guidance
- production `Analyze image with AI` only after crop confirmation
- answer revision during grace period
- Gemini evaluation after confirmed question and locked answer
- stoppable five-second auto-next
- local history across update
- explicit Drive consent and backup
