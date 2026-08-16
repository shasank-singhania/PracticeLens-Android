# Privacy

PracticeLens is local-first.

Local data:

- Room stores practice sessions, questions, options, first/final answers, answer changes, timing, model ID, result metadata, questionable-feedback flags, Drive sync state, checksums, and timestamps.
- Temporary camera frames are accepted only inside the foreground PracticeLens activity and are discarded unless the learner explicitly enables accepted-image backup.
- Optional locally retained cropped images are limited to accepted stable question crops.

Network data:

- Gemini receives only learner-confirmed question text, options, and the selected answer after commitment. Camera frames are not uploaded to Gemini.
- Google's free-tier terms may permit submitted inputs to be used to improve Google products; verify current terms before production use.
- Firebase App Distribution stores tester and release metadata for private distribution.

Optional Google Drive backup:

- Disabled by default.
- Uses `https://www.googleapis.com/auth/drive.file` only after the learner taps `Connect Google Drive`.
- Drive backup is cloud storage, not phone-only storage. Google may process uploaded data under its terms, and Drive is not end-to-end encrypted against Google.
- Disconnecting Drive does not automatically delete existing remote files.

PracticeLens does not use Firebase Analytics, Crashlytics, Firestore, Realtime Database, Firebase Storage, screenshots, screen capture, overlays, accessibility-node capture, clipboard monitoring, or a custom APK updater.
