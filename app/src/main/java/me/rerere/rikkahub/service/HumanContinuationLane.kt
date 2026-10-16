package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.ai.hostToolFailure
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.needsIndependentDispatch
import me.rerere.rikkahub.data.orbis.workshop.WorkshopJob
import me.rerere.rikkahub.data.orbis.workshop.WorkshopStatus

/** Unknown prior side effects never replay automatically or block fresh chat.
 * Fresh calls honor an explicit current grant; automatic event turns can require fresh approval.
 */
internal fun reviewUncertainContinuationTools(
    tools: List<Tool>, messages: List<UIMessage>, requireFreshApproval: Boolean = false,
    hasCurrentGrant: (Tool) -> Boolean = { false },
): List<Tool> {
    val uncertainNames = messages.flatMap { it.getTools() }.filter {
        it.hostToolFailure()?.let { failure -> failure.executionPerformed == null } == true
    }.map { it.toolName }.toSet()
    return tools.map { tool -> if (!requireFreshApproval && (tool.name !in uncertainNames || hasCurrentGrant(tool))) tool else tool.copy(
        needsApproval = { true },
    ) }
}

internal sealed interface HumanContinuationRegeneration {
    data class Blocked(val notice: String) : HumanContinuationRegeneration

    /** Only a request projection is shortened. The visible/persisted conversation is never truncated. */
    data class Ready(val original: Conversation, val request: Conversation) : HumanContinuationRegeneration {
        fun merge(generated: Conversation): Conversation {
            require(generated.id == original.id && generated.assistantId == original.assistantId)
            val prefix = request.currentMessages
            val messages = generated.currentMessages
            require(messages.take(prefix.size).map { it.id } == prefix.map { it.id })
            val replies = messages.drop(prefix.size)
            val existingIds = original.messageNodes.flatMap { it.messages }.map { it.id }.toSet()
            require(replies.none { it.id in existingIds }) { "重新生成不能覆盖原回复。" }
            // updateCurrentMessages appends/selects a new alternative at the original tail node.
            // Even a failed request with no delta leaves every original branch untouched.
            return original.updateCurrentMessages(prefix + replies)
        }
    }
}

/** Retrying old tool-bearing turns is not a fresh authorization to repeat their effects. */
internal fun prepareHumanContinuationRegeneration(
    conversation: Conversation, message: UIMessage,
): HumanContinuationRegeneration {
    fun blocked() = HumanContinuationRegeneration.Blocked("恢复期间仅能重试最后一轮文字回复，请直接发送新消息。")
    val selected = conversation.currentMessages
    val targetIndex = selected.indexOfFirst { it.id == message.id }
    val userIndex = selected.indexOfLast { it.role == MessageRole.USER }
    if (targetIndex < 0 || userIndex < 0 || targetIndex < userIndex ||
        selected.size - userIndex > 2 ||
        selected.drop(userIndex + 1).any { it.role != MessageRole.ASSISTANT }) return blocked()
    // Inspect every alternative and local undo receipt, not just the currently visible prose.
    val turn = conversation.messageNodes.drop(userIndex).flatMap { it.messages }
    if (turn.any { it.deletedToolRecords.isNotEmpty() || it.parts.any { part ->
            part is UIMessagePart.Tool || part is UIMessagePart.ServerTool
        } } || selected.any { it.getTools().any { tool -> !tool.isExecuted } }) {
        return HumanContinuationRegeneration.Blocked("该轮有工具记录；请发新消息确认是否重试，旧工具不会自动重跑。")
    }
    if (turn.any { it.orbisVoiceCallId != null || it.orbisEvent != null || it.isSynthetic ||
            it.parts.any { part -> part !is UIMessagePart.Text && part !is UIMessagePart.Reasoning } }) return blocked()
    return HumanContinuationRegeneration.Ready(conversation,
        conversation.copy(messageNodes = conversation.messageNodes.take(userIndex + 1)))
}

/** A human send is a new turn, NOT an acknowledgement/replay of an interrupted turn.
 * This lane never waits for the old job, journal, queue pause file, or database writer.
 * Its separate snapshot prevents a late old producer/finalizer from replacing new input.
 * Persistence is an independent best-effort observer, never a generation prerequisite.
 */
