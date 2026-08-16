#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SCAN = [
    ROOT / "app/src/main/AndroidManifest.xml",
    ROOT / "app/src/practicelens/kotlin",
]
FORBIDDEN = [
    "AccessibilityService",
    "BIND_ACCESSIBILITY_SERVICE",
    "MediaProjectionManager",
    "createVirtualDisplay",
    "TYPE_APPLICATION_OVERLAY",
    "SYSTEM_ALERT_WINDOW",
    "ClipboardManager",
    "addPrimaryClipChangedListener",
    "NotificationListenerService",
    "REQUEST_INSTALL_PACKAGES",
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "foregroundServiceType=\"camera\"",
    "appdistribution-api",
    "firebase-appdistribution",
]

failures = []
for item in SCAN:
    files = [item] if item.is_file() else list(item.rglob("*"))
    for file in files:
        if not file.is_file() or file.suffix.lower() in {".md", ".png", ".jpg", ".jpeg", ".webp"}:
            continue
        text = file.read_text(encoding="utf-8", errors="ignore")
        for token in FORBIDDEN:
            if token in text:
                failures.append(f"{file.relative_to(ROOT)} contains {token}")

if failures:
    print("PracticeLens structural guard failed:")
    print("\n".join(failures))
    sys.exit(1)
print("PracticeLens structural guard passed.")
