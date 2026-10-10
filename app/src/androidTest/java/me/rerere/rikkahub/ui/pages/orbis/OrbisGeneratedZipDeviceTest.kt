package me.rerere.rikkahub.ui.pages.orbis

import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.GeneratedZipArchive
import me.rerere.rikkahub.data.files.ZipAttachmentArchive
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.chat.orbisToolDocuments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbisGeneratedZipDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun completedToolArtifactIsClickableWithoutRenderingOrExpandingToolDetails() {
        val tool = UIMessagePart.Tool("zip", "orbis_zip_create", "{}", output = listOf(
            UIMessagePart.Document("file:///app/files/upload/produced.zip", "作品.zip", "application/zip")))
        val artifacts = orbisToolDocuments(listOf(tool), "message")
        var opened = false
        compose.setContent { MaterialTheme {
            OrbisCallFileAttachmentsContent(artifacts, title = "生成的文件") { opened = true }
        } }
        compose.onNodeWithText("生成的文件").assertExists()
        compose.onNodeWithText("文件 · 作品.zip").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test fun androidDocumentDestinationReceivesExactZipAndInputParserCanReadIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val upload = File(context.filesDir, "upload").apply { mkdirs() }
        val source = File(upload, "zip-test-$id.zip")
        val target = File(context.cacheDir, "zip-export-test-$id.zip")
        val archive = GeneratedZipArchive.create("礼物.zip", listOf(GeneratedZipArchive.TextFile("说明.md", "合成内容\n不访问真人数据")))
        try {
            assertTrue(source.createNewFile()); source.writeBytes(archive.bytes)
            assertTrue(target.createNewFile())
            val destination = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            // Exercise the same ContentResolver output path used after SAF returns a user URI.
            context.contentResolver.openOutputStream(destination, "wt")!!.use {
                exportCallDocument(source.toURI().toString(), upload, it)
            }
            assertArrayEquals(archive.bytes, target.readBytes())
            assertEquals("合成内容\n不访问真人数据", ZipAttachmentArchive.readText(target, "说明.md").text)
            val intent = ActivityResultContracts.CreateDocument("application/zip").createIntent(context, "礼物.zip")
            assertEquals("application/zip", intent.type)
            assertEquals("礼物.zip", intent.getStringExtra(android.content.Intent.EXTRA_TITLE))
        } finally {
            source.delete(); target.delete() // Only the two exact UUID-owned synthetic files.
        }
    }
}
