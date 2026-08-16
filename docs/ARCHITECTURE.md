# Architecture

PracticeLens is local-first. The active source set is `app/src/practicelens/kotlin`.

Core interfaces:

- `PracticeRepository`
- `QuestionImageStore`
- `DriveAuthorizationManager`
- `DriveBackupRepository`
- `BackupSerializer`
- `BackupScheduler`
- `GeminiEvaluator`
- `FakePracticeEvaluator`

Camera capture is lifecycle-bound CameraX inside `MainActivity`. The app uses `CameraSelector.DEFAULT_BACK_CAMERA`, visible preview, `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, and always closes `ImageProxy`.

The reducer in `core/Domain.kt` serializes answer changes with `selectionVersion`. Evaluation can move to `IN_FLIGHT` only when question ID, selection version, selected option, and evaluation state still match.
