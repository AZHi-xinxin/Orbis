package me.rerere.rikkahub.data.orbis.privacy

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PrivateVaultRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val protector = TestProtector()
    private var wall = 1_000_000L
    private var elapsed = 100_000L
    private val owner = "synthetic-assistant"
    private fun repo(base: File = temporary.root, key: PrivateVaultKeyProtector = protector,
                     process: String = "synthetic-process", before: (String) -> Unit = {}) =
        PrivateVaultRepository(base, owner, key, { wall }, { elapsed }, process, before)
    private fun ready(repository: PrivateVaultRepository = repo()): Pair<PrivateVaultRepository, PrivateVaultCrypto.RecoveryMaterial> {
        val recovery = repository.create()
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        return repository to recovery
    }
    private fun denied(code: String? = null, action: () -> Unit) {
        try { action(); fail("Expected fail-closed") } catch (error: PrivateVaultException) {
            if (code != null) assertEquals(code, error.reasonCode)
            assertNull(error.cause)
        }
    }
    private fun room(base: File = temporary.root) = File(base, vaultDigest(owner))
    private fun pointer() = File(room(), "CURRENT").readText()
    private fun head() = File(room(), "generations/${pointer()}/HEAD").readText()
    private fun backup(repository: PrivateVaultRepository) = ByteArrayOutputStream().also(repository::exportEncrypted).toByteArray()

    @Test fun creationIsDisabledUntilOfflineRecoveryIsAcknowledged() {
        val repository = repo()
        assertEquals(PrivateVaultAvailability.ABSENT, repository.status().availability)
        assertFalse(room().exists())
        repository.create()
        assertFalse(repository.status().enabled)
        denied("recovery_not_confirmed") { repository.setEnabled(true) }
        denied("room_paused") { repository.openAiSession("private") }
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        assertTrue(repository.status().enabled)
        denied("already_exists") { repository.create() }
    }

    @Test fun diskContainsOnlyCiphertextNotTitlesBodiesPurposesOrRecoveryCode() {
        val (repository, recovery) = ready()
        repository.openAiSession("binding-secret").use {
            it.writeRecord("SYNTHETIC-PRIVATE-TITLE", "SYNTHETIC-PRIVATE-BODY")
        }
        repository.requestAccess("SYNTHETIC-PRIVATE-PURPOSE")
        val forbidden = listOf("SYNTHETIC-PRIVATE-TITLE", "SYNTHETIC-PRIVATE-BODY", "SYNTHETIC-PRIVATE-PURPOSE", recovery.recoveryCode, "binding-secret")
        room().walkTopDown().filter { it.isFile }.forEach { file ->
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            forbidden.forEach { assertFalse("plaintext present in encrypted storage", raw.contains(it)) }
        }
        assertFalse(recovery.toString().contains(recovery.recoveryCode))
    }

    @Test fun ordinaryRequestCannotReadUntilAnAiCapabilityDecidesIt() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val request = repository.requestAccess("read please")
        denied("access_not_approved") { repository.listGranted(request.id) }
        denied("access_not_approved") { repository.readGranted(request.id, record.id) }
        assertEquals(request.id, ai.pendingRequests().single().id)
        ai.decideAccess(request.id, true)
        assertEquals("body", repository.readGranted(request.id, record.id).body)
        assertTrue(repository.auditEvents().any { it.action == "human_read" && it.requestId == request.id })
    }

    @Test fun approvedScopeContainsFrozenVersionsNotFutureEditsOrOtherRecords() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("old-title", "old-body")
        val request = repository.requestAccess("frozen", listOf(record.id))
        ai.decideAccess(request.id, true)
        ai.writeRecord("new-title", "new-body", record.id)
        val other = ai.writeRecord("other", "other-body")
        assertEquals("old-body", repository.readGranted(request.id, record.id).body)
        assertEquals("old-title", repository.listGranted(request.id).single().title)
        assertEquals("new-body", ai.readRecord(record.id).body)
        denied("outside_scope") { repository.readGranted(request.id, other.id) }
    }

    @Test fun changedPendingScopeMustBeRequestedAgain() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("old", "old")
        val request = repository.requestAccess("read")
        ai.writeRecord("new", "new", record.id)
        denied("request_scope_changed") { ai.decideAccess(request.id, true) }
        assertFalse(repository.isAccessGranted(request.id))
    }

    @Test fun aiCanApproveOnlyAnExplicitSubsetWithoutChangingOriginalRecords() {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val shared = ai.writeRecord("shared", "shared body")
            val hidden = ai.writeRecord("hidden", "hidden body")
            val request = repository.requestAccess("ask about existing records")
            val approved = ai.decideAccess(request.id, true, listOf(shared.id))
            assertEquals(listOf(shared.id), approved.recordIds)
            assertEquals(listOf(shared.id), repository.requestsForHuman().single().recordIds)
            assertEquals(listOf(shared.id), repository.listGranted(request.id).map { it.id })
            denied("outside_scope") { repository.readGranted(request.id, hidden.id) }
            assertEquals("hidden body", ai.readRecord(hidden.id).body)
            ai.writeRecord("shared changed", "future body", shared.id)
            val future = ai.writeRecord("future", "new record")
            assertEquals("shared body", repository.readGranted(request.id, shared.id).body)
            denied("outside_scope") { repository.readGranted(request.id, future.id) }
            repository.revokeAccess(request.id)
            assertFalse(repository.isAccessGranted(request.id))
        }
    }

    @Test fun malformedOrOutsideSubsetIsRejectedAtomicallyWithoutConsumingPendingRequest() {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val first = ai.writeRecord("first", "one")
            val outside = ai.writeRecord("outside", "two")
            val request = repository.requestAccess("only first", listOf(first.id))
            val before = head()
            listOf(emptyList(), listOf(first.id, first.id), listOf("invalid"),
                listOf(first.id, vaultId()), listOf(first.id, outside.id)).forEach { scope ->
                denied { ai.decideAccess(request.id, true, scope) }
                assertEquals(before, head())
                assertEquals(PrivateVaultRequestStatus.PENDING, repository.requestsForHuman().single().status)
                assertFalse(repository.isAccessGranted(request.id))
            }
            ai.decideAccess(request.id, true, listOf(first.id))
            assertEquals("one", repository.readGranted(request.id, first.id).body)
        }
    }

    @Test fun subsetRequiresUnchangedChosenVersionsButNotUnchosenRecords() {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val first = ai.writeRecord("first", "one")
            val changed = ai.writeRecord("changed", "old")
            val request = repository.requestAccess("read")
            ai.writeRecord("changed", "new", changed.id)
            val before = head()
            denied("request_scope_changed") { ai.decideAccess(request.id, true, listOf(first.id, changed.id)) }
            assertEquals(before, head())
            ai.decideAccess(request.id, true, listOf(first.id))
            assertEquals(listOf(first.id), repository.listGranted(request.id).map { it.id })
        }
    }

    @Test fun deniedDecisionRejectsAnyScopeAndForeignRoomCannotApproveRequest() {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val first = ai.writeRecord("first", "one")
            val request = repository.requestAccess("read")
            val before = head()
            denied("invalid_scope") { ai.decideAccess(request.id, false, listOf(first.id)) }
            assertEquals(before, head())
            val foreign = PrivateVaultRepository(temporary.newFolder("foreign-room"), "other-owner", TestProtector())
            foreign.create(); foreign.confirmRecoverySaved(); foreign.setEnabled(true)
            foreign.openAiSession("foreign").use { other ->
                denied("request_missing") { other.decideAccess(request.id, true, listOf(first.id)) }
            }
            assertEquals(before, head())
            ai.decideAccess(request.id, false)
            assertFalse(repository.isAccessGranted(request.id))
        }
    }

    @Test fun subsetConsentStillExpiresOnTimeRestartAndPause() {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val first = ai.writeRecord("first", "one")
            ai.writeRecord("second", "two")
            val request = repository.requestAccess("read", accessDurationMs = 500)
            ai.decideAccess(request.id, true, listOf(first.id))
            assertTrue(repository.isAccessGranted(request.id))
            assertFalse(repo(process = "new-process").isAccessGranted(request.id))
            elapsed += 500
            assertFalse(repository.isAccessGranted(request.id))
            val next = repository.requestAccess("again")
            ai.decideAccess(next.id, true, listOf(first.id))
            repository.setEnabled(false)
            assertFalse(repository.isAccessGranted(next.id))
        }
    }

    @Test fun aiToolCannotApproveImplicitAllOrSmuggleScopeIntoDenial() = kotlinx.coroutines.test.runTest {
        val (repository) = ready()
        repository.openAiSession("private").use { ai ->
            val first = ai.writeRecord("first", "one")
            val hidden = ai.writeRecord("hidden", "two")
            val request = repository.requestAccess("read")
            val tool = me.rerere.rikkahub.data.orbis.privateroom.privateRoomTools(ai).single { it.name == "private_decide" }
            val before = head()
            listOf(
                "{\"request_id\":\"${request.id}\",\"approved\":true}",
                "{\"request_id\":\"${request.id}\",\"approved\":true,\"record_ids\":[]}",
                "{\"request_id\":\"${request.id}\",\"approved\":true,\"record_ids\":null}",
                "{\"request_id\":\"${request.id}\",\"approved\":false,\"record_ids\":[\"${first.id}\"]}",
                "{\"request_id\":\"${request.id}\",\"approved\":\"true\",\"record_ids\":[\"${first.id}\"]}",
            ).forEach { input ->
                try { tool.execute(kotlinx.serialization.json.Json.parseToJsonElement(input)); fail("explicit scope required") }
                catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
                assertEquals(before, head())
                assertFalse(repository.isAccessGranted(request.id))
            }
            tool.execute(kotlinx.serialization.json.Json.parseToJsonElement(
                "{\"request_id\":\"${request.id}\",\"approved\":true,\"record_ids\":[\"${first.id}\"]}"))
            assertEquals(listOf(first.id), repository.listGranted(request.id).map { it.id })
            denied("outside_scope") { repository.readGranted(request.id, hidden.id) }
        }
    }

    @Test fun deniedDecisionCannotLaterBeReusedAsAnApproval() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, false)
        denied("request_not_pending") { ai.decideAccess(request.id, true) }
        assertFalse(repository.isAccessGranted(request.id))
    }

    @Test fun humanAndAiCanBothRevokeExactRequests() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val first = repository.requestAccess("first")
        val second = repository.requestAccess("second")
        ai.decideAccess(first.id, true)
        ai.decideAccess(second.id, true)
        repository.revokeAccess(first.id)
        assertFalse(repository.isAccessGranted(first.id))
        assertTrue(repository.isAccessGranted(second.id))
        ai.revokeAccess(second.id)
        assertFalse(repository.isAccessGranted(second.id))
    }

    @Test fun grantsExpireAtWallDeadline() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read", accessDurationMs = 500)
        ai.decideAccess(request.id, true)
        wall += 500
        assertFalse(repository.isAccessGranted(request.id))
    }

    @Test fun monotonicDeadlineStillExpiresWhenWallClockStalls() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read", accessDurationMs = 500)
        ai.decideAccess(request.id, true)
        elapsed += 500
        assertFalse(repository.isAccessGranted(request.id))
    }

    @Test fun backwardsClockAndNewProcessBothInvalidateGrant() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        assertFalse(repo(process = "after-restart").isAccessGranted(request.id))
        wall -= 1
        assertFalse(repository.isAccessGranted(request.id))
    }

    @Test fun pausingRevokesHumanGrantsAndOldAiSessionsEvenAfterReenable() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        repository.setEnabled(false)
        assertFalse(repository.isAccessGranted(request.id))
        denied("room_paused") { ai.listRecords() }
        repository.setEnabled(true)
        denied("session_expired") { ai.writeRecord("not-written", "not-written") }
        assertEquals(PrivateVaultRequestStatus.REVOKED, repository.requestsForHuman().single().status)
    }

    @Test fun closedAiCapabilityCannotReadWriteOrApprove() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.close()
        denied("session_expired") { ai.listRecords() }
        denied("session_expired") { ai.writeRecord("title", "body") }
        denied("session_expired") { ai.decideAccess(request.id, true) }
    }

    @Test fun deleteRevokesRequestsWithoutDeletingUnrelatedRecords() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("one", "one")
        val other = ai.writeRecord("two", "two")
        val request = repository.requestAccess("read", listOf(record.id))
        ai.decideAccess(request.id, true)
        ai.deleteRecord(record.id)
        assertFalse(repository.isAccessGranted(request.id))
        assertEquals(other.id, ai.listRecords().single().id)
    }

    @Test fun failedStateCommitPreservesPreviousRecordAndHead() {
        var failWrites = false
        val (repository) = ready(repo(before = { if (failWrites && it == "head") error("synthetic-io-failure") }))
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("original", "original")
        val originalHead = head()
        failWrites = true
        denied("storage_failed") { ai.writeRecord("changed", "changed", record.id) }
        assertEquals(originalHead, head())
        failWrites = false
        assertEquals("original", ai.readRecord(record.id).body)
    }

    @Test fun failedReadAuditDoesNotReturnHumanPlaintext() {
        var failWrites = false
        val (repository) = ready(repo(before = { if (failWrites) error("synthetic-io-failure") }))
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        failWrites = true
        denied("storage_failed") { repository.readGranted(request.id, record.id) }
    }

    @Test fun pollingGrantDoesNotWriteNewAuditSnapshots() {
        val (repository) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        val initialHead = head()
        repeat(4) { assertTrue(repository.isAccessGranted(request.id)) }
        assertEquals(initialHead, head())
    }

    @Test fun encryptedBackupRestoresOnNewDeviceDisabledAndWithoutOldGrants() {
        val (repository, recovery) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        val destination = temporary.newFolder("new-device")
        val recovered = repo(destination, TestProtector())
        recovered.importEncrypted(ByteArrayInputStream(backup(repository)), recovery.recoveryCode)
        assertFalse(recovered.status().enabled)
        assertFalse(recovered.isAccessGranted(request.id))
        recovered.setEnabled(true)
        assertEquals("body", recovered.openAiSession("new-private").readRecord(record.id).body)
    }

    @Test fun wrongRecoveryCodeAndTamperedExportDoNotCreateDestinationRoom() {
        val (repository, recovery) = ready()
        repository.openAiSession("private").writeRecord("title", "body")
        val source = backup(repository)
        val wrong = PrivateVaultCrypto.create("unrelated").let { it.session.close(); it.recovery.recoveryCode }
        val recovered = repo(temporary.newFolder("destination"), TestProtector())
        denied("authentication_failed") { recovered.importEncrypted(ByteArrayInputStream(source), wrong) }
        assertEquals(PrivateVaultAvailability.ABSENT, recovered.status().availability)
        val changed = source.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        denied { recovered.importEncrypted(ByteArrayInputStream(changed), recovery.recoveryCode) }
        assertEquals(PrivateVaultAvailability.ABSENT, recovered.status().availability)
    }

    @Test fun importCannotOverwriteAnExistingRoom() {
        val (repository, recovery) = ready()
        val before = head()
        denied("already_exists") { repository.importEncrypted(ByteArrayInputStream(backup(repository)), recovery.recoveryCode) }
        assertEquals(before, head())
    }

    @Test fun assistantBindingCannotBeChangedByCopyingAnExport() {
        val (repository, recovery) = ready()
        val foreign = PrivateVaultRepository(temporary.newFolder("other"), "other-assistant", TestProtector())
        denied("invalid_format") { foreign.importEncrypted(ByteArrayInputStream(backup(repository)), recovery.recoveryCode) }
        assertEquals(PrivateVaultAvailability.ABSENT, foreign.status().availability)
    }

    @Test fun emergencyMarkerRequiresRecoveryEvenWhenOriginalDeviceKeySurvives() {
        val (repository, recovery) = ready()
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val request = repository.requestAccess("read")
        ai.decideAccess(request.id, true)
        assertTrue(validateEmergencyVaultCopy(room()))
        File(room(), "recovery-required.marker").writeText("1")
        assertEquals(PrivateVaultAvailability.RECOVERY_REQUIRED, repository.status().availability)
        assertFalse(repository.isAccessGranted(request.id))
        denied("recovery_required") { ai.readRecord(record.id) }
        repository.recoverLocal(recovery.recoveryCode)
        assertFalse(repository.status().enabled)
        assertFalse(repository.isAccessGranted(request.id))
        assertFalse(File(room(), "recovery-required.marker").exists())
        repository.setEnabled(true)
        assertEquals("body", repository.openAiSession("new-private").readRecord(record.id).body)
    }

    @Test fun wrongLocalRecoveryPreservesHeadMarkerAndCiphertext() {
        val (repository) = ready()
        repository.openAiSession("private").writeRecord("title", "body")
        File(room(), "recovery-required.marker").writeText("1")
        val before = head()
        denied("authentication_failed") { repository.recoverLocal("invalid-code") }
        assertEquals(before, head())
        assertTrue(File(room(), "recovery-required.marker").exists())
    }

    @Test fun interruptedNewGenerationNeverReplacesPreviousCurrent() {
        var failing = false
        val (repository, recovery) = ready(repo(before = { if (failing && it == "current") error("synthetic-failure") }))
        repository.openAiSession("private").writeRecord("title", "body")
        val original = pointer()
        val originalHead = head()
        failing = true
        denied("storage_failed") { repository.recoverLocal(recovery.recoveryCode) }
        assertEquals(original, pointer())
        assertEquals(originalHead, head())
    }

    @Test fun recoveryRotationPausesRoomAndDoesNotPersistCode() {
        val (repository, old) = ready()
        val ai = repository.openAiSession("private")
        ai.writeRecord("title", "body")
        val recovery = repository.issueRecoveryCode()
        assertNotEquals(old.recoveryCode, recovery.recoveryCode)
        assertFalse(repository.status().enabled)
        assertFalse(repository.status().recoveryConfirmed)
        denied("authentication_failed") { repository.recoverLocal(old.recoveryCode) }
        repository.recoverLocal(recovery.recoveryCode)
        assertEquals(1, repository.status().recordCount)
    }

    @Test fun rotatedDataKeyPreventsAnOldRecoveryWrapperDecryptingFutureContent() {
        val (repository, oldRecovery) = ready()
        repository.openAiSession("before").writeRecord("before-title", "before-body")
        val beforeEnvelope = decodeVaultJson<VaultEnvelope>(File(room(), "generations/${pointer()}/states/${head()}.vault").readBytes())
        val newRecovery = repository.issueRecoveryCode()
        repository.confirmRecoverySaved()
        repository.setEnabled(true)
        val future = repository.openAiSession("after").writeRecord("future-title", "future-body")
        val afterHead = head()
        val afterEnvelope = decodeVaultJson<VaultEnvelope>(File(room(), "generations/${pointer()}/states/$afterHead.vault").readBytes())
        assertNotEquals(beforeEnvelope.vaultId, afterEnvelope.vaultId)
        PrivateVaultCrypto.recover(beforeEnvelope.vaultId, oldRecovery.recoveryCode,
            vaultUnbase64(beforeEnvelope.recoveryKey, 66)).use { oldSession ->
            try {
                oldSession.decryptRecord("state:$afterHead:${vaultDigest(owner)}", vaultUnbase64(afterEnvelope.encryptedState, MAX_STATE_BYTES + 34))
                fail("An old data key must not decrypt new-generation state")
            } catch (_: PrivateVaultCrypto.AccessException) { /* fail closed */ }
        }
        repository.recoverLocal(newRecovery.recoveryCode)
        repository.setEnabled(true)
        assertEquals("future-body", repository.openAiSession("restored").readRecord(future.id).body)
    }

    @Test fun failedRecoveryRotationKeepsOriginalKeyCurrentAndRecord() {
        var failing = false
        val (repository, recovery) = ready(repo(before = { if (failing && it == "current") error("synthetic-failure") }))
        val ai = repository.openAiSession("private")
        val record = ai.writeRecord("title", "body")
        val oldGeneration = pointer()
        val oldHead = head()
        failing = true
        denied("storage_failed") { repository.issueRecoveryCode() }
        assertEquals(oldGeneration, pointer())
        assertEquals(oldHead, head())
        assertTrue(repository.status().enabled)
        failing = false
        assertEquals("body", ai.readRecord(record.id).body)
        repository.recoverLocal(recovery.recoveryCode)
        assertEquals(1, repository.status().recordCount)
    }

    @Test fun deeplyNestedExternalEnvelopeIsRejectedBeforeParserRecursion() {
        val deep = ("{\"encryptedState\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}").toByteArray()
        assertFalse(vaultJsonNestingAllowed(deep.toString(Charsets.UTF_8)))
        val malformed = ByteArrayOutputStream().also { out -> DataOutputStream(out).apply {
            write("ORPVBAK1".toByteArray()); writeUTF(vaultId()); writeInt(deep.size); write(deep); writeInt(0)
        } }.toByteArray()
        denied("invalid_format") { repo().importEncrypted(ByteArrayInputStream(malformed), "not-a-key") }
        assertEquals(PrivateVaultAvailability.ABSENT, repo().status().availability)
        val (repository) = ready()
        File(room(), "generations/${pointer()}/states/${head()}.vault").writeBytes(deep)
        assertFalse(validateEmergencyVaultCopy(room()))
        assertEquals(PrivateVaultAvailability.UNREADABLE, repository.status().availability)
    }

    @Test fun jsonDepthScanDoesNotMistakeQuotedBracesForNesting() {
        assertTrue(vaultJsonNestingAllowed("{\"title\":\"" + "[{".repeat(1000) + "\",\"body\":\"\\\"quoted\\\"\"}"))
        assertFalse(vaultJsonNestingAllowed("{\"unfinished\":\"text"))
    }

    @Test fun oversizedLengthsAndTrailingBackupBytesFailClosed() {
        val (repository, recovery) = ready()
        val recovered = repo(temporary.newFolder("destination"), TestProtector())
        val malicious = ByteArrayOutputStream().also { out -> DataOutputStream(out).apply {
            write("ORPVBAK1".toByteArray()); writeUTF(vaultId()); writeInt(Int.MAX_VALUE)
        } }.toByteArray()
        denied("invalid_format") { recovered.importEncrypted(ByteArrayInputStream(malicious), recovery.recoveryCode) }
        denied("invalid_format") { recovered.importEncrypted(ByteArrayInputStream(backup(repository) + byteArrayOf(1)), recovery.recoveryCode) }
    }

    @Test fun structuralValidatorIgnoresInterruptedPointerButRejectsUnknownPayload() {
        val (repository) = ready()
        repository.openAiSession("private").writeRecord("title", "body")
        File(room(), ".pointer-${vaultId()}").writeText(vaultId())
        assertTrue(validateEmergencyVaultCopy(room()))
        File(room(), "unexpected.txt").writeText("not-vault-data")
        assertFalse(validateEmergencyVaultCopy(room()))
    }

    private class TestProtector : PrivateVaultKeyProtector {
        private val random = SecureRandom()
        private val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(vaultId.toByteArray())
            return nonce + cipher.doFinal(dataKey)
        }
        override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrappedKey.copyOfRange(0, 12)))
            cipher.updateAAD(vaultId.toByteArray())
            cipher.doFinal(wrappedKey, 12, wrappedKey.size - 12)
        } catch (_: Exception) { throw PrivateVaultException("device_key_unavailable") }
    }
}
