# Firebase Setup

Required owner inputs:

- Firebase project ID
- Firebase Android app ID
- Firebase tester group
- injected `google-services.json`
- Firebase CLI or CI workload identity/service account
- App Check debug token for local `productionDebug` development only
- Play Integrity configuration for `productionRelease`

Use Firebase AI Logic with Gemini Developer API and App Check enforcement. The model is configured in one place through `PRACTICELENS_GEMINI_MODEL_ID`; do not replace it silently with preview, experimental, or `-latest` model names.

Production App Check should use Play Integrity. Debug builds may use the debug provider only for development.

Owner actions:

1. Create or select the Firebase project and Android app for `app.practicelens.android`.
2. Download `google-services.json` through the Firebase console and inject it locally/CI without committing it.
3. Enable Firebase AI Logic with the Gemini Developer API provider.
4. Enforce App Check for Firebase AI Logic.
5. Register the local App Check debug token only for `productionDebug` development.
6. Configure Play Integrity for release builds and never enable the debug provider in release.
7. Set `PRACTICELENS_GEMINI_MODEL_ID` to the approved production model.

Missing Firebase config, App Check rejection, quota, and authentication failures are external configuration/runtime blockers. They should surface as typed app errors, not as Kotlin compilation success or raw SDK exceptions.

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
