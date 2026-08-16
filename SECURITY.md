# Security

Report security issues privately to the repository owner. Do not publish exploitable details until a fix is available.

Production signing keys are critical update infrastructure. Never commit keystores, passwords, service-account JSON, Firebase config secrets, OAuth codes, access tokens, tester private links, or raw session cookies.

Supported CI secrets:

- `PRACTICELENS_KEYSTORE_B64`
- `PRACTICELENS_KEY_ALIAS`
- `PRACTICELENS_STORE_PASSWORD`
- `PRACTICELENS_KEY_PASSWORD`
- `FIREBASE_ANDROID_APP_ID`
- `FIREBASE_TESTER_GROUPS`

The app must continue to pass `tools/structural_guard.py` before release.
