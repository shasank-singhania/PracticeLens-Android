# Play Internal Testing

Owner steps:

1. Create a Google Play developer account and pay the current one-time registration fee.
2. Enroll in Play App Signing.
3. Preserve `app.practicelens.android`.
4. Where Google permits it, enroll with the existing app-signing key so Firebase sideloaded installs can update without uninstalling.
5. Create an internal testing track.
6. Add Gmail-address testers up to the current Play limit.
7. Upload `PracticeLens-v0.1.0.aab`.

Signing migration warning: if Play uses a different app-signing certificate from the Firebase-distributed APK, Android may require uninstalling the existing app, which can delete local data.
