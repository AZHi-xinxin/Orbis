package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.serialization.Serializable

@Serializable internal data class PhoneElement(
    val id: Int, val path: List<Int>, val text: String, val description: String, val viewId: String,
    val bounds: String, val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
    val enabled: Boolean, val checked: Boolean, val selected: Boolean,
)
@Serializable internal data class PhonePage(val token: String, val packageName: String,
    val at: Long, val elements: List<PhoneElement>, val truncated: Boolean = false,
    val snapshotHash: String = "")

internal object PhoneControlPolicy {
    const val MAX_AGE_MS = 30_000L
    fun validate(page: PhonePage, token: String, current: PhonePage, allowed: Set<String>, now: Long) {
        check(token == page.token && page.packageName in allowed && current.packageName == page.packageName &&
            now >= page.at && now - page.at <= MAX_AGE_MS && current.elements == page.elements &&
            current.truncated == page.truncated && current.snapshotHash == page.snapshotHash) { "phone_page_changed_read_again" }
    }
    fun allowedPackage(pkg: String, host: String) = pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) &&
        pkg != host && pkg !in setOf("com.android.settings", "com.android.systemui", "com.android.permissioncontroller",
            "com.google.android.permissioncontroller", "com.miui.securitycenter", "com.google.android.gms") &&
        !pkg.contains("packageinstaller", true) && !pkg.contains("permissioncontroller", true)
}
