#!/usr/bin/env python3
"""Local-only PracticeLens real-image regression harness.

Place personal capture fixtures under local-ocr-fixtures/. The directory is
gitignored. Optional OCR sidecars may use the same base name with .ocr.txt.
By default the report records only counts and categories, not full OCR text.
"""

from __future__ import annotations

import argparse
import json
import struct
import time
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}


def png_dimensions(data: bytes) -> tuple[int, int] | None:
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
        return struct.unpack(">II", data[16:24])
    return None


def jpeg_dimensions(data: bytes) -> tuple[int, int] | None:
    if not data.startswith(b"\xff\xd8"):
        return None
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        i += 2
        if marker in {0xD8, 0xD9}:
            continue
        if i + 2 > len(data):
            return None
        length = struct.unpack(">H", data[i : i + 2])[0]
        if length < 2 or i + length > len(data):
            return None
        if marker in range(0xC0, 0xC4) or marker in range(0xC5, 0xC8) or marker in range(0xC9, 0xCC) or marker in range(0xCD, 0xD0):
            if length >= 7:
                height, width = struct.unpack(">HH", data[i + 3 : i + 7])
                return width, height
        i += length
    return None


def dimensions(path: Path) -> tuple[int, int] | None:
    data = path.read_bytes()
    return png_dimensions(data) or jpeg_dimensions(data)


def sidecar_for(path: Path) -> Path:
    return path.with_suffix(path.suffix + ".ocr.txt")


def parse_sidecar(text: str) -> tuple[bool, int, str]:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    labels = 0
    for line in lines:
        prefix = line.split(maxsplit=1)[0].strip("()[]")
        if prefix.rstrip(".):-").upper() in {"A", "B", "C", "D", "1", "2", "3", "4", "I", "II", "III", "IV"}:
            labels += 1
    if not text.strip():
        return False, 0, "empty_ocr"
    if labels < 2:
        return False, labels, "fewer_than_two_options"
    return True, labels, "parsed_from_sidecar"


def inspect(path: Path, include_text: bool) -> dict:
    started = time.perf_counter()
    dims = dimensions(path)
    sidecar = sidecar_for(path)
    ocr_text = sidecar.read_text(encoding="utf-8") if sidecar.exists() else ""
    valid, option_count, category = parse_sidecar(ocr_text)
    elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
    row = {
        "fixture_id": path.stem,
        "file_name": path.name,
        "dimensions": {"width": dims[0], "height": dims[1]} if dims else None,
        "rotation_degrees": "not_read_by_harness",
        "ocr_character_count": len(ocr_text),
        "parser_valid": valid,
        "detected_option_count": option_count,
        "processing_latency_ms": elapsed_ms,
        "failure_rejection_category": category,
        "manual_review_reachable": True,
    }
    if include_text:
        row["local_debug_ocr_text"] = ocr_text
    return row


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", default="local-ocr-fixtures")
    parser.add_argument("--out", default="real-image-regression-reports/latest.json")
    parser.add_argument("--include-ocr-text", action="store_true")
    args = parser.parse_args()

    fixture_dir = Path(args.fixtures)
    images = sorted(path for path in fixture_dir.glob("*") if path.suffix.lower() in IMAGE_EXTENSIONS)
    report = {
        "fixture_dir": str(fixture_dir),
        "image_upload_performed": False,
        "complete_question_text_logged": bool(args.include_ocr_text),
        "results": [inspect(path, args.include_ocr_text) for path in images],
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Wrote {out} with {len(images)} fixture result(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
