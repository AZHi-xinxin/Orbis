package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OrbisCloudHomeConfigTest {
    @Test fun defaultsAreOfflineAndContainNoDeploymentAddress() {
        assertEquals(OrbisCloudHomeConfig(), Json.decodeFromString<OrbisCloudHomeConfig>("{}"))
        assertFalse(OrbisCloudHomeConfig().enabled)
        assertEquals("", OrbisCloudHomeConfig().homeUrl)
    }

    @Test fun canonicalizesHttpsHostPortAndRootWithoutChangingPath() {
        assertEquals("https://example.com/", normalizeOrbisCloudHomeUrl(" HTTPS://EXAMPLE.COM:443 "))
        assertEquals("https://example.com/orbis/index.html", normalizeOrbisCloudHomeUrl("https://example.com/orbis/index.html"))
        assertEquals("https://8.8.8.8/", normalizeOrbisCloudHomeUrl("https://8.8.8.8"))
        assertEquals("https://example.com/a%2Eb", normalizeOrbisCloudHomeUrl("https://example.com/a%2Eb"))
    }

    @Test fun rejectsCredentialsQueriesFragmentsAndNonHttpsSchemes() {
        listOf("", "example.com", "/relative", "//example.com/", "http://example.com/",
            "https://user:pass@example.com/", "https://@example.com/", "https://example.com/?token=synthetic",
            "https://example.com/?", "https://example.com/#", "https://example.com/#route",
            "file:///secret", "javascript:alert(1)", "content://example.com/", "intent://example.com/",
            "https://example.com:8443/", "https://example.com:/").forEach {
            assertNull(it, normalizeOrbisCloudHomeUrl(it))
        }
    }

    @Test fun rejectsLocalPrivateAndAlternativeIpForms() {
        listOf("localhost", "a.localhost", "a.local", "a.internal", "a.lan", "a.home", "singlehost",
            "127.0.0.1", "127.1", "2130706433", "0x7f000001", "0x7f.0.0.1", "0177.0.0.1",
            "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "0.0.0.0",
            "100.64.0.1", "169.254.169.254", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "[::1]", "[::ffff:127.0.0.1]", "[2001:4860:4860::8888]",
            "appassets.androidplatform.net", "child.appassets.androidplatform.net").forEach {
            assertNull(it, normalizeOrbisCloudHomeUrl("https://$it/"))
        }
    }

    @Test fun rejectsAmbiguousHostAndPathParsing() {
        listOf("https://example.com./", "https://exa_mple.com/", "https://例子.com/",
            "https://%65xample.com/", "https://example.com\\@localhost/", "https://example.com/\n",
            "https://example.com/./index", "https://example.com/../index", "https://example.com/%2e/index",
            "https://example.com/.%2E/index", "https://example.com/%2e%2e/index",
            "https://example.com/%252e%252e/index", "https://example.com/a%2fb", "https://example.com/a%5cb",
            "https://example.com/a%00b", "https://example.com/a%0ab", "https://example.com//a").forEach {
            assertNull(it, normalizeOrbisCloudHomeUrl(it))
        }
    }

    @Test fun rejectsOversizeAddress() {
        assertNull(normalizeOrbisCloudHomeUrl("https://example.com/" + "a".repeat(2048)))
    }
}
