package app.practicelens.android.drive

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

enum class DriveSyncState { DISCONNECTED, READY, USER_ACTION_REQUIRED, SYNCING, FAILED }

data class DriveBackupSettings(
    val historyBackup: Boolean = false,
    val imageBackup: Boolean = false,
    val wifiOnly: Boolean = true,
    val deleteLocalImageAfterUpload: Boolean = false,
    val automaticSync: Boolean = false,
)

interface QuestionImageStore {
    suspend fun persistAcceptedCrop(questionId: String, bytes: ByteArray): String
    suspend fun discardRejectedFrame(frameId: String)
}

interface DriveAuthorizationManager {
    val requestedScope: String get() = DRIVE_FILE_SCOPE
    suspend fun connectAfterUserTap(): DriveSyncState
    suspend fun disconnect()
    suspend fun revoke()
}

interface BackupSerializer {
    fun manifestJson(appVersion: String): String
    fun sessionJson(sessionId: String): String
}

interface DriveBackupRepository {
    suspend fun syncNow(settings: DriveBackupSettings): DriveSyncState
    suspend fun restorePreview(): List<String>
    suspend fun deleteCloudBackup(confirm: Boolean): Boolean
}

interface BackupScheduler {
    fun schedule(settings: DriveBackupSettings)
}
