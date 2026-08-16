package app.practicelens.android

import app.practicelens.android.drive.DRIVE_FILE_SCOPE
import app.practicelens.android.drive.DriveBackupSettings
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

    @Test fun `demo policy keeps drive disabled by default`() {
        val settings = DriveBackupSettings()
        assertFalse(settings.automaticSync)
    }
}
