# Share Via Gmail

Gmail blocks `.apk` attachments, including APKs hidden inside many compressed archives. Do not rename APKs, use password-protected archives, or use Gmail API automation to bypass scanning.

Preferred sharing: Firebase App Distribution invitation.

Alternative: upload the signed APK to Google Drive, restrict it to the intended recipient, and email the Drive link.

Subject:

`PracticeLens Android v{version} - installation/update link`

Body:

> PracticeLens v{version} is available for installation or update.
>
> Install through the private Firebase App Distribution link below:
> {distribution_link}
>
> Package: app.practicelens.android
> APK SHA-256: {checksum}
> Release notes: {release_notes_link}
> Source: {repository_url}
>
> Android may ask you to confirm installation. This build must be signed with the same certificate as the previously installed version.

Do not automatically send this email.
