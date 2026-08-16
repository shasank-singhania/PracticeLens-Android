# Firebase Setup

Required owner inputs:

- Firebase project ID
- Firebase Android app ID
- Firebase tester group
- injected `google-services.json`
- Firebase CLI or CI workload identity/service account

Use Firebase AI Logic with Gemini Developer API and App Check enforcement. The model is configured in one place through `PRACTICELENS_GEMINI_MODEL_ID`; current documentation lists `gemini-3.6-flash` as the latest GA Gemini 3.x Flash model.

Production App Check should use Play Integrity. Debug builds may use the debug provider only for development.

Firebase App Distribution is external CI/CD only. Do not add the Firebase App Distribution Android runtime SDK.

Example:

```bash
firebase appdistribution:distribute \
  PracticeLens-v0.1.0-production.apk \
  --app "$FIREBASE_ANDROID_APP_ID" \
  --groups "$FIREBASE_TESTER_GROUPS" \
  --release-notes-file PracticeLens-v0.1.0-release-notes.md
```

Firebase App Distribution releases expire from the dashboard after 150 days; keep reproducible source, tags, checksums, and signing identity.
