# Release Process

1. Update `version.properties`.
2. Add `docs/release-notes/PracticeLens-v{version}-release-notes.md`.
3. Run demo CI locally or in GitHub Actions.
4. Build signed production APK and AAB with protected secrets.
5. Verify signature and certificate fingerprints.
6. Generate APK SHA-256 checksum.
7. Upload production APK privately through Firebase App Distribution.
8. Attach only the demo APK, checksum, and public notes to public GitHub prereleases unless the owner explicitly approves public production binaries.

Android updates are not silent or forced. The user may need to confirm installation.
