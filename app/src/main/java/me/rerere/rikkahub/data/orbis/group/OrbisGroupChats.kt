package me.rerere.rikkahub.data.orbis.group

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** Group-only coordinator. No private conversation repository, memory repository or task hub. */
class OrbisGroupChats internal constructor(
    private val storage: OrbisGroupStorage,
    private val settingsSource: () -> Settings,
    private val responder: OrbisGroupResponder,
    private val scope: CoroutineScope,
    private val attachmentFiles: OrbisGroupAttachments? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, scope: CoroutineScope, settingsStore: SettingsStore, generationLoop: GenerationLoop) : this(
        AndroidOrbisGroupStorage(context), { settingsStore.settingsFlow.value }, GenerationLoopGroupResponder(generationLoop), scope,
        attachmentFiles = OrbisGroupAttachments(context),
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(OrbisGroupState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var storageBlocked = false
    private val jobs = mutableMapOf<String, Job>()
    private val active = mutableMapOf<String, RunningRound>()
    private val runningMembers = mutableMapOf<String, String>()
    private val stopped = mutableSetOf<String>()
    private class RunningRound(val round: GroupRound, val members: List<OrbisGroupMember>,
        val messageIds: List<String>, val settings: Settings)

    init { scope.launch { try { reload() } catch (_: OrbisGroupException) { /* Exposed as safe state. */ } } }

    suspend fun reload() = locked {
        if (storageBlocked) {
            groupCheck(active.isEmpty(), "busy")
            storage.recoverInterrupted(now())
        }
        initializeLocked()
        publishLocked()
        storageBlocked = false
        mutableState.value = state.value.copy(error = null)
    }

    suspend fun selectRoom(roomId: String?) = locked {
        initializeLocked()
        if (roomId != null) requiredRoom(roomId)
        mutableState.value = state.value.copy(selectedRoomId = roomId, messages = emptyList(), hasEarlier = false, hasLater = false)
        publishLocked(forceLatest = true)
    }

    suspend fun createRoom(title: String): String = locked {
        initializeLocked()
        groupCheck(!storageBlocked, "storage_unavailable")
        val checked = title.trim()
        groupCheck(checked.length in 1..80 && checked.none { it.isISOControl() }, "invalid_title")
        groupCheck(storage.rooms().size < 64, "room_limit")
        val time = now()
        val room = OrbisGroupRoom(Uuid.random().toString(), checked, createdAt = time, updatedAt = time)
        storage.commit(room = room)
        mutableState.value = state.value.copy(selectedRoomId = room.id, hasLater = false)
        publishLocked(forceLatest = true)
        room.id
    }

    suspend fun addMember(roomId: String, assistantId: Uuid, modelId: Uuid) = locked {
        initializeLocked(); idle(roomId)
        val room = requiredRoom(roomId)
        groupCheck(room.members.size < ORBIS_GROUP_MEMBER_LIMIT, "member_limit")
        val settings = settingsSource()
        groupCheck(!settings.init, "settings_loading")
        val assistants = settings.assistants.filter { it.id == assistantId }
        groupCheck(assistants.size == 1, "assistant_missing")
        val owners = settings.providers.flatMap { provider -> provider.models.filter { it.id == modelId }.map { provider to it } }
        groupCheck(owners.isNotEmpty(), "model_missing")
        groupCheck(owners.size == 1, "ambiguous_model")
        val (provider, model) = owners.single()
        val assistant = assistants.single()
        val name = groupName(assistant.name.ifBlank { model.displayName.ifBlank { model.modelId } }).ifBlank { "AI" }
        val member = OrbisGroupMember(Uuid.random().toString(), assistantId, modelId, provider.id, name, assistant.avatar, now())
        resolveGroupParticipant(settings, member) // Includes provider uniqueness, disabled and model type checks.
        storage.commit(room = room.copy(members = room.members + member, updatedAt = now()))
        publishLocked()
    }

    suspend fun removeMember(roomId: String, memberId: String) = locked {
        initializeLocked(); idle(roomId)
        val room = requiredRoom(roomId)
        groupCheck(room.members.any { it.id == memberId }, "member_missing")
        storage.commit(room = room.copy(members = room.members.filterNot { it.id == memberId }, updatedAt = now()))
        // Existing message snapshots are intentionally unchanged.
        publishLocked()
    }

    suspend fun send(roomId: String, text: String, targetMemberId: String? = null,
        attachments: List<OrbisGroupAttachment> = emptyList()) = locked {
        initializeLocked(); idle(roomId)
        groupCheck((text.isNotBlank() || attachments.isNotEmpty()) && text.toByteArray(Charsets.UTF_8).size <= GROUP_INPUT_BYTES, "invalid_input")
        validateGroupAttachments(attachments)
        if (attachments.isNotEmpty()) (attachmentFiles ?: throw OrbisGroupException("invalid_attachment")).validate(attachments)
        val room = requiredRoom(roomId)
        val targets = if (targetMemberId == null) room.members else listOf(room.members.find { it.id == targetMemberId }
            ?: throw OrbisGroupException("member_missing"))
        groupCheck(targets.isNotEmpty(), "no_members")
        val settings = settingsSource()
        groupCheck(!settings.init, "settings_loading")
        val round = GroupRound(Uuid.random().toString(), roomId, targets.map { it.id }, now())
        val human = OrbisGroupMessage(Uuid.random().toString(), roomId, round.id, null,
            groupName(settings.displaySetting.userNickname).ifBlank { "人类" }, text = text,
            createdAt = now(), updatedAt = now(), attachments = attachments.toList(), avatar = settings.displaySetting.userAvatar)
        startRoundLocked(room, round, targets, settings, listOf(human))
    }

    suspend fun retry(roomId: String, messageId: String) = retryInternal(roomId, messageId, allowComplete = false)

    /** Explicitly requested new alternative; keep the completed original and other members unchanged. */
    suspend fun regenerate(roomId: String, messageId: String) = retryInternal(roomId, messageId, allowComplete = true)

    private suspend fun retryInternal(roomId: String, messageId: String, allowComplete: Boolean) = locked {
        initializeLocked(); idle(roomId)
        val room = requiredRoom(roomId)
        val message = storage.message(messageId) ?: throw OrbisGroupException("not_retryable")
        groupCheck(message.roomId == roomId && message.memberId != null &&
            (message.status in setOf(OrbisGroupMessageStatus.FAILED, OrbisGroupMessageStatus.INTERRUPTED) ||
                (allowComplete && message.status == OrbisGroupMessageStatus.COMPLETE)), "not_retryable")
        groupCheck(storage.latestRound(roomId)?.id == message.roundId, "stale_retry")
        val member = room.members.find { it.id == message.memberId } ?: throw OrbisGroupException("member_missing")
        val settings = settingsSource()
        groupCheck(!settings.init, "settings_loading")
        val round = GroupRound(Uuid.random().toString(), roomId, listOf(member.id), now(), retryOfMessageId = messageId)
        startRoundLocked(room, round, listOf(member), settings, emptyList())
    }

    suspend fun stop(roomId: String) {
        var job: Job? = null
        var failure: OrbisGroupException? = null
        try {
            locked {
                initializeLocked()
                job = jobs[roomId]
                val running = active[roomId] ?: return@locked
                stopped += running.round.id
                try {
                    interruptPendingLocked(running)
                    publishLocked()
                } finally { job?.cancel() }
            }
        } catch (error: OrbisGroupException) { failure = error }
        job?.cancelAndJoin()
        // A job cancelled before its body starts does not execute runRound's finally block.
        // Identity guard also prevents an old stop operation from clearing a newer round.
        try {
            locked {
                if (job != null && jobs[roomId] === job) {
                    active.remove(roomId)?.let { stopped.remove(it.round.id) }
                    jobs.remove(roomId); runningMembers.remove(roomId)
                    publishLocked()
                }
            }
        } catch (error: OrbisGroupException) { if (failure == null) failure = error }
        failure?.let { throw it }
    }

    suspend fun loadEarlier() = locked {
        initializeLocked()
        val roomId = state.value.selectedRoomId ?: return@locked
        val before = state.value.messages.firstOrNull()?.sequence ?: return@locked
        val rows = storage.messages(roomId, GROUP_PAGE_SIZE + 1, before)
        if (rows.isNotEmpty()) mutableState.value = state.value.copy(messages = rows.takeLast(GROUP_PAGE_SIZE),
            hasEarlier = rows.size > GROUP_PAGE_SIZE, hasLater = true)
        else mutableState.value = state.value.copy(hasEarlier = false)
    }

    private suspend fun startRoundLocked(room: OrbisGroupRoom, round: GroupRound, members: List<OrbisGroupMember>,
        settings: Settings, preceding: List<OrbisGroupMessage>) {
        // Mobile resource guard: only one group round at a time, without touching private chat jobs.
        groupCheck(active.isEmpty(), "other_room_busy")
        val messages = members.map { member -> OrbisGroupMessage(Uuid.random().toString(), room.id, round.id,
            member.id, member.name, member.modelId, status = OrbisGroupMessageStatus.QUEUED,
            createdAt = now(), updatedAt = now(), avatar = member.avatar) }
        // Human message, plan and pending member messages are one transaction, before any request.
        storage.commit(room.copy(updatedAt = now()), round, preceding + messages)
        val running = RunningRound(round, members.toList(), messages.map { it.id }, settings)
        active[room.id] = running
        val job = scope.launch(start = CoroutineStart.LAZY) { runRound(running) }
        jobs[room.id] = job
        try { publishLocked() }
        catch (failure: Exception) {
            job.cancel()
            active.remove(room.id); jobs.remove(room.id)
            try { interruptPendingLocked(running) } catch (_: Exception) { /* Retry only through explicit reload. */ }
            throw failure
        }
        job.start()
    }

    private suspend fun runRound(running: RunningRound) {
        val roomId = running.round.roomId
        var failedStorage = false
        var interrupted = false
        try {
            running.members.forEachIndexed { index, member ->
                currentCoroutineContext().ensureActive()
                val messageId = running.messageIds[index]
                try {
                    val participant = resolveGroupParticipant(running.settings, member)
                    val input = locked {
                        stillRunning(running)
                        val message = storage.message(messageId) ?: throw OrbisGroupException("storage_unavailable")
                        val history = storage.messages(roomId, GROUP_PAGE_SIZE)
                        val context = buildGroupContext(history, participant, storage.countMessages(roomId)) { attachment ->
                            android.net.Uri.fromFile((attachmentFiles ?: throw OrbisGroupException("invalid_attachment")).file(attachment)).toString()
                        }
                        storage.commit(messages = listOf(message.copy(status = OrbisGroupMessageStatus.GENERATING, updatedAt = now())))
                        runningMembers[roomId] = member.id
                        publishLocked()
                        if (state.value.selectedRoomId == roomId) mutableState.value = state.value.copy(contextInfo =
                            "本次给 ${member.name} 提供本群 ${context.included} 条文字" +
                                (if (context.omitted) "（更早内容仍保留，未全部发送）" else "") +
                                "。发送窗口最多 200 条 / 128 KiB；不共享私聊记忆、工具或通话。")
                        GroupGenerationInput(roomId, participant, context.messages)
                    }
                    val result = withTimeout(180_000) {
                        responder.generate(input) { text ->
                            groupCheck(text.toByteArray(Charsets.UTF_8).size <= GROUP_OUTPUT_BYTES, "response_too_large")
                            locked {
                                stillRunning(running)
                                val old = storage.message(messageId) ?: throw OrbisGroupException("storage_unavailable")
                                groupCheck(old.status == OrbisGroupMessageStatus.GENERATING, "interrupted")
                                storage.commit(messages = listOf(old.copy(text = text, updatedAt = now())))
                                publishLocked()
                            }
                        }
                    }
                    groupCheck(result.isNotBlank(), "empty_reply")
                    groupCheck(result.toByteArray(Charsets.UTF_8).size <= GROUP_OUTPUT_BYTES, "response_too_large")
                    locked {
                        stillRunning(running)
                        val old = storage.message(messageId) ?: throw OrbisGroupException("storage_unavailable")
                        storage.commit(messages = listOf(old.copy(text = result, status = OrbisGroupMessageStatus.COMPLETE,
                            errorReason = null, errorDetails = null, updatedAt = now())))
                        publishLocked()
                    }
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    markFailed(running, messageId, classifyGroupFailure(timeout))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    val details = classifyGroupFailure(failure)
                    if (details.category == "storage_unavailable") { failedStorage = true; throw OrbisGroupException(details.category) }
                    markFailed(running, messageId, details)
                }
            }
        } catch (cancelled: CancellationException) { interrupted = true
        } catch (_: Exception) { failedStorage = true; interrupted = true
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    try {
                        if (active[roomId] === running) {
                            if (interrupted || failedStorage || running.round.id in stopped) interruptPendingLocked(running)
                            else storage.commit(round = running.round.copy(status = GroupRoundStatus.FINISHED))
                        }
                    } catch (_: Exception) { failedStorage = true }
                    if (active[roomId] === running) {
                        active.remove(roomId); jobs.remove(roomId); runningMembers.remove(roomId)
                    }
                    stopped.remove(running.round.id)
                    try { publishLocked() } catch (_: Exception) { failedStorage = true }
                    if (failedStorage) {
                        storageBlocked = true
                        mutableState.value = state.value.copy(error = OrbisGroupException("storage_unavailable").message,
                            runningRoomIds = active.keys.toSet(), runningMemberId = runningMembers[state.value.selectedRoomId])
                    }
                }
            }
        }
    }

    private suspend fun markFailed(running: RunningRound, messageId: String, details: OrbisGroupFailureDetails) = locked {
        stillRunning(running)
        val old = storage.message(messageId) ?: throw OrbisGroupException("storage_unavailable")
        val safe = details.sanitized()
        storage.commit(messages = listOf(old.copy(status = OrbisGroupMessageStatus.FAILED,
            errorReason = safe.category, errorDetails = safe, updatedAt = now())))
        publishLocked()
    }

    private suspend fun interruptPendingLocked(running: RunningRound) {
        val pending = running.messageIds.mapNotNull { storage.message(it) }.filter {
            it.status == OrbisGroupMessageStatus.QUEUED || it.status == OrbisGroupMessageStatus.GENERATING
        }.map { it.copy(status = OrbisGroupMessageStatus.INTERRUPTED, errorReason = "interrupted", updatedAt = now()) }
        storage.commit(round = running.round.copy(status = GroupRoundStatus.INTERRUPTED), messages = pending)
    }

    private fun stillRunning(running: RunningRound) {
        groupCheck(!storageBlocked, "storage_unavailable")
        if (active[running.round.roomId] !== running || running.round.id in stopped) throw CancellationException("group_stopped")
    }

    private suspend fun requiredRoom(roomId: String) = storage.room(roomId) ?: throw OrbisGroupException("room_missing")

    /** Explicit private-to-group reads never initialize, recover, publish, enqueue or write. */
    internal suspend fun listReadableGroups(assistantId: Uuid, limit: Int, afterRoomId: String?) = mutex.withLock {
        requireGroupReadAssistant(assistantId)
        val rooms = storage.rooms()
        requireGroupReadAssistant(assistantId)
        OrbisGroupReadPolicy.listRooms(rooms, assistantId, limit, afterRoomId)
    }

    internal suspend fun readReadableGroup(
        assistantId: Uuid, roomId: String, limit: Int, beforeSequence: Long?, onlyOwn: Boolean,
    ) = mutex.withLock {
        requireGroupReadAssistant(assistantId)
        // Authorize BEFORE selecting message bodies; all group membership edits use this mutex.
        val room = OrbisGroupReadPolicy.authorize(storage.room(roomId), assistantId)
        if (limit !in 1..ORBIS_GROUP_READ_MAX_MESSAGES || (beforeSequence != null && beforeSequence <= 0L))
            throw OrbisGroupReadException("invalid_pagination")
        val rows = storage.messages(roomId, limit + 1, beforeSequence)
        // Settings can change outside this mutex. Check the assistant still exists after IO too.
        requireGroupReadAssistant(assistantId)
        OrbisGroupReadPolicy.readMessages(room, assistantId, rows, limit, beforeSequence, onlyOwn)
    }

    private fun requireGroupReadAssistant(assistantId: Uuid) {
        val settings = settingsSource()
        if (settings.init || storageBlocked || settings.assistants.count { it.id == assistantId } != 1)
            throw OrbisGroupReadException("group_records_unavailable")
    }

    private fun idle(roomId: String) {
        groupCheck(!storageBlocked, "storage_unavailable")
        groupCheck(roomId !in active, "busy")
    }

    private suspend fun initializeLocked() {
        if (initialized) return
        storage.recoverInterrupted(now()) // Mark only; no models, retries, tools or task messages.
        mutableState.value = state.value.copy(loaded = true, error = null, rooms = storage.rooms())
        initialized = true
    }

    private suspend fun publishLocked(forceLatest: Boolean = false) {
        val old = state.value
        val rooms = storage.rooms()
        val selected = old.selectedRoomId?.takeIf { id -> rooms.any { it.id == id } }
        var next = old.copy(loaded = true, error = if (storageBlocked) old.error else null, rooms = rooms, selectedRoomId = selected,
            runningRoomIds = active.keys.toSet(), runningMemberId = runningMembers[selected])
        if (selected == null) next = next.copy(messages = emptyList(), hasEarlier = false, hasLater = false)
        else if (forceLatest || !old.hasLater) {
            val rows = storage.messages(selected, GROUP_PAGE_SIZE + 1)
            next = next.copy(messages = rows.takeLast(GROUP_PAGE_SIZE), hasEarlier = rows.size > GROUP_PAGE_SIZE, hasLater = false)
        }
        mutableState.value = next
    }

    private suspend fun <T> locked(action: suspend () -> T): T = mutex.withLock {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OrbisGroupException) {
            if (failure.code == "storage_unavailable") {
                storageBlocked = true
                mutableState.value = state.value.copy(error = failure.message)
            }
            throw failure
        }
        catch (_: Exception) {
            storageBlocked = true
            mutableState.value = state.value.copy(error = OrbisGroupException("storage_unavailable").message)
            throw OrbisGroupException("storage_unavailable")
        }
    }
}