internal class HumanContinuationLane(
    val baseline: Conversation,
    private val scope: CoroutineScope,
    private val persist: suspend (Conversation) -> Boolean,
    private val onFailure: (Exception) -> Unit,
    private val onIdleSaved: (HumanContinuationLane) -> Unit = {},
) {
    val state = MutableStateFlow(baseline)
    val job = MutableStateFlow<Job?>(null)
    val notice = MutableStateFlow("已启用独立续聊，旧错误不阻挡新消息。正在核对保存状态。")
    val contextNotice = MutableStateFlow("恢复通道保留角色设定和可读聊天；暂不自动整理历史或自动注入本机长期记忆。")
    // Presentation is separate from the lane's control status: healthy sends/saves stay silent.
    val persistenceWarning = MutableStateFlow<String?>(null)
    private var revision = 0L
    private var turn = 0L
    private var savedRevision = -1L
    private var retired = false
    private val activeJobs = mutableSetOf<Job>()
    private var persistenceJob: Job? = null
    private val workshopReturns = mutableMapOf<String, WorkshopJob>()
    private var workshopReservation: WorkshopReturnReservation? = null
    private var sentinelReservation: SentinelReservation? = null

    internal data class SentinelReservation(val owner: Long, val event: OrbisInboxEvent)

    /** Fresh notifications may wake an idle continuation lane, never replay an old attempt. */
    @Synchronized fun reserveSentinel(event: OrbisInboxEvent, targetMatches: Boolean,
        deliveryAllowed: Boolean, resetting: Boolean = false): SentinelReservation? {
        if (retired || !targetMatches || !deliveryAllowed || resetting || !event.needsIndependentDispatch() ||
            !(me.rerere.rikkahub.data.orbis.isNativeSentinelSource(event.source) ||
                event.source in me.rerere.rikkahub.data.orbis.ORBIS_EVENT_SOURCES) ||
            event.assistantId != baseline.assistantId.toString() || event.conversationId != baseline.id.toString() ||
            event.workshopImages.isNotEmpty() || job.value != null || workshopReservation != null ||
            sentinelReservation != null ||
            state.value.currentMessages.any { it.getTools().any { tool -> !tool.isExecuted } } ||
            state.value.messageNodes.any { node -> node.messages.any { it.id.toString() == event.id } }) return null
        return SentinelReservation(turn, event).also { sentinelReservation = it }
    }

    @Synchronized fun releaseSentinel(reservation: SentinelReservation) {
        if (sentinelReservation === reservation) sentinelReservation = null
        notifyIdleSaved()
    }

    @Synchronized fun sendSentinel(reservation: SentinelReservation, input: UIMessage,
        claimConfirmed: Boolean, generate: suspend (Conversation, Long, (Conversation) -> Unit) -> Unit): Job? {
        if (sentinelReservation !== reservation) return null
        sentinelReservation = null
        val metadata = input.orbisEvent
        if (!claimConfirmed || !isCurrent(reservation.owner) || job.value != null ||
            input.role != MessageRole.USER || input.id.toString() != reservation.event.id ||
            metadata?.recordId != reservation.event.id || metadata.source != reservation.event.source ||
            metadata.eventId != reservation.event.eventId) return null
        return send(input, generate = generate)
    }

    internal data class WorkshopReturnReservation(val owner: Long, val event: OrbisInboxEvent,
        val accepted: WorkshopJob)

    /** Called only by the host's new-ledger-acceptance callback, before worker enqueue. */
    @Synchronized fun registerWorkshopReturn(owner: Long, accepted: WorkshopJob): Boolean {
        if (!isCurrent(owner) || !accepted.byAi || !accepted.notifyConversation ||
            accepted.status != WorkshopStatus.QUEUED || accepted.assets.isNotEmpty() || accepted.delivered ||
            accepted.assistantId != baseline.assistantId.toString() ||
            accepted.conversationId != baseline.id.toString() || accepted.id in workshopReturns) return false
        workshopReturns[accepted.id] = accepted
        return true
    }

    @Synchronized fun matchesWorkshopReturn(event: OrbisInboxEvent, completed: WorkshopJob): Boolean {
        val accepted = workshopReturns[completed.id] ?: return false
        return event.independentDelivery && event.source == "image_workshop.${accepted.id}" &&
            event.eventId == "result-${accepted.id}" && event.localImage == null &&
            event.assistantId == baseline.assistantId.toString() && event.conversationId == baseline.id.toString() &&
            event.workshopImages == completed.assets.map { it.file }.distinct() &&
            completed.status in setOf(WorkshopStatus.COMPLETE, WorkshopStatus.FAILED,
                WorkshopStatus.INTERRUPTED, WorkshopStatus.CANCELLED) &&
            completed.copy(status = accepted.status, assets = accepted.assets, error = accepted.error,
                delivered = accepted.delivered) == accepted
    }

    /** Reserve RAM only. Claiming the durable event must happen outside all session/lane locks. */
    @Synchronized fun reserveWorkshopReturn(event: OrbisInboxEvent, completed: WorkshopJob,
        targetMatches: Boolean, deliveryAllowed: Boolean, imageInputSupported: Boolean,
        resetting: Boolean = false): WorkshopReturnReservation? {
        if (retired || !targetMatches || !deliveryAllowed || resetting ||
            event.workshopImages.isNotEmpty() && !imageInputSupported ||
            !event.needsIndependentDispatch() || !matchesWorkshopReturn(event, completed) ||
            job.value != null || workshopReservation != null || sentinelReservation != null ||
            state.value.currentMessages.any { it.getTools().any { tool -> !tool.isExecuted } } ||
            state.value.messageNodes.any { node -> node.messages.any { it.id.toString() == event.id } }) return null
        return WorkshopReturnReservation(turn, event, workshopReturns.getValue(completed.id)).also {
            workshopReservation = it
        }
    }

    @Synchronized fun releaseWorkshopReturn(reservation: WorkshopReturnReservation) {
        if (workshopReservation === reservation) workshopReservation = null
        notifyIdleSaved()
    }

    /** A human send during a slow claim wins. No wait, replay or replacement of that human turn. */
    @Synchronized fun sendWorkshopReturn(reservation: WorkshopReturnReservation, input: UIMessage,
        claimConfirmed: Boolean, generate: suspend (Conversation, Long, (Conversation) -> Unit) -> Unit): Job? {
        if (workshopReservation !== reservation) return null
        workshopReservation = null
        val metadata = input.orbisEvent
        if (!claimConfirmed || !isCurrent(reservation.owner) || job.value != null ||
            workshopReturns[reservation.accepted.id] != reservation.accepted ||
            input.role != MessageRole.USER || input.id.toString() != reservation.event.id ||
            metadata?.recordId != reservation.event.id ||
            metadata.source != reservation.event.source || metadata.eventId != reservation.event.eventId) return null
        workshopReturns.remove(reservation.accepted.id)
        return send(input, generate = generate)
    }

    @Synchronized fun isCurrent(owner: Long): Boolean = !retired && owner == turn

    internal data class ApprovalTarget(val snapshot: Conversation, val message: UIMessage,
        val tool: UIMessagePart.Tool)

    @Synchronized fun captureApproval(callId: String): ApprovalTarget? {
        if (retired) return null
        val snapshot = state.value
        val message = snapshot.currentMessages.firstOrNull { msg ->
            msg.getTools().any { it.toolCallId == callId && it.isPending && !it.isExecuted }
        } ?: return null
        return ApprovalTarget(snapshot, message, message.getTools().single {
            it.toolCallId == callId && it.isPending && !it.isExecuted
        })
    }

    /** Match the displayed call, not an entire UI snapshot that may finish while loading tools.
     * New human input, changed calls and duplicate taps cannot save a grant or execute a tool.
     */
    @Synchronized fun resolveApproval(target: ApprovalTarget,
        resolve: (UIMessagePart.Tool) -> UIMessagePart.Tool,
        generate: suspend (Conversation, Long, (Conversation) -> Unit) -> Unit,
    ): Boolean {
        if (retired) return false
        val current = state.value
        if (current.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id !=
            target.snapshot.currentMessages.lastOrNull { it.role == MessageRole.USER }?.id) return false
        val message = current.currentMessages.firstOrNull { it.id == target.message.id } ?: return false
        if (message.getTools().none { it == target.tool && it.isPending && !it.isExecuted }) return false
        val resolved = resolve(target.tool)
        require(resolved.toolCallId == target.tool.toolCallId && resolved.toolName == target.tool.toolName &&
            resolved.input == target.tool.input && (!resolved.isPending || resolved.isExecuted))
        state.value = current.copy(messageNodes = current.messageNodes.map { node ->
            node.copy(messages = node.messages.map { msg ->
                if (msg.id != message.id) msg else msg.copy(parts = msg.parts.map { part ->
                    if (part == target.tool) resolved else part
                })
            })
        })
        // Fence/cancel a finishing producer before continuing. Never join an unrelated old job.
        send(null, answer = state.value.currentMessages.none {
            it.getTools().any { tool -> tool.isPending && !tool.isExecuted }
        }, generate = generate)
        return true
    }

    /** The caller separately verifies disk history, no journal and no old session job while
     * holding the history edit mutex. This is a CAS handoff, never a queue resume or replay.
     */
    private fun idleAndSaved(allowPendingApprovals: Boolean = false): Boolean = !retired && savedRevision == revision && persistenceJob == null &&
        activeJobs.none { !it.isCompleted } && job.value == null && workshopReservation == null &&
        sentinelReservation == null && workshopReturns.isEmpty() &&
        state.value.currentMessages.none { it.getTools().any { tool ->
            !tool.isExecuted && !(allowPendingApprovals && tool.isPending)
        } }

    private fun notifyIdleSaved() {
        if (idleAndSaved()) onIdleSaved(this)
    }

    @Synchronized fun retireForHistoryEdit(expected: Conversation, allowPendingApprovals: Boolean = false): Boolean {
        if (state.value !== expected || !idleAndSaved(allowPendingApprovals)) return false
        retired = true
        ++turn
        return true
    }

    @Synchronized fun publish(owner: Long, conversation: Conversation) {
        if (!isCurrent(owner)) return
        require(conversation.id == baseline.id && conversation.assistantId == baseline.assistantId)
        state.value = conversation
        revision++
    }

    @Synchronized fun send(input: UIMessage?, answer: Boolean = true,
        generate: suspend (Conversation, Long, (Conversation) -> Unit) -> Unit,
    ): Job {
        check(!retired) { "续聊通道已交回历史管理，请重新发送。" }
        val owner = ++turn // Fence BEFORE cancellation: cancellation cleanup may publish late.
        workshopReservation = null
        sentinelReservation = null
        job.value?.cancel()
        val current = state.value
        val settled = if (input == null) current else current.copy(messageNodes = current.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.finishInterruptedHostTools() })
        })
        val next = if (input == null || settled.currentMessages.any { it.id == input.id }) settled else
            settled.copy(messageNodes = settled.messageNodes + input.toMessageNode())
        publish(owner, next)
        notice.value = "独立续聊中；尚未确认保存。请勿清除数据，重要内容可先复制。"
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (answer) generate(next, owner) { publish(owner, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(owner)) onFailure(failure)
            } finally {
                if (isCurrent(owner)) {
                    requestSave()
                }
            }
        }
        job.value = nextJob
        activeJobs.add(nextJob)
        nextJob.invokeOnCompletion {
            synchronized(this) {
                activeJobs.remove(nextJob)
                if (isCurrent(owner) && job.value === nextJob) job.value = null
                notifyIdleSaved()
            }
        }
        requestSave()
        nextJob.start()
        return nextJob
    }

    /** A button-local refusal changes neither the owner nor the queue; new sends remain independent. */
    @Synchronized fun regenerate(message: UIMessage,
        generate: suspend (Conversation, Long, (Conversation) -> Unit) -> Unit,
    ): String? = when (val plan = prepareHumanContinuationRegeneration(state.value, message)) {
        is HumanContinuationRegeneration.Blocked -> plan.notice
        is HumanContinuationRegeneration.Ready -> {
            send(null) { _, owner, publish ->
                generate(plan.request, owner) { generated ->
                    if (isCurrent(owner)) publish(plan.merge(generated))
                }
            }
            null
        }
    }

    @Synchronized fun stop() {
        if (retired) return
        ++turn
        workshopReservation = null
        sentinelReservation = null
        job.value?.cancel()
        job.value = null
        state.value = state.value.copy(messageNodes = state.value.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.finishInterruptedHostTools() })
        })
        revision++
        requestSave()
    }

    /** A stuck disk observer is not joined or cancelled by a human send. At most one writer. */
    @Synchronized fun requestSave() {
        if (retired) return
        if (persistenceJob?.isCompleted == false) return
        val snapshot = state.value
        val savingRevision = revision
        val writer = scope.launch(start = CoroutineStart.LAZY) {
            val saved = try { persist(snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            synchronized(this@HumanContinuationLane) {
                if (savingRevision == revision) {
                    savedRevision = if (saved) savingRevision else -1L
                    notice.value = if (saved)
                        "续聊已保存；旧轮仍单独保留，不会自动重跑旧工具。" else
                        "临时续聊：保存仍有问题，但可以继续发送。离开或重启前请复制重要内容。"
                    persistenceWarning.value = if (saved) null else notice.value
                }
            }
        }
        persistenceJob = writer
        writer.invokeOnCompletion {
            synchronized(this) {
                persistenceJob = null
                if (savingRevision != revision) requestSave()
                else notifyIdleSaved()
            }
        }
        writer.start()
    }
}
