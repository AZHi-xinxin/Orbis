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
    description = """原生整应用备份通道，可由当前助手从已绑定 Orbis 工作区配置人类指定的 WebDAV/S3/VPS，不依赖开发者代填。
action=bind：先用工作区文件工具在 /workspace/ 写 JSON（最大64KiB），WebDAV示例 {"target":"webdav","webdav":{"url":"https://server.example/dav","path":"orbis","username":"用户提供","password":"用户提供"}}；S3用 {"target":"s3","s3":{"endpoint":"https://s3.example","bucket":"bucket","region":"auto","accessKeyId":"用户提供","secretAccessKey":"用户提供","pathStyle":true}}。只使用人类指定/已授权的地址和凭据，不能拿阿序的服务器代替；普通SSH地址不是WebDAV/S3，若VPS尚未提供存储接口，应先经人类授权部署相应服务再绑定，不要假装成功。
写好文件后先action=inspect并传connection_file，Orbis原生层只返回校验值expected_sha256和脱敏目标，不回显文件正文、不写设置、不联网，无需shell或人类计算哈希。再bind参数 connection_file、expected_sha256(使用inspect回执)、auto_upload(默认true)、include_connection_secrets(默认false)。原生层读取文件把凭据直接保存到连接设置，不回显。该授权涉及整个应用（不只当前助手）的聊天/附件，可选择以后允许；不要在最终答复、报告或orbis_backup工具参数里复述密钥。工作区文件请私密保管，不自动删除人类资料。
action=check 后台检查连接；action=save_now 安排一次独立即时存档尝试，不等待旧自动重试的退避；action=status 读已绑定目标状态，不发网络请求。upload_attempt是本次上传阶段；check_or_restore_status/operation_status是独立的连接检查或恢复状态，不是save_now结果。completed_at是最近已验证副本的时间；pending_changes=true时仍有新内容未入该副本，completion_covers_current_changes=true才覆盖当前变化。旧目标/其他助手不能冒用授权。
action=restore_latest 是人类明确要求找回资料时才用的恢复命令：后台等当前回复/通话结束，下载并校验最新完整存档，暂存后提示重启，不自动重启或清数据。首次绑定发现服务器原有存档会保护原件，不能反复save强行覆盖。返回queued就继续正常回复，不等轮询、不重复提交。无额外安全码；重装需从应用外恢复连接文件重新绑定。""",
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
