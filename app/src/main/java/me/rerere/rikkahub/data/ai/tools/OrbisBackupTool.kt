package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.sync.*

/** Files are resolved by the host-bound workspace, never an arbitrary id/path from model input. */
internal fun createOrbisBackupTool(
    workspaceId: String,
    stillBound: () -> Boolean,
    readFile: suspend (String) -> ByteArray,
    bind: suspend (BackupRecoveryConnection, Boolean, Boolean) -> Unit,
    status: suspend () -> JsonObject,
    request: suspend (String) -> Unit,
) = Tool(
    name = "orbis_backup",
    description = me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_backup"),
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("action", buildJsonObject { put("type", "string")
            put("enum", JsonArray(listOf("inspect", "bind", "check", "save_now", "status", "restore_latest").map(::JsonPrimitive))) })
        listOf("connection_file", "expected_sha256").forEach { key -> put(key, buildJsonObject { put("type", "string") }) }
        listOf("auto_upload", "include_connection_secrets").forEach { key -> put(key, buildJsonObject { put("type", "boolean") }) }
    }, required = listOf("action")) },
    needsApproval = { it.jsonObject["action"]?.jsonPrimitive?.content in setOf("bind", "restore_latest") },
    hostApproval = HostToolApproval("orbis:backup:$workspaceId", "workspace-backup-v2",
        "允许此助手从当前工作区管理整应用备份目标、上传与恢复（可记住授权）"),
    isApprovalCurrent = stillBound,
    execute = { args ->
        val result = try {
            check(stillBound())
            val fields = args.jsonObject
            require(fields.keys.all { it in setOf("action", "connection_file", "expected_sha256", "auto_upload", "include_connection_secrets") })
            fun string(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("required_field")
            fun bool(key: String, fallback: Boolean) = fields[key]?.let {
                require(it is JsonPrimitive && !it.isString); requireNotNull(it.booleanOrNull)
            } ?: fallback
            val action = string("action")
            require(action in setOf("inspect", "bind", "check", "save_now", "status", "restore_latest"))
            if (action == "inspect" || action == "bind") {
                val path = string("connection_file")
                require(path.startsWith("/workspace/") && path.length <= 4096)
                val bytes = readFile(path)
                try {
                    if(action == "inspect") {
                        val connection = BackupRecoveryConnection.decode(bytes)
                        buildJsonObject {
                            put("ok", true); put("status", "inspected_not_bound")
                            put("expected_sha256", AiBackupBinding.fingerprint(bytes))
                            put("destination", connection.displayDestination())
                            put("connection_verified", false)
                        }
                    } else {
                        val connection = AiBackupBinding.read(bytes, string("expected_sha256"))
                        check(stillBound())
                        bind(connection, bool("auto_upload", true), bool("include_connection_secrets", false))
                        buildJsonObject {
                            put("ok", true); put("status", "bound"); put("connection_verified", false)
                            put("note", "已写入原生备份设置；这不是上传成功回执。可用check检查，save_now交给后台；不要等候或重复绑定，继续回复即可。")
                        }
                    }
                } finally { bytes.fill(0) }
            } else if (action == "status") status()
            else {
                request(action)
                buildJsonObject { put("ok", true); put("status", "queued"); put("action", action)
                    put("note", "后台任务已安排，不占聊天工具队列。恢复会等本轮回复和通话结束；无需轮询，继续正常回复。") }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { buildJsonObject {
            put("ok", false); put("status", "not_confirmed")
            put("help", "请确认当前助手仍绑定此工作区、文件在/workspace/内且完整SHA256匹配、格式为WebDAV/S3连接。check/save/restore仅能操作这位助手已绑定且未在设置中改换的目标。没有回显密钥，没有自动重试，原聊天保留。")
        } }
        listOf(UIMessagePart.Text(result.toString()))
    },
)
