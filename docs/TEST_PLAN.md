# Test Plan

Implemented unit coverage:

- answer revision reducer
- stale selection rejection
- result validation
- blur/exposure rejection
- stable-frame single acceptance
- same-question deduplication
- Drive scope/default policy
- worker camera-dependency guard

Required device/emulator coverage before release:

- install signed production APK with `adb install -r`
- rear camera only in foreground
- OCR review and correction
- answer revision during grace period
- Gemini evaluation after commitment
- stoppable five-second auto-next
- local history across update
- explicit Drive consent and backup
