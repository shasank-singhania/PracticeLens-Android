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
- `QuestionImageInterpreter`
- `EvaluationGateway`
- `FakePracticeEvaluator`

Camera capture is lifecycle-bound CameraX inside `MainActivity`. The app uses `CameraSelector.DEFAULT_BACK_CAMERA`, visible preview, `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, and high-quality `ImageCapture` in a shared `UseCaseGroup` with the `PreviewView` viewport when available. Live analysis produces advisory quality messages only; capture acceptance is image-first and does not depend on OCR/parser validity.

The scanner state flow is `SCANNING -> CAPTURING -> CROP_REVIEW -> QUESTION_INTERPRETATION -> QUESTION_REVIEW -> ANSWERING -> GRACE_PERIOD -> EVALUATING -> RESULT` or `FAILED`.

Captured stills and confirmed crops are app-private cache files represented by `CapturedQuestionMedia`: private URI, MIME type, dimensions, applied rotation, SHA-256, timestamp, and quality warnings. Room stores no image bytes. Retake/new question/session disposal clears ViewModel references, and the Android UI deletes replaced capture files after crop confirmation or retake.

Question interpretation and evaluation are separate contracts. `QuestionImageInterpreter` extracts one editable MCQ from the confirmed crop and must not solve it. `GeminiEvaluator` evaluates only a locked selected app-owned option ID after the 700 ms grace period. JSON model responses are locally validated before reaching UI state.

The reducer in `core/Domain.kt` serializes answer changes with `selectionVersion`. Evaluation can move to `IN_FLIGHT` only when question ID, selection version, selected option, and evaluation state still match.
