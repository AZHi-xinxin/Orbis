package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisLocalPolicyTest {
    @Test fun acceptsOnlyLocalPageAndItsFragment() {
        assertTrue(OrbisLocalPolicy.isHome(OrbisLocalPolicy.HOME))
        assertTrue(OrbisLocalPolicy.isHome(OrbisLocalPolicy.HOME + "#reading"))
    }

    @Test fun rejectsForeignOrAmbiguousPageOrigins() {
        listOf(null, "", "file:///assets/orbis/index.html", "content://files/private",
            "http://appassets.androidplatform.net/assets/orbis/index.html",
            "https://appassets.androidplatform.net.evil.invalid/assets/orbis/index.html",
            "https://user@appassets.androidplatform.net/assets/orbis/index.html",
            "https://appassets.androidplatform.net:443/assets/orbis/index.html",
            OrbisLocalPolicy.HOME + "?redirect=external",
            "https://appassets.androidplatform.net/assets/orbis/../private",
            "https://appassets.androidplatform.net/assets/other/index.html")
            .forEach { assertFalse(OrbisLocalPolicy.isHome(it)) }
    }

    @Test fun chatNavigationRequiresCurrentPageMainFrameAndUserGesture() {
        assertTrue(OrbisLocalPolicy.mayOpenChat(OrbisLocalPolicy.OPEN_CHAT, OrbisLocalPolicy.HOME, true, true))
        assertFalse(OrbisLocalPolicy.mayOpenChat(OrbisLocalPolicy.OPEN_CHAT, null, true, true))
        assertFalse(OrbisLocalPolicy.mayOpenChat(OrbisLocalPolicy.OPEN_CHAT, OrbisLocalPolicy.HOME, false, true))
        assertFalse(OrbisLocalPolicy.mayOpenChat(OrbisLocalPolicy.OPEN_CHAT, OrbisLocalPolicy.HOME, true, false))
        assertFalse(OrbisLocalPolicy.mayOpenChat(OrbisLocalPolicy.OPEN_CHAT + "?tool=execute", OrbisLocalPolicy.HOME, true, true))
        assertFalse(OrbisLocalPolicy.mayOpenChat("intent://anything", OrbisLocalPolicy.HOME, true, true))
    }
}
