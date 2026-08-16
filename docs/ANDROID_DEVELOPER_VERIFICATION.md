# Android Developer Verification

As of August 16, 2026, Google documents developer verification for apps distributed outside Google Play. Certified-device enforcement is being phased in regionally beginning in 2026 and more broadly in 2027.

Paths:

- Google Play Console: for apps distributed on Google Play, or both on and off Play.
- Android Developer Console: for apps distributed only outside Google Play.

The process includes identity verification, package-name registration, and signing-key registration. New package names require the public signing certificate. Existing package names may require proving private-key ownership with a signed APK.

Google also documents a limited-distribution path for developers who do not distribute widely, with installs limited to explicitly authorized devices.

Do not assume sideloading rules will remain unchanged. Register `app.practicelens.android` and its production signing certificate before enforcement affects the target region.
