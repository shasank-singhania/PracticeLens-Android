# Changelog

## Unreleased

- Fixed foreground scanning that could run indefinitely when adjacent ML Kit results differed only by punctuation or whitespace.
- Extended the stable-frame window for real device OCR latency and tolerated small hand-held camera translations.
- Added scanner guidance, diagnostic rejection logging, and a `Use current OCR` fallback.

## 0.1.0

- Rebuilt as PracticeLens with preparation-only product boundary.
- Added foreground rear-camera scanner scaffold, local OCR review, answer revision reducer, fake demo evaluator, Drive backup contracts, Room schema, release signing documentation, and CI guards.
