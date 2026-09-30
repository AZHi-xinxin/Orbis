package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisCloudWebPolicyTest {
    private val policy = OrbisCloudWebPolicy.from("https://home.example.org/")!!

    @Test fun sameOriginRoutesAndQueriesRemainUsable() {
        assertTrue(policy.allowsNavigation("https://home.example.org/#reading"))
        assertTrue(policy.allowsNavigation("https://home.example.org/login?next=reading"))
        assertTrue(policy.allowsNavigation("https://HOME.example.org:443/calendar"))
    }

    @Test fun neverOpensAnotherMainFrameOriginOrNativeScheme() {
        listOf(null, "", "https://home.example.org.evil.invalid/", "https://other.example.org/",
            "http://home.example.org/", "https://home.example.org:444/", "file:///secret",
            "content://private", "intent://execute", "javascript:alert(1)",
            "https://user@home.example.org/", "https://home.example.org\\@evil.invalid/",
            OrbisLocalPolicy.HOME, OrbisLocalPolicy.OPEN_CHAT).forEach {
            assertFalse("Unexpected navigation: $it", policy.allowsNavigation(it))
        }
    }

    @Test fun backendResourcesNeedPublicHttpsButNoPrivateOrNativeAddress() {
        assertTrue(policy.allowsResource("https://project.supabase.co/rest/v1/letters?limit=0"))
        assertTrue(policy.allowsResource("https://api.example.org/api/reading/books"))
        listOf("http://api.example.org/", "https://localhost/", "https://127.0.0.1/",
            "https://192.168.1.2/", "https://[::1]/", "https://a.local/",
            "https://api.example.org:8000/", "https://user:pass@api.example.org/",
            OrbisLocalPolicy.HOME, "file:///android_asset/test.html").forEach {
            assertFalse("Unexpected resource: $it", policy.allowsResource(it))
        }
    }

    @Test fun nativeChatRequiresExactActionTrustedSourceAndDirectMainFrameGesture() {
        val action = "${OrbisLocalPolicy.OPEN_CHAT}?navigation=synthetic-instance"
        val source = "https://home.example.org/#calendar"
        assertTrue(policy.mayOpenChat(action, source, true, true, "GET", false, action))
        listOf(null, "", "https://foreign.example.org/", "https://home.example.org.evil.invalid/",
            "http://home.example.org/", "https://home.example.org:444/", OrbisLocalPolicy.HOME).forEach {
            assertFalse(policy.mayOpenChat(action, it, true, true, "GET", false, action))
        }
        listOf("$action?tool=run", "$action#extra", "$action/", "intent://open-chat",
            "https://home.example.org/orbis/open-chat", OrbisLocalPolicy.OPEN_CHAT,
            "${OrbisLocalPolicy.OPEN_CHAT}?navigation=another-instance").forEach {
            assertFalse(policy.mayOpenChat(it, source, true, true, "GET", false, action))
        }
        assertFalse(policy.mayOpenChat(action, source, false, true, "GET", false, action))
        assertFalse(policy.mayOpenChat(action, source, true, false, "GET", false, action))
        assertFalse(policy.mayOpenChat(action, source, true, true, "POST", false, action))
        assertFalse(policy.mayOpenChat(action, source, true, true, "GET", true, action))
    }

    @Test fun cloudMountRequiresEveryConsentAndVisibilityCondition() {
        assertTrue(shouldMountOrbisCloudHome(true, true, true, true, true, false))
        assertFalse(shouldMountOrbisCloudHome(false, true, true, true, true, false))
        assertFalse(shouldMountOrbisCloudHome(true, false, true, true, true, false))
        assertFalse(shouldMountOrbisCloudHome(true, true, false, true, true, false))
        assertFalse(shouldMountOrbisCloudHome(true, true, true, false, true, false))
        assertFalse(shouldMountOrbisCloudHome(true, true, true, true, false, false))
        assertFalse(shouldMountOrbisCloudHome(true, true, true, true, true, true))
    }
}
