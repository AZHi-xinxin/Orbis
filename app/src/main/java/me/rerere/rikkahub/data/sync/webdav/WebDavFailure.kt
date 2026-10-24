package me.rerere.rikkahub.data.sync.webdav

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParserException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Stable explanations, without remote bodies, URLs, object names or credentials. */
internal class WebDavOperationFailure(val operation: String, val code: Int, val reason: String) :
    Exception("WebDAV · $operation：$reason" + if (code > 0) "（HTTP $code）" else "")

internal fun webDavFailure(operation: String, error: Throwable): WebDavOperationFailure {
    if (error is CancellationException) throw error
    if (error is WebDavOperationFailure) return error
    val chain = generateSequence(error) { it.cause?.takeIf { next -> next !== it } }.take(8).toList()
    val status = chain.filterIsInstance<WebDavException>().firstOrNull()?.statusCode
        ?: chain.filterIsInstance<ResponseException>().firstOrNull()?.response?.status?.value
        ?: error.message?.let { Regex("backup_(?:capability|replace)_http_(\\d{3})").matchEntire(it)?.groupValues?.get(1)?.toInt() }
        ?: 0
    val reason = when {
        status == 401 -> "服务器未接受账号或授权，请检查 WebDAV 用户名与应用密码"
        status == 403 -> "服务器拒绝此操作，请检查目录权限与服务端访问限制"
        status == 404 -> "地址或目录不存在，请检查 WebDAV 地址和备份路径"
        status == 405 || status == 501 -> "服务器不支持此 WebDAV 操作，请确认填写的是 WebDAV 接口"
        status == 409 -> "目录或文件状态发生冲突，请刷新后检查父目录"
        status == 413 -> "服务器拒绝了过大的备份，请检查网盘或反向代理的上传大小限制"
        status == 423 -> "远端文件被锁定，请稍后检查"
        status == 429 -> "服务器限制了请求频率，请稍后再试"
        status == 507 -> "远端存储空间不足"
        status >= 500 -> "远端服务暂时无法完成请求，请稍后检查服务状态"
        status >= 300 -> "服务器未完成此操作，请核对接口和访问设置"
        chain.any { it is UnknownHostException } -> "找不到服务器地址，请检查地址、DNS 和网络"
        chain.any { it is SSLException } -> "TLS 连接未通过，请检查服务器证书与设备时间"
        chain.any { it is SocketTimeoutException || it.javaClass.simpleName.contains("Timeout", ignoreCase = true) } ->
            if (operation == "上传备份" || operation == "提交覆盖备份") "等待超时，远端是否已写入尚未确认；请先刷新备份列表，不自动重传"
            else "等待超时，请检查网络、代理或服务响应"
        chain.any { it is ConnectException } -> "无法连接服务器，请检查端口、网络或代理"
        chain.any { it is XmlPullParserException } -> "服务器返回的目录格式无法识别，请确认 WebDAV 接口地址"
        error.message == "backup_atomic_replace_unsupported" -> "服务器未提供安全覆盖能力，未替换已有备份"
        error.message == "backup_replace_unconfirmed" -> "未收到覆盖确认，请先核对远端备份状态"
        error is IllegalArgumentException -> "请求参数或地址无效，请检查配置"
        else -> "操作未完成，请检查连接和服务状态；原始错误内容未显示"
    }
    return WebDavOperationFailure(operation, status, reason)
}

internal suspend inline fun <T> davResult(operation: String, action: () -> T): Result<T> =
    try { Result.success(action()) } catch (error: Exception) { Result.failure(webDavFailure(operation, error)) }
