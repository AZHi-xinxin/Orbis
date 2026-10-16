package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EmergencyCrashFlagReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun prefs() = File(temporary.root, "shared_prefs")
    private fun write(xml: String, backup: Boolean = false): File {
        prefs().mkdirs()
        return File(prefs(), "crash_handler.xml" + if (backup) ".bak" else "").apply { writeText(xml) }
    }

    @Test fun freshInstallDoesNotCreatePreferencesOrEnableProtection() {
        assertFalse(readEmergencyLauncherCrashFlag(prefs()))
        assertFalse(prefs().exists())
    }

    @Test fun eachReadObservesFreshDiskStateNotAProcessCache() {
        val file = write("<map><boolean name=\"crashed\" value=\"false\" /></map>")
        assertFalse(readEmergencyLauncherCrashFlag(prefs()))
        file.writeText("<map><boolean name=\"crashed\" value=\"true\" /></map>")
        assertTrue(readEmergencyLauncherCrashFlag(prefs()))
        file.writeText("<map><string name=\"stacktrace\">synthetic preserved report</string></map>")
        assertFalse(readEmergencyLauncherCrashFlag(prefs()))
    }

    @Test fun validAndroidXmlReportIsNotChangedOrReturned() {
        val xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>" +
            "<string name=\"stacktrace\">synthetic &lt;private&gt; &amp; report</string>" +
            "<boolean value=\"true\" name=\"crashed\" /></map>"
        val file = write(xml)
        assertTrue(readEmergencyLauncherCrashFlag(prefs()))
        assertEquals(xml, file.readText())
    }

    @Test fun backupPrecedenceDoesNotRestoreRenameOrDeleteEitherFile() {
        val primary = write("<map><boolean name=\"crashed\" value=\"false\" /></map>")
        val backup = write("<map><boolean name=\"crashed\" value=\"true\" /></map>", backup = true)
        val before = primary.readBytes() to backup.readBytes()
        assertTrue(readEmergencyLauncherCrashFlag(prefs()))
        assertArrayEquals(before.first, primary.readBytes())
        assertArrayEquals(before.second, backup.readBytes())
    }

    @Test fun malformedBackupCannotBeIgnoredInFavourOfPrimary() {
        write("<map/>")
        write("not xml", backup = true)
        assertThrows(Exception::class.java) { readEmergencyLauncherCrashFlag(prefs()) }
    }

    @Test fun invalidOrAmbiguousCrashFlagsFailClosed() {
        val rejected = listOf(
            "<map><boolean name=\"crashed\" value=\"maybe\" /></map>",
            "<map><string name=\"crashed\">true</string></map>",
            "<map><boolean name=\"crashed\" value=\"false\" /><boolean name=\"crashed\" value=\"true\" /></map>",
            "<not-map/>", "<map><string name=\"stacktrace\"><nested/></string></map>",
            "<map>", "", "<map>\u0000</map>",
        )
        for (xml in rejected) {
            write(xml)
            assertThrows(xml, Exception::class.java) { readEmergencyLauncherCrashFlag(prefs()) }
        }
    }

    @Test fun declarationsCannotLoadExternalFilesOrExpandEntities() {
        write("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///synthetic-private'>]><map><string name=\"stacktrace\">&x;</string></map>")
        assertThrows(IllegalStateException::class.java) { readEmergencyLauncherCrashFlag(prefs()) }
    }

    @Test fun oversizedOrNonFileStateFailsClosed() {
        val file = write(" ".repeat(1_048_577))
        assertThrows(IllegalStateException::class.java) { readEmergencyLauncherCrashFlag(prefs()) }
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertThrows(IllegalStateException::class.java) { readEmergencyLauncherCrashFlag(prefs()) }
    }

    @Test fun unrelatedPreferencesAreNeverParsed() {
        write("<map/>")
        val unrelated = File(prefs(), "settings.xml").apply { writeText("not XML synthetic secret") }
        assertFalse(readEmergencyLauncherCrashFlag(prefs()))
        assertEquals("not XML synthetic secret", unrelated.readText())
    }
}
