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
- same-question deduplication
- Drive scope/default policy
- worker camera-dependency guard

Required device/emulator coverage before release:

- install signed production APK with `adb install -r`
- rear camera only in foreground
- OCR review and correction
- automatic scanner completion on a stable MCQ page
- visible blur, exposure, motion, and OCR-readability guidance
- `Use current OCR` fallback after readable text is detected
- answer revision during grace period
- Gemini evaluation after commitment
- stoppable five-second auto-next
- local history across update
- explicit Drive consent and backup
