# PracticeLens Basic Gemini Loop

PracticeLens is currently reduced to a smoke-test app: tap Start, capture one rear-camera image, send it to Firebase AI Logic Gemini once, display the raw Gemini text, wait five seconds, and repeat until Stop or backgrounding.

## Firebase requirement

The local Firebase project must be `practicelens-private`, and the debug package must be registered as `app.practicelens.android.debug`.

This smoke build intentionally does not initialize App Check. Firebase AI Logic baseline protection must temporarily be unenforced for this APK. Do not distribute this APK while App Check enforcement is disabled.

## Build

```powershell
$repo = "D:\moved_from_C_drive_cleanup\Codex\2026-08-16\files-pasted-by-the-user-you\work\PracticeLens-Android"
$jdk17 = "$repo\.toolchains\jdk-17.0.19+10"
Set-Location $repo
$env:JAVA_HOME = $jdk17
$env:Path = "$jdk17\bin;$env:Path"
$env:GRADLE_USER_HOME = "$repo\.gradle-user-home"
.\gradlew.bat "-Dorg.gradle.java.home=$jdk17" "-Pkotlin.compiler.execution.strategy=in-process" clean :app:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
```

## Install

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Device smoke test

1. Launch PracticeLens and tap Start.
2. Show one page containing two clear multiple-choice questions.
3. Confirm one automatic capture and one Gemini request.
4. Confirm the raw Gemini response is visible and answers both questions.
5. Confirm the next capture happens after the five-second display and one-second camera warm-up, then tap Stop.
