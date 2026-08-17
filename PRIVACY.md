# Privacy

PracticeLens is local-first.

Local data:

- Room stores practice sessions, questions, options, first/final answers, answer changes, timing, model ID, result metadata, questionable-feedback flags, Drive sync state, checksums, and timestamps.
- Temporary camera stills and confirmed crops are app-private cache files used only inside the foreground PracticeLens activity.
- The app never saves question images to the public gallery and never stores image blobs in Room.
- Retake, next question, cancellation, completion, or ViewModel/session disposal removes active media references; replaced capture files are deleted after crop confirmation or retake.

Network data:

- Production Firebase AI Logic receives only the learner-confirmed crop after crop confirmation. Continuous frames, background images, pre-disclosure frames, and unconfirmed crops are not uploaded.
- Interpretation receives the confirmed crop plus optional local OCR as unreliable supporting evidence. Evaluation receives the confirmed crop, learner-confirmed question/options, the locked selected app-owned option ID, and optional OCR diagnostic text.
- Demo builds remain offline and do not call Firebase or Gemini.
- Google's free-tier terms may permit submitted inputs to be used to improve Google products; verify current terms before production use.
- Firebase App Distribution stores tester and release metadata for private distribution.

Optional Google Drive backup:

- Disabled by default.
- Uses `https://www.googleapis.com/auth/drive.file` only after the learner taps `Connect Google Drive`.
- Drive backup is cloud storage, not phone-only storage. Google may process uploaded data under its terms, and Drive is not end-to-end encrypted against Google.
- Disconnecting Drive does not automatically delete existing remote files.

PracticeLens does not log OCR/question text, image bytes/base64, complete model responses, or raw SDK exceptions. It does not use Firebase Analytics, Crashlytics, Firestore, Realtime Database, Firebase Storage, screenshots, screen capture, overlays, accessibility-node capture, clipboard monitoring, automated taps, background camera behavior, or a custom APK updater.
