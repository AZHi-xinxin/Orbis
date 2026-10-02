package me.rerere.rikkahub.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

const val ORBIS_RELEASES_PAGE = "https://github.com/AZHi-xinxin/Orbis/releases"
internal const val ORBIS_RELEASE_PACKAGE = "org.orbis.agent"
internal const val ORBIS_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
internal const val ORBIS_MAX_APK_BYTES = 512L * 1024 * 1024

@Serializable
data class OrbisReleaseAsset(
    val name: String, val size: Long,
    @SerialName("browser_download_url") val downloadUrl: String,
    val digest: String? = null,
)

@Serializable
data class OrbisPublishedRelease(
    val id: Long,
    @SerialName("tag_name") val tag: String,
    val name: String? = null, val body: String? = null,
    @SerialName("html_url") val pageUrl: String,
    val draft: Boolean = false, val prerelease: Boolean = false,
    val assets: List<OrbisReleaseAsset> = emptyList(),
)

data class OrbisUpdateCheck(val latest: OrbisPublishedRelease?, val updateAvailable: Boolean, val checked: Boolean)

internal fun orbisAutomaticCheckDue(now: Long, lastAttempt: Long): Boolean =
    lastAttempt == 0L || (now >= lastAttempt && now - lastAttempt >= ORBIS_CHECK_INTERVAL_MS)

internal fun orbisReleaseIsNewer(tag: String, installed: String): Boolean {
    fun parse(value: String): List<Long>? = Regex("v?(\\d{1,8})\\.(\\d{1,8})\\.(\\d{1,8})")
        .matchEntire(value)?.groupValues?.drop(1)?.map(String::toLong)
    val candidate = parse(tag) ?: return false
    val current = parse(installed) ?: return false
    return candidate.zip(current).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

internal fun validateOrbisRelease(release: OrbisPublishedRelease): OrbisPublishedRelease {
    require(!release.draft && !release.prerelease && release.id > 0) { "只接受公开正式版本" }
    require(Regex("[A-Za-z0-9._-]{1,100}").matches(release.tag)) { "版本标识异常" }
    require(release.pageUrl == "$ORBIS_RELEASES_PAGE/tag/${release.tag}") { "更新说明不属于 Orbis 仓库" }
    require((release.body?.length ?: 0) <= 300_000 && release.assets.size <= 100) { "发布资料超过大小限制" }
    require(release.assets.map { it.name }.distinct().size == release.assets.size) { "发布文件名重复" }
    release.assets.forEach { asset ->
        require(Regex("[A-Za-z0-9._-]{1,180}").matches(asset.name)) { "发布文件名异常" }
        require(asset.size >= 0 && asset.downloadUrl == "$ORBIS_RELEASES_PAGE/download/${release.tag}/${asset.name}") {
            "下载地址不属于该 Orbis 版本"
        }
    }
    return release
}

internal fun orbisApkForDevice(release: OrbisPublishedRelease, supportedAbis: List<String>): OrbisReleaseAsset? {
    val supported = supportedAbis.filter { it == "arm64-v8a" || it == "x86_64" }.distinct()
    if (supported.isEmpty()) return null // The current universal build does not contain 32-bit native libraries.
    val candidates = release.assets.filter { it.name.startsWith("Orbis-") && it.name.endsWith(".apk") &&
        it.size in 1..ORBIS_MAX_APK_BYTES && !it.name.contains("debug", true) && !it.name.contains("unsigned", true) }
    for (abi in supported) {
        val matching = candidates.filter { it.name.endsWith("-$abi.apk") }
        if (matching.size == 1) return matching.single()
        if (matching.size > 1) return null
    }
    return candidates.singleOrNull { it.name.endsWith("-universal.apk") }
}

internal fun orbisShaFromDigest(digest: String?): String? {
    if (digest == null) return null
    require(Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) { "GitHub 文件摘要格式异常" }
    return digest.substringAfter(':').lowercase()
}

internal fun orbisShaFromSums(text: String, filename: String): String {
    val entries = linkedMapOf<String, String>()
    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val match = Regex("([0-9a-fA-F]{64})[ \\t]+\\*?([A-Za-z0-9._-]{1,180})").matchEntire(line.trim())
            ?: error("SHA256SUMS 格式异常")
        require(entries.put(match.groupValues[2], match.groupValues[1].lowercase()) == null) { "SHA256SUMS 文件名重复" }
    }
    return entries[filename] ?: error("校验清单没有该安装包，已停止下载")
}

internal fun orbisExpectedSha(asset: OrbisReleaseAsset, sums: String?): String {
    val api = orbisShaFromDigest(asset.digest)
    val manifest = sums?.let { orbisShaFromSums(it, asset.name) }
    require(api == null || manifest == null || api == manifest) { "发布摘要与校验清单不一致，已停止下载" }
    return api ?: manifest ?: error("发布文件没有可用 SHA-256，请到发布页核对；不会跳过校验安装")
}

internal fun orbisTrustedDownloadRedirect(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443) &&
        (uri.host == "release-assets.githubusercontent.com" || uri.host == "objects.githubusercontent.com" ||
            (uri.host == "github.com" && uri.path.startsWith("/AZHi-xinxin/Orbis/releases/download/")))
}.getOrDefault(false)

internal data class OrbisApkIdentity(val packageName: String, val versionCode: Long, val signerSha256: Set<String>,
    val minSdk: Int = 26)

internal fun requireOrbisSafeUpgrade(installed: OrbisApkIdentity, candidate: OrbisApkIdentity, deviceSdk: Int) {
    require(installed.packageName == ORBIS_RELEASE_PACKAGE && candidate.packageName == installed.packageName) {
        "安装包与当前应用不是同一正式包；Dev 与正式版数据独立，不能作为覆盖升级"
    }
    require(candidate.versionCode > installed.versionCode) {
        "Android 不允许把低版本代码当作普通覆盖升级；此包未满足更高版本代码，未安装、未卸载或清除数据"
    }
    require(installed.signerSha256.isNotEmpty()) {
        "无法读取当前应用签名，已阻止安装；请稍后重试或到正式发布页核对"
    }
    require(candidate.signerSha256.isNotEmpty()) {
        "无法读取安装包签名，已阻止安装；请重新下载或到正式发布页核对"
    }
    require(candidate.signerSha256 == installed.signerSha256) {
        "安装包签名与当前应用不一致，已阻止安装"
    }
    require(candidate.minSdk <= deviceSdk) { "安装包要求更高 Android 版本，已阻止安装" }
}
