package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class EmergencyExportDestinationPolicyTest {
    private val external = "com.android.externalstorage.documents"
    private val downloads = "com.android.providers.downloads.documents"
    private fun reason(id: String?, authority: String? = external) =
        emergencyExportDestinationRejection(authority, id, "org.orbis.agent")

    @Test fun `allows local Downloads and custom folders on primary storage`() {
        listOf("primary:Download/Orbis-emergency-20261003.zip", "primary:我的备份/聊天与助手.zip")
            .forEach { assertNull(reason(it)) }
    }

    @Test fun `allows removable volume storage`() {
        assertNull(reason("ABCD-1234:Backup/Orbis.zip"))
    }

    @Test fun `rejects private data obb and media of every app including dev`() {
        listOf("primary:Android/data/org.orbis.agent/files/backup.zip",
            "primary:Android/data/org.orbis.agent.dev/files/backup.zip",
            "primary:Android/data/unrelated.app/backup.zip", "ABCD-1234:Android/obb/any.app/backup.zip",
            "primary:Android/media/org.orbis.agent/backup.zip",
            "primary:ANDROID/DATA/org.orbis.agent/backup.zip")
            .forEach { assertEquals(it, "app_private_directory", reason(it)) }
    }

    @Test fun `rejects unrecognized cloud and app providers`() {
        listOf("drive.provider", "cloud.local", "com.android.externalstorage.documents.evil", "other.app.files", null)
            .forEach { assertEquals("not_local_provider", reason("primary:Download/file.zip", it)) }
        assertEquals("app_private_directory", reason("file", "org.orbis.agent.fileprovider"))
        assertEquals("app_private_directory", reason("file", "org.orbis.agent.documents"))
    }

    @Test fun `rejects path traversal and ambiguous separators`() {
        listOf("primary:Download/../Android/data/app/backup.zip", "primary:../backup.zip",
            "primary:Download/./backup.zip", "primary:Download//backup.zip", "primary:/Download/backup.zip",
            "primary:Download\\backup.zip", "primary:Download/backup.zip/", "primary:Download/ .. /backup.zip")
            .forEach { assertEquals(it, "invalid_document_id", reason(it)) }
    }

    @Test fun `rejects encoded separator and traversal ambiguities`() {
        listOf("primary:Download/%2e%2e/backup.zip", "primary:Download%2fbackup.zip",
            "primary:Download/%252e%252e/backup.zip", "primary:Download/%5Cbackup.zip")
            .forEach { assertEquals(it, "invalid_document_id", reason(it)) }
    }

    @Test fun `rejects malformed document identifiers`() {
        listOf(null, "", "primary:", ":Download/file.zip", "../volume:Download/file.zip", "Download/file.zip",
            "primary:Download/a:b.zip", "primary:Download/file\u0000.zip", "primary:Download/file\n.zip")
            .forEach { assertEquals(it, "invalid_document_id", reason(it)) }
    }

    @Test fun `allows official Downloads numeric and media ids`() {
        listOf("0", "12045", "msf:12345", "msd:54321")
            .forEach { assertNull(reason(it, downloads)) }
    }

    @Test fun `allows official Downloads raw ids on shared storage`() {
        listOf("raw:/storage/emulated/0/Download/backup.zip", "raw:/sdcard/Download/backup.zip",
            "raw:/storage/ABCD-1234/Backups/backup.zip", "raw:/mnt/media_rw/ABCD-1234/backup.zip")
            .forEach { assertNull(reason(it, downloads)) }
    }

    @Test fun `rejects raw ids into app data or obb even through official Downloads`() {
        listOf("raw:/storage/emulated/0/Android/data/org.orbis.agent/files/backup.zip",
            "raw:/sdcard/Android/obb/other.app/backup.zip", "raw:/sdcard/Android/media/any.app/backup.zip",
            "raw:/mnt/media_rw/ABCD-1234/ANDROID/DATA/any.app/backup.zip",
            "raw:/data/user/0/org.orbis.agent/files/backup.zip", "raw:/data/data/org.orbis.agent/backup.zip")
            .forEach { assertEquals(it, "app_private_directory", reason(it, downloads)) }
    }

    @Test fun `rejects raw path traversal and unexpected Downloads ids`() {
        listOf("raw:/sdcard/../data/file.zip", "raw:/storage//emulated/0/file.zip", "raw:relative/file.zip",
            "raw:/sdcard/file.zip\u0000", "msf:-12", "msf:12/file", "download:12", "raw:")
            .forEach { assertEquals(it, "invalid_document_id", reason(it, downloads)) }
    }

    @Test fun `does not apply authority prefix confusion to official providers`() {
        assertEquals("not_local_provider", reason("12", "com.android.providers.downloads.documents.evil"))
        assertEquals("not_local_provider", reason("12", "com.android.providers.downloads.documents:443"))
    }
}
