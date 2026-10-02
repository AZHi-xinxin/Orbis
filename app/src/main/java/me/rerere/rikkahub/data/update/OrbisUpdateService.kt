package me.rerere.rikkahub.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class OrbisPreparedUpdate internal constructor(val file: File, val sha256: String, val versionCode: Long)

/** Android 9/10 collect archive certificates only when the legacy bit is also set. */
@Suppress("DEPRECATION")
internal fun orbisArchiveIdentityFlags(deviceSdk: Int): Int =
    if (deviceSdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    else PackageManager.GET_SIGNATURES

/** Public GitHub metadata only. No model client, account token, chat data, background download or silent install. */
class OrbisUpdateService(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("orbis_release_updates_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    val canReplaceCurrentApp: Boolean get() = app.packageName == ORBIS_RELEASE_PACKAGE

    suspend fun check(force: Boolean = false): OrbisUpdateCheck = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && (!canReplaceCurrentApp || !orbisAutomaticCheckDue(now, preferences.getLong("last_attempt_ms", 0))))
                return@withLock OrbisUpdateCheck(null, false, false)
            // Throttle failed attempts as well; opening a chat never starts an unbounded retry loop.
            check(preferences.edit().putLong("last_attempt_ms", now).commit()) { "无法保存更新检查时间" }
            val body = metadata("$API/releases/latest", 1024 * 1024)
            val latest = validateOrbisRelease(json.decodeFromString<OrbisPublishedRelease>(body))
            OrbisUpdateCheck(latest, canReplaceCurrentApp && orbisReleaseIsNewer(latest.tag, BuildConfig.VERSION_NAME), true)
        }
    }

    suspend fun history(): List<OrbisPublishedRelease> = withContext(Dispatchers.IO) {
        json.decodeFromString<List<OrbisPublishedRelease>>(metadata("$API/releases?per_page=20", 4 * 1024 * 1024))
            .filter { !it.draft && !it.prerelease }.map(::validateOrbisRelease)
    }

    fun matchingAsset(release: OrbisPublishedRelease): OrbisReleaseAsset? =
        orbisApkForDevice(validateOrbisRelease(release), Build.SUPPORTED_ABIS.toList())

    suspend fun downloadAndVerify(release: OrbisPublishedRelease,
        progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): OrbisPreparedUpdate = withContext(Dispatchers.IO) {
        require(canReplaceCurrentApp) { "Dev 版不能用正式 APK 覆盖；请使用正式发布页并分别保留数据" }
        validateOrbisRelease(release)
        val asset = matchingAsset(release) ?: error("该版本没有适配本机的唯一安装包，请到发布页查看")
        val sumsAsset = release.assets.singleOrNull { it.name == "SHA256SUMS.txt" }
        val sums = sumsAsset?.let {
            require(it.size in 1..256 * 1024) { "校验清单超过大小限制" }
            val bytes = assetBytes(it, 256 * 1024)
            orbisShaFromDigest(it.digest)?.let { expected -> require(sha256(bytes) == expected) { "校验清单摘要不匹配" } }
            bytes.toString(Charsets.UTF_8)
        }
        val expected = orbisExpectedSha(asset, sums)
        val directory = File(app.cacheDir, "orbis-updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存" }
        require(directory.usableSpace >= asset.size + RESERVED_SPACE) { "手机空间不足，请先备份并腾出空间" }
        val file = File.createTempFile("orbis-", ".apk", directory)
        var keep = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastProgress = 0L
            assetResponse(asset.downloadUrl).use { response ->
                response.body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= asset.size && copied <= ORBIS_MAX_APK_BYTES) { "下载文件超出发布大小" }
                        require(directory.usableSpace >= RESERVED_SPACE + count) { "手机剩余空间不足，已停止下载" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (copied == asset.size || now - lastProgress >= 200) {
                            progress(copied, asset.size); lastProgress = now
                        }
                    }
                } }
            }
            require(copied == asset.size && hex(digest.digest()) == expected) { "APK 大小或 SHA-256 不匹配，已阻止安装" }
            val identity = verifyApkIdentity(file)
            keep = true
            OrbisPreparedUpdate(file, expected, identity.versionCode)
        } finally { if (!keep) file.delete() } // The exact new cache file only, never application/user data.
    }

    fun installationPermissionGranted(): Boolean = app.packageManager.canRequestPackageInstalls()
    fun permissionSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${app.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Recheck immediately before handing this exact private-cache file to the Android installer. */
    suspend fun installerIntent(prepared: OrbisPreparedUpdate): Intent = withContext(Dispatchers.IO) {
        require(canReplaceCurrentApp && installationPermissionGranted()) { "请先允许此应用安装更新，再点安装" }
        val root = File(app.cacheDir, "orbis-updates").canonicalFile
        require(prepared.file.isFile && prepared.file.canonicalFile.parentFile == root &&
            prepared.file.length() in 1..ORBIS_MAX_APK_BYTES) { "更新缓存不存在或路径不符，请重新下载" }
        require(fileSha256(prepared.file) == prepared.sha256) { "更新缓存已变化，请重新下载" }
        val identity = verifyApkIdentity(prepared.file)
        require(identity.versionCode == prepared.versionCode) { "更新版本已变化，请重新下载" }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", prepared.file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = ClipData.newRawUri("Orbis update APK", uri) }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkIdentity(file: File): OrbisApkIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val candidate = app.packageManager.getPackageArchiveInfo(file.absolutePath,
            orbisArchiveIdentityFlags(Build.VERSION.SDK_INT)) ?: error("无法解析签名安装包")
        val identity = packageIdentity(candidate)
        requireOrbisSafeUpgrade(packageIdentity(installed), identity, Build.VERSION.SDK_INT)
        return identity
    }

    @Suppress("DEPRECATION")
    private fun packageIdentity(info: PackageInfo): OrbisApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return OrbisApkIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info),
            signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet(), info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)
    }

    private suspend fun metadata(url: String, limit: Int): String {
        require(url == "$API/releases/latest" || url == "$API/releases?per_page=20")
        client.newCall(request(url)).await().use { response ->
            require(response.isSuccessful) { "GitHub 更新检查暂时不可用（HTTP ${response.code}），可稍后重试或打开发布页" }
            return readBounded(response, limit).toString(Charsets.UTF_8)
        }
    }

    private suspend fun assetBytes(asset: OrbisReleaseAsset, limit: Int): ByteArray = assetResponse(asset.downloadUrl).use {
        readBounded(it, limit).also { bytes -> require(bytes.size.toLong() == asset.size) { "发布文件大小不一致" } }
    }

    private suspend fun assetResponse(initial: String): Response {
        require(initial.startsWith("$ORBIS_RELEASES_PAGE/download/") && orbisTrustedDownloadRedirect(initial))
        var url = initial
        repeat(5) {
            val response = client.newCall(request(url)).await()
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                require(location != null && orbisTrustedDownloadRedirect(location)) { "下载被重定向到非可信来源，已停止" }
                url = location
            } else {
                if (!response.isSuccessful) {
                    val code = response.code; response.close()
                    error("GitHub 下载暂时不可用（HTTP $code）")
                }
                return response
            }
        }
        error("下载重定向次数异常，已停止")
    }

    private suspend fun readBounded(response: Response, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        response.body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "更新资料超过安全大小限制" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private suspend fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer); if (count < 0) break
                bytes += count
                require(bytes <= ORBIS_MAX_APK_BYTES) { "更新缓存超过安全大小限制" }
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun request(url: String) = Request.Builder().url(url).get()
        .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "Orbis/${BuildConfig.VERSION_NAME}").build()

    companion object {
        private const val API = "https://api.github.com/repos/AZHi-xinxin/Orbis"
        private const val RESERVED_SPACE = 64L * 1024 * 1024
        private val checkMutex = Mutex()
        // Intentionally not the model HTTP client: no provider headers, credentials or custom interceptors.
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
