# Release Signing

Future updates require the same application ID and signing certificate.

Supported secrets:

- `PRACTICELENS_KEYSTORE_B64`
- `PRACTICELENS_KEY_ALIAS`
- `PRACTICELENS_STORE_PASSWORD`
- `PRACTICELENS_KEY_PASSWORD`

Rules:

- Never commit the keystore or passwords.
- Never print secret environment variables.
- Decode CI keystore only into `$RUNNER_TEMP`.
- Delete temporary keystore after the workflow.
- Verify APKs with `apksigner verify --verbose --print-certs`.
- Record SHA-256 and SHA-1 certificate fingerprints.
- Increase `versionCode` for every release.

If no production signing identity exists, choose a secure storage/recovery method before generating one.
