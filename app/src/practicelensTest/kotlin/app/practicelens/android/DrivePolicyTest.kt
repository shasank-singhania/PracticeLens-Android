package app.practicelens.android

import app.practicelens.android.drive.DRIVE_FILE_SCOPE
import app.practicelens.android.drive.DriveBackupSettings
import app.practicelens.android.drive.DriveSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePolicyTest {
    @Test fun `drive scope is narrow`() {
        assertEquals("https://www.googleapis.com/auth/drive.file", DRIVE_FILE_SCOPE)
    }

    @Test fun `backup defaults are opt in and wifi only`() {
        val settings = DriveBackupSettings()
        assertFalse(settings.historyBackup)
        assertFalse(settings.imageBackup)
        assertTrue(settings.wifiOnly)
        assertFalse(settings.automaticSync)
    }

    @Test fun `drive worker does not depend on camera packages`() {
        val text = DriveSyncWorker::class.java.name
        assertFalse(text.contains("camera", ignoreCase = true))
    }
}
