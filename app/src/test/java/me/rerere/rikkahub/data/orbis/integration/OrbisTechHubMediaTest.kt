package me.rerere.rikkahub.data.orbis.integration

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisTechHubMediaTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "合成附件，不是真实聊天\nsecond line".toByteArray()
    private fun media() = HubUploadAttachment(UUID.randomUUID().toString(), "test.txt", "text/plain", bytes.size.toLong(),
        hubHex(MessageDigest.getInstance("SHA-256").digest(bytes)))

    @Test fun namesRejectPathsControlsBidiAndTraversal() {
        listOf("../x", "a/b", "a\\b", "C:x", ".", "..", " x", "x\n", "x\u0000", "x\u007f", "x\u202eexe", "x\u2066", "a".repeat(201)).forEach {
            assertThrows(IllegalArgumentException::class.java) { requireHubFilename(it) }
        }
        assertEquals("合成（1）.txt", requireHubFilename("合成（1）.txt"))
    }
    @Test fun normalizationMatchesServerAndNeverBuildsAPathFromName() {
        assertEquals("test_name_.txt", hubUploadFilename("test@name?.txt"))
        assertEquals("image/png", hubMediaType(" IMAGE/PNG; charset=binary"))
        listOf("", "text", "text/plain\r\nAuthorization:x", "a/b/c").forEach {
            assertThrows(IllegalArgumentException::class.java) { hubMediaType(it) }
        }
    }
    @Test fun imageAndFileLimitsAndCanonicalMetadataAreStrict() {
        validateHubUpload(media().copy(size = HUB_FILE_LIMIT))
        validateHubUpload(media().copy(mediaType = "image/png", size = HUB_IMAGE_LIMIT))
        listOf(media().copy(size = 0), media().copy(size = HUB_FILE_LIMIT + 1),
            media().copy(mediaType = "image/png", size = HUB_IMAGE_LIMIT + 1),
            media().copy(payloadId = "../outside"), media().copy(sha256 = "X".repeat(64)),
            media().copy(mediaType = "Text/Plain"), media().copy(filename = "a?.txt")).forEach {
            assertThrows(IllegalArgumentException::class.java) { validateHubUpload(it) }
        }
    }
    @Test fun streamingCopyHasExactHashAndBoundedOutput() {
        val output = ByteArrayOutputStream()
        val result = copyHubMedia(bytes.inputStream(), output, bytes.size.toLong())
        assertEquals(bytes.size.toLong(), result.first); assertEquals(media().sha256, result.second)
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(OrbisTechHubException::class.java) { copyHubMedia(bytes.inputStream(), ByteArrayOutputStream(), 2) }
        assertThrows(OrbisTechHubException::class.java) { copyHubMedia(byteArrayOf().inputStream(), ByteArrayOutputStream(), 2) }
        assertThrows(IOException::class.java) { copyHubMedia(bytes.inputStream(), output, 100) { throw IOException("cancel") } }
    }
    @Test fun textPreviewIsBoundedStrictUtf8AndNeverExecutesHtml() {
        val file = temp.newFile("preview")
        file.writeText("<script>never execute</script>")
        assertEquals("<script>never execute</script>", hubPreviewText(file, "text/html"))
        assertNull(hubPreviewText(file, "image/png"))
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28)); assertNull(hubPreviewText(file, "text/plain"))
        file.writeBytes(byteArrayOf(65, 0, 66)); assertNull(hubPreviewText(file, "text/plain"))
        file.writeBytes(ByteArray(HUB_TEXT_PREVIEW_LIMIT + 1) { 65 }); assertNull(hubPreviewText(file, "text/plain"))
    }
    @Test fun stageThenVerifyAndDiscardOnlyOwnedUuidPayload() = runBlocking {
        val root = temp.newFolder("private"); val files = HubMediaFiles(root)
        val unrelated = File(root, "keep.txt").apply { writeText("keep") }
        val item = files.stage("test@file.txt", "text/plain") { bytes.inputStream() }
        assertEquals("test_file.txt", item.filename)
        val file = files.verified(item)
        assertEquals("${item.payloadId}.bin", file.name); assertArrayEquals(bytes, file.readBytes())
        files.discard(item)
        assertFalse(file.exists()); assertEquals("keep", unrelated.readText())
    }
    @Test fun interruptedStagingDeletesOnlyItsPartialFile() = runBlocking {
        val root = temp.newFolder("failed"); val files = HubMediaFiles(root)
        try { files.stage("test.txt", "text/plain") { throw IOException("fixture") }; fail() } catch (_: IOException) { }
        assertTrue(File(root, "orbis-techhub-uploads").listFiles()!!.isEmpty())
    }
    @Test fun changedStoredBytesCannotBeRetried() = runBlocking {
        val files = HubMediaFiles(temp.newFolder("changed"))
        val item = files.stage("test.txt", "text/plain") { bytes.inputStream() }
        val file = files.verified(item)
        file.writeBytes(ByteArray(bytes.size) { 65 })
        try { files.verified(item); fail() } catch (error: OrbisTechHubException) { assertEquals("media_changed", error.reason) }
        files.discard(item)
    }
    @Test fun uploadBodyIsRawSingleShotAndUsesActualContentLength() = runBlocking {
        val files = HubMediaFiles(temp.newFolder("body"))
        val item = files.stage("test.txt", "text/plain") { bytes.inputStream() }
        val file = files.verified(item); val body = hubUploadBody(file, item)
        assertTrue(body.isOneShot()); assertEquals(bytes.size.toLong(), body.contentLength())
        assertEquals("text/plain", body.contentType().toString())
        val sink = Buffer(); body.writeTo(sink); assertArrayEquals(bytes, sink.readByteArray())
    }
    @Test fun legacyTextIntentAndBlankMediaCaptionRemainDifferent() {
        assertThrows(IllegalArgumentException::class.java) { HubSendIntent.create("general", "", 4) }
        val item = media(); assertEquals(item, HubSendIntent.createMedia("general", "", 4, item).attachment)
    }
    @Test fun uploadReceiptMustMatchTheExactStagedMetadata() {
        val item = media()
        val receipt = """{"seq":8,"attachment":{"id":"${"a".repeat(32)}","filename":"test.txt","size":${item.size},"content_type":"text/plain","kind":"file"}}"""
        assertEquals(8L, parseHubUploaded(receipt.toByteArray(), item).seq)
        listOf(receipt.replace("test.txt", "other.txt"), receipt.replace("text/plain", "text/html"),
            receipt.replace("\"seq\":8", "\"seq\":0"), receipt.replace("\"kind\":\"file\"", "\"kind\":\"image\""),
            receipt.replace("\"size\":${item.size}", "\"size\":1")).forEach {
            assertThrows(OrbisTechHubException::class.java) { parseHubUploaded(it.toByteArray(), item) }
        }
    }
    @Test fun draftDisposalAfterClaimCannotDeleteOutboxPayloadEvenWithoutRecomposition() {
        val deleted = mutableListOf<HubUploadAttachment>(); val owner = HubMediaDraftOwner { deleted += it }
        val item = media(); owner.replace(item); owner.claim(item); owner.clear()
        assertTrue(deleted.isEmpty())
    }
    @Test fun disposingBeforeClaimPreventsOwnershipTransferOfDeletedBytes() {
        val deleted = mutableListOf<HubUploadAttachment>(); val owner = HubMediaDraftOwner { deleted += it }
        val item = media(); owner.replace(item); owner.clear()
        assertThrows(IllegalStateException::class.java) { owner.claim(item) }
        assertEquals(listOf(item), deleted)
    }
    @Test fun slowOldSendCannotClaimOrDeleteNewlySelectedPayload() {
        val deleted = mutableListOf<HubUploadAttachment>(); val owner = HubMediaDraftOwner { deleted += it }
        val old = media(); val next = media(); owner.replace(old); owner.replace(next)
        assertThrows(IllegalStateException::class.java) { owner.claim(old) }
        owner.claim(next); owner.clear()
        assertEquals(listOf(old), deleted)
    }
    @Test fun previewFileCleanupDoesNotWaitForUiRecomposition() {
        val unrelated = temp.newFile("keep-preview-neighbor")
        val cache = temp.newFile("owned-preview")
        val owner = HubPreviewFileOwner(); owner.publish(cache); owner.clear(); owner.clear()
        assertFalse(cache.exists()); assertTrue(unrelated.exists())
    }
    @Test fun replacingPreviewDeletesOnlyThePreviousOwnedFile() {
        val first = temp.newFile("first-preview"); val second = temp.newFile("second-preview")
        val owner = HubPreviewFileOwner(); owner.publish(first); owner.publish(second)
        assertFalse(first.exists()); assertTrue(second.exists()); owner.clear(); assertFalse(second.exists())
    }
    @Test fun exactSaveRejectsShortCacheInsteadOfReportingSuccess() {
        val failure = assertThrows(OrbisTechHubException::class.java) {
            copyHubMediaExact("short".byteInputStream(), ByteArrayOutputStream(), 9)
        }
        assertEquals("media_size_mismatch", failure.reason)
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyHubMediaExact(bytes.inputStream(), output, bytes.size.toLong()).first)
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun automaticPreviewOnlyAcceptsBoundedListedRasterMetadata() {
        val image = HubAttachment("a".repeat(32), "image.jpg", 159710, "image", "image/jpeg")
        assertTrue(hubMayAutoPreview(image))
        assertTrue(hubMayAutoPreview(image.copy(size = HUB_AUTO_PREVIEW_LIMIT)))
        listOf(image.copy(size = 0), image.copy(size = HUB_AUTO_PREVIEW_LIMIT + 1),
            image.copy(kind = "file"), image.copy(mediaType = "image/svg+xml"),
            image.copy(mediaType = "application/octet-stream"), image.copy(id = "https://external/image.jpg"))
            .forEach { assertFalse(hubMayAutoPreview(it)) }
    }
    @Test fun automaticPreviewAttemptsRemainOnceAcrossCardRecreationAndHaveBoundedMemory() {
        val image = HubAttachment("a".repeat(32), "image.jpg", 200, "image", "image/jpeg")
        val attempts = mutableSetOf<String>()
        assertTrue(claimHubAutoPreview(attempts, image))
        assertFalse(claimHubAutoPreview(attempts, image))
        assertFalse(claimHubAutoPreview(attempts, image.copy(size = 100)))
        assertEquals(1, attempts.size)
        val full = (0 until 1000).map { it.toString(16).padStart(32, '0') }.toMutableSet()
        assertFalse(claimHubAutoPreview(full, image))
        assertEquals(1000, full.size)
    }
    @Test fun explicitSaveOwnershipSurvivesClearingBackgroundedPreviewButNotDisposal() {
        val file = temp.newFile("save-owned.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val preview = HubPreviewFileOwner(); val pendingSave = HubPreviewFileOwner()
        preview.publish(file)
        pendingSave.publish(requireNotNull(preview.take()))
        preview.clear()
        assertTrue(file.exists()); assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        pendingSave.clear(); assertFalse(file.exists())
    }
    @Test fun successfulOffscreenPreviewReturnsByLocalOwnershipWithoutAnotherAutomaticAttempt() {
        val item = HubAttachment("a".repeat(32), "synthetic.jpg", 3, "image", "image/jpeg")
        val file = temp.newFile("scroll-cache").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val page = HubPreviewPageCache()
        assertTrue(page.claim(item)); page.succeeded(item)
        page.put(HubCachedPreview(item, file, "image/jpeg"))
        assertFalse(page.claim(item)); assertTrue(page.contains(item))
        val taken = requireNotNull(page.take(item))
        assertFalse(page.contains(item)); page.close()
        assertTrue(file.exists()) // caller alone owns the taken bytes now
        assertArrayEquals(byteArrayOf(1, 2, 3), taken.file.readBytes())
        assertTrue(taken.file.delete())
    }
    @Test fun backgroundDropsBytesButOnlyResetsSuccessfulAutomaticAttempts() {
        val success = HubAttachment("a".repeat(32), "synthetic.jpg", 1, "image", "image/jpeg")
        val failure = success.copy(id = "b".repeat(32))
        val file = temp.newFile("background-cache").apply { writeBytes(byteArrayOf(1)) }
        val page = HubPreviewPageCache()
        assertTrue(page.claim(success)); assertTrue(page.claim(failure)); page.succeeded(success)
        page.put(HubCachedPreview(success, file, "image/jpeg")); page.setActive(false)
        assertFalse(file.exists()); assertFalse(page.claim(success))
        page.setActive(true)
        assertTrue(page.claim(success)); assertFalse(page.claim(failure)); page.close()
        assertFalse(page.claim(success))
    }
    @Test fun pageCacheEvictsOnlyItsOldestVerifiedFileAndClosedCacheCannotBeRevived() {
        val page = HubPreviewPageCache()
        val files = (0 until 9).map { index ->
            val item = HubAttachment(index.toString(16).padStart(32, '0'), "synthetic.jpg", 1, "image", "image/jpeg")
            temp.newFile("bounded-cache-$index").apply {
                writeBytes(byteArrayOf(index.toByte()))
                page.put(HubCachedPreview(item, this, "image/jpeg"))
            }
        }
        assertFalse(files.first().exists()); assertTrue(files.drop(1).all { it.exists() })
        page.close(); assertTrue(files.none { it.exists() })
        val late = temp.newFile("closed-cache").apply { writeBytes(byteArrayOf(1)) }
        page.put(HubCachedPreview(HubAttachment("a".repeat(32), "x.jpg", 1, "image", "image/jpeg"), late, "image/jpeg"))
        assertFalse(late.exists())
    }
    @Test fun previewCacheResolvesTrustedParentAliasBeforeCheckingOwnedChildren() {
        val base = temp.newFolder("preview-cache")
        val child = File(base, "trusted-child").also { assertTrue(it.mkdir()) }
        val alias = File(child, "..")
        assertNotEquals(alias.absoluteFile, alias.canonicalFile)
        val file = createHubPreviewFile(alias)
        assertEquals(File(base.canonicalFile, "orbis-techhub-preview"), file.parentFile)
        assertEquals(file.absoluteFile, file.canonicalFile)
        assertTrue(file.isFile); assertEquals(0L, file.length())
        assertTrue(file.name.startsWith("hub-") && file.name.endsWith(".bin"))
    }
    @Test fun previewCacheDoesNotOverwriteOccupiedDirectoryOrCreateMissingTrustedRoot() {
        val base = temp.newFolder("occupied-preview-cache")
        val occupied = File(base, "orbis-techhub-preview").apply { writeText("keep") }
        assertThrows(OrbisTechHubException::class.java) { createHubPreviewFile(base) }
        assertEquals("keep", occupied.readText())
        val missing = File(base, "missing-parent")
        assertThrows(OrbisTechHubException::class.java) { createHubPreviewFile(missing) }
        assertFalse(missing.exists())
    }
    @Test fun previewImageDecodePolicyIsBoundedAndRejectsExecutableOrUnknownFormats() {
        assertEquals(1, hubPreviewImageSample("image/jpeg", 800, 600))
        assertEquals(4, hubPreviewImageSample("image/png", 4000, 3000))
        assertEquals(4, hubPreviewImageSample("image/jpeg", 3201, 20))
        listOf("image/svg+xml", "text/html", "application/octet-stream", "image/unknown").forEach {
            assertNull(hubPreviewImageSample(it, 40, 40))
        }
        listOf(0 to 1, -1 to 100, 65537 to 1, 10001 to 10000, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (w, h) ->
            assertNull(hubPreviewImageSample("image/jpeg", w, h))
        }
    }
    @Test fun previewFailuresDistinguishLocalCacheFromAuthorizationWithoutExposingDetails() {
        val secret = "synthetic-never-display-credential"
        val local = hubAttachmentReadFailure(HubAttachmentReadStage.CACHE, IOException("$secret http://private/filename"))
        assertTrue(local.contains("本机缓存")); assertFalse(local.contains("授权")); assertFalse(local.contains(secret))
        val auth = hubAttachmentReadFailure(HubAttachmentReadStage.TRANSFER, OrbisTechHubException("unauthorized"))
        assertTrue(auth.contains("授权")); assertFalse(auth.contains("本机缓存"))
        HubAttachmentReadStage.entries.forEach { stage ->
            assertFalse(hubAttachmentReadFailure(stage, IOException(secret)).contains(secret))
            assertFalse(hubAttachmentReadFailure(stage, OrbisTechHubException(secret)).contains(secret))
        }
        assertTrue(hubAttachmentReadFailure(HubAttachmentReadStage.TRANSFER,
            OrbisTechHubException("redirect_refused")).contains("跳转已拦截"))
    }
}
