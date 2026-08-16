# Google Drive Setup

Drive backup is disabled by default and must not block camera, OCR, local history, or Gemini evaluation.

Authorization:

- Credential Manager handles Google-account sign-in.
- Google Identity Services `AuthorizationClient` requests Drive authorization only after `Connect Google Drive`.
- Scope: `https://www.googleapis.com/auth/drive.file`
- Never request broad `drive`.
- Never embed an OAuth client secret in the APK.

Folder structure:

- `My Drive/PracticeLens/Backups/`
- `My Drive/PracticeLens/QuestionImages/`
- `My Drive/PracticeLens/Exports/`

Defaults:

- history backup off
- image backup off
- Wi-Fi-only on
- manual sync on

Accepted question images may contain names, course material, account details, or other visible information. Selected images are stored in the learner's Google Drive and count against Drive quota.
