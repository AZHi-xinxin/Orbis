package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.consultation.*
import me.rerere.rikkahub.data.orbis.integration.OrbisConnectionStore
import kotlin.coroutines.coroutineContext

/** Local metadata only. Does not initialize a chat, reconcile a job, contact the relay or retry. */
@Composable
internal fun OrbisConsultationLocalStatus(
    sessionId: String,
    humanStore: OrbisConnectionStore,
    onDismiss: () -> Unit,
) {
    if (!consultationFeature.enabled) {
        OrbisConsultationUnavailableDialog(onDismiss)
        return
    }
    val context = LocalContext.current
    val runtime = remember { ConsultationRuntimeStore.open(context) }
    val scope = rememberCoroutineScope()
    val expected = remember(sessionId) { humanStore.state.value }
    var snapshot by remember(sessionId) { mutableStateOf<ConsultationLocalStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        snapshot = null
        error = null
        busy = true
        try {
            fun bindingUnchanged(): Boolean = humanStore.state.value.let {
                expected.available && it.available && it.revision == expected.revision && it.baseUrl == expected.baseUrl
            }
            check(bindingUnchanged())
            val human = checkNotNull(humanStore.readCredential())
            check(human.revision == expected.revision && human.baseUrl == expected.baseUrl)
            val value = runtime.localStatus(sessionId, human)
            coroutineContext.ensureActive()
            check(bindingUnchanged())
            snapshot = value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = "暂时无法安全读取本机状态，或绑定已变化。没有批准、重试或重新生成任何操作。"
        } finally {
            busy = false
        }
    }

    LaunchedEffect(sessionId, expected.revision) { refresh() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本机执行状态") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只读取此场在这台手机上的状态快照，不读取对方手机；不显示正文、思考、工具参数或密钥。打开和刷新都不会唤醒 AI、批准工具或重试。",
                    style = MaterialTheme.typography.bodySmall)
                Text("会话 ${sessionId.takeLast(8)}", style = MaterialTheme.typography.labelMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                snapshot?.let { value ->
                    Text(consultationLocalStatusDescription(value.state), style = MaterialTheme.typography.bodyMedium)
                    if (value.state !in setOf(ConsultationLocalState.UNAVAILABLE, ConsultationLocalState.NOT_FOUND)) {
                        Text("阶段：${if (value.phase == ConsultationLocalPhase.ARCHIVING) "己方归档" else "咨询发言"}")
                        Text("本机记录的提交尝试：${value.submitAttempts} 次（不是生成次数）")
                        if (value.toolInFlight) Text("有尚未核定结果的工具执行记录，请勿重复操作。")
                        value.failureCode?.let { Text(consultationLocalFailureDescription(it), style = MaterialTheme.typography.bodySmall) }
                        value.httpStatus?.let { Text("已记录的 HTTP 状态：$it", style = MaterialTheme.typography.bodySmall) }
                        if (value.leaseExpired) Text("本请求的领取时限已到；这不等于可以安全重试，也不会改变本机已存记录。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("这是读取时的本机记录，不是实时生成指示；中继是否已收到正文仍以中继回执为准。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("刷新本机状态") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

internal fun consultationLocalStatusDescription(state: ConsultationLocalState): String = when (state) {
    ConsultationLocalState.UNAVAILABLE -> "暂时没有可安全核对的本机记录，可能是绑定变化、旧格式或写入尚未稳定；不能据此判断没有执行。"
    ConsultationLocalState.NOT_FOUND -> "本机没有找到此场当前请求的执行记录；这不能说明对方手机没有执行。"
    ConsultationLocalState.PREPARED -> "本机已准备请求，尚未登记开始生成。"
    ConsultationLocalState.RUNNING -> "本机已登记执行意图，尚未记录终态；这不等于模型此刻仍在生成。"
    ConsultationLocalState.WAITING_APPROVAL -> "本机记录为等待工具确认；请在该场的“待确认的工具操作”中核对具体操作。"
    ConsultationLocalState.COMPLETE -> "本机已保存可提交的最终正文，尚未记录提交成功；不要因此重新生成。"
    ConsultationLocalState.SUBMISSION_EXHAUSTED -> "本机提交尝试已达上限，已有正文保留，未因此重新生成。需要核对提交回执，不能当作生成失败重跑。"
    ConsultationLocalState.SUBMITTED -> "本机已记录中继的接收确认。"
    ConsultationLocalState.UNKNOWN -> "本机执行结果未完整确认，记录仍保留；不会自动重新生成。"
    ConsultationLocalState.BUSY -> "本机记录为执行繁忙，尚需核对，不代表已经完成一轮回复。"
}

/** Never echo an arbitrary persisted failure string: old and future records are untrusted. */
internal fun consultationLocalFailureDescription(code: String): String = when (code) {
    "generation_not_completed_no_automatic_retry" -> "原因：生成流程未正常完成。"
    "unexecuted_tool_no_automatic_retry" -> "原因：存在尚未完成的工具调用，不能提交为最终正文。"
    "missing_terminal_assistant_no_automatic_retry" -> "原因：没有找到可提交的最终助手消息。"
    "tool_call_tail_no_automatic_retry" -> "原因：工具调用之后没有可核定的最终答复。"
    "terminal_response_unverified_no_automatic_retry" -> "原因：没有核对到本次模型最终回复的完成凭据，未转发中途内容或自动重试。"
    "empty_final_text_no_automatic_retry" -> "原因：最终可转发的文字正文为空；思考内容不作为正文。"
    "oversized_final_text_no_automatic_retry" -> "原因：最终文字超过 16 KiB；没有截断、转发或重新生成。"
    "submission_rejected_keep_checkpoint" -> "原因：中继未确认接收，原检查点保留。"
    "process_interrupted_evidence_recovered_no_automatic_retry" -> "原因：此前进程中断，已恢复保存的执行证据，结果仍需核对。"
    "generation_cancelled_no_automatic_retry" -> "原因：生成已取消，未自动重新生成。"
    "provider_request_failed_no_automatic_retry" -> "原因：模型服务请求失败，未自动重新生成。"
    "network_interrupted_no_automatic_retry" -> "原因：连接中断，执行结果需核对。"
    "generation_deadline_expired_no_automatic_retry" -> "原因：生成请求已超过时限，未自动重新生成。"
    "empty_or_oversized_final_no_automatic_retry" -> "原因：旧记录仅标明正文为空或过大，无法从该记录区分具体情况。"
    "binding_changed_no_automatic_retry" -> "原因：执行绑定已变化，未继续使用旧绑定生成。"
    "gateway_busy_no_generation_no_automatic_retry" -> "原因：网关在生成前返回繁忙，本次未开始生成。"
    "execution_incomplete_no_automatic_retry" -> "原因：执行未完整确认，旧记录没有保存更具体的原因。"
    "session_ended_archive_without_active_replay" -> "原因：本场已结束，转入归档处理，不重放咨询发言。"
    else -> "记录了失败或未完成状态，但没有可显示的具体安全原因；不能据此判断可重试。"
}
