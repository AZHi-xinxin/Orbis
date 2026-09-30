package me.rerere.rikkahub.data.orbis.group

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.Avatar
import kotlin.uuid.Uuid

const val ORBIS_GROUP_MEMBER_LIMIT = 8
const val ORBIS_GROUP_CONTEXT_MESSAGES = 200
const val ORBIS_GROUP_CONTEXT_BYTES = 128 * 1024
internal const val GROUP_INPUT_BYTES = 32 * 1024
internal const val GROUP_OUTPUT_BYTES = 64 * 1024
internal const val GROUP_PAGE_SIZE = 200
internal const val GROUP_ATTACHMENT_LIMIT = 4
internal const val GROUP_ATTACHMENT_BYTES = 8 * 1024 * 1024
internal const val GROUP_ATTACHMENT_TOTAL_BYTES = 20 * 1024 * 1024
internal const val GROUP_ATTACHMENT_TEXT_BYTES = 32 * 1024

@Serializable
data class OrbisGroupAttachment(
    val id: String, val name: String, val mime: String, val bytes: Long,
    val image: Boolean, val extractedText: String? = null,
)

@Serializable
data class OrbisGroupMember(
    val id: String, val assistantId: Uuid, val modelId: Uuid, val providerId: Uuid,
    val name: String, val avatar: Avatar = Avatar.Dummy, val joinedAt: Long,
)

@Serializable
data class OrbisGroupRoom(
    val id: String, val title: String, val members: List<OrbisGroupMember> = emptyList(),
    val createdAt: Long, val updatedAt: Long,
)

@Serializable
enum class OrbisGroupMessageStatus { QUEUED, GENERATING, COMPLETE, FAILED, INTERRUPTED }

@Serializable
data class OrbisGroupMessage(
    val id: String, val roomId: String, val roundId: String, val memberId: String?,
    val name: String, val modelId: Uuid? = null, val text: String = "",
    val status: OrbisGroupMessageStatus = OrbisGroupMessageStatus.COMPLETE,
    val errorReason: String? = null, val createdAt: Long, val updatedAt: Long,
    val sequence: Long = 0,
    val attachments: List<OrbisGroupAttachment> = emptyList(),
    val avatar: Avatar? = null,
    val errorDetails: OrbisGroupFailureDetails? = null,
)

@Serializable
internal enum class GroupRoundStatus { ACTIVE, FINISHED, INTERRUPTED }

@Serializable
internal data class GroupRound(
    val id: String, val roomId: String, val targets: List<String>, val createdAt: Long,
    val status: GroupRoundStatus = GroupRoundStatus.ACTIVE, val retryOfMessageId: String? = null,
)

data class OrbisGroupState(
    val loaded: Boolean = false, val error: String? = null,
    val rooms: List<OrbisGroupRoom> = emptyList(), val selectedRoomId: String? = null,
    val messages: List<OrbisGroupMessage> = emptyList(), val hasEarlier: Boolean = false,
    val hasLater: Boolean = false, val runningRoomIds: Set<String> = emptySet(),
    val runningMemberId: String? = null,
    val contextInfo: String = "仅本群最近 200 条、最多 128 KiB 文字参与回复；原历史保留。群聊不共享私聊记忆、工具和语音通话。",
)

class OrbisGroupException(val code: String) : IllegalStateException(when (code) {
    "not_ready" -> "群聊记录尚未安全读取，请重试。"
    "settings_loading" -> "模型设置尚未加载，请稍后重试。"
    "room_missing" -> "这个群聊不存在。"
    "room_limit" -> "群聊数量已达到本机上限。"
    "invalid_title" -> "群名称需要 1–80 个字符。"
    "busy" -> "本群正在回复，请先停止后再操作。"
    "other_room_busy" -> "另一个群正在回复，请等待完成或先停止那个群，再发送新一轮。"
    "member_limit" -> "一个群最多加入 8 位 AI。"
    "member_missing" -> "这位成员已不在群中。"
    "no_members" -> "请先添加至少一位 AI 成员。"
    "assistant_missing" -> "这位成员绑定的 AI 已不存在，请重新配置。"
    "model_missing" -> "这位成员的模型或连接已失效，请重新配置。"
    "ambiguous_model" -> "模型标识对应多个连接，已停止，避免发给错误的服务。"
    "provider_disabled" -> "这位成员的连接已关闭，没有替换成其他模型。"
    "invalid_input" -> "请输入文字，单次最多 32 KiB。"
    "invalid_attachment" -> "附件无法读取或不符合限制；未发送。每条最多 4 个附件，单个 8 MiB，合计 20 MiB。"
    "attachment_text_too_large" -> "附件文字超过 32 KiB，请分成较小文件；没有截断后发送。"
    "image_unsupported" -> "这位成员的模型没有开启图片输入，未发送本轮内容；请选择支持图片的模型后再试。"
    "context_too_large" -> "本群最新消息或成员设定超出安全上下文预算，未发送。"
    "response_too_large" -> "这位成员回复超过本群安全长度，已保留收到的部分并停止。"
    "empty_reply" -> "这位成员没有返回文字，可以单独重试。"
    "unexpected_tool" -> "这位成员返回了尚未获群聊授权的工具或附件，已停止。"
    "stale_retry" -> "这条失败记录之后已有新一轮对话，不能重放旧轮。请点名发一条新消息。"
    "not_retryable" -> "只有失败或中断的 AI 回复可以单独重试。"
    "storage_unavailable" -> "群聊记录未能安全保存，已停止生成；请勿连续重试。"
    "interrupted" -> "本次回复已中断，没有自动继续。"
    "request_rejected" -> "模型服务拒绝了请求参数或内容；请复制诊断核对，不会自动重试。"
    "authentication_failed" -> "模型服务未接受连接凭证，请核对该成员的连接设置。"
    "permission_denied" -> "模型服务拒绝访问，请核对该模型的权限或额度。"
    "model_or_endpoint_missing" -> "模型服务未找到该模型或接口地址，请核对成员配置。"
    "request_timeout" -> "这位成员的回复请求超时，已停止；收到的部分保留。"
    "request_too_large" -> "模型服务认为请求过长或过大，没有自动删改群记录。"
    "rate_limited" -> "模型服务触发请求限流或额度限制，请稍后手动重试。"
    "provider_unavailable" -> "模型服务暂时不可用；没有自动重试或切换模型。"
    "http_rejected" -> "模型服务返回未成功的 HTTP 状态，请复制诊断核对。"
    "network_dns" -> "无法解析该成员的服务地址，请检查网络和连接设置。"
    "network_connection" -> "无法连接该成员的模型服务，请检查网络和服务状态。"
    "network_tls" -> "该成员的安全连接未建立，请核对服务证书与网络。"
    "network_io" -> "该成员的网络连接中断；已收到的部分保留。"
    "invalid_response" -> "该成员的服务响应格式无法解析，请复制诊断核对。"
    else -> "这位成员暂时未能完成回复；其他聊天未受影响。"
})

internal fun groupCheck(condition: Boolean, code: String) {
    if (!condition) throw OrbisGroupException(code)
}

internal fun groupName(value: String) = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(80)
