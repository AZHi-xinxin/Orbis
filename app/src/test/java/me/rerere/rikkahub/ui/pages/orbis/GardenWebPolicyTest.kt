package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GardenWebPolicyTest {
    private val data = buildJsonObject { put("version", 1); put("books", JsonArray(emptyList())) }

    @Test fun `exact bundled resources only`() {
        listOf("index.html", "original.css", "local.css", "garden.js", "library.js").forEach {
            assertTrue(GardenAssetPolicy.asset("${GardenAssetPolicy.ORIGIN}/assets/orbis-garden/$it"))
        }
        listOf("http://appassets.androidplatform.net/assets/orbis-garden/index.html",
            "${GardenAssetPolicy.HOME}?x=1", "${GardenAssetPolicy.HOME}#x", "${GardenAssetPolicy.HOME}/../library.js",
            "${GardenAssetPolicy.ORIGIN}/assets/orbis/index.html", "file:///data/data/settings.json",
            "https://example.com/garden.js", "https://appassets.androidplatform.net.evil.test/assets/orbis-garden/index.html"
        ).forEach { assertFalse(it, GardenAssetPolicy.asset(it)) }
    }
    @Test fun `only home is bridge main frame`() {
        assertTrue(GardenAssetPolicy.mainFrame(GardenAssetPolicy.HOME))
        assertFalse(GardenAssetPolicy.mainFrame(null))
        assertFalse(GardenAssetPolicy.mainFrame("${GardenAssetPolicy.ORIGIN}/assets/orbis-garden/library.js"))
    }
    @Test fun `library backup roundtrips only labelled library payload`() {
        assertEquals(data, GardenLibraryBackup.decode(GardenLibraryBackup.encode(data)))
        listOf("{}", "{\"format\":\"orbis-local-garden/1\",\"data\":{}}",
            "{\"format\":\"orbis-local-library/1\",\"data\":{\"version\":1,\"books\":[]},\"token\":\"test\"}",
            "{\"format\":\"orbis-local-library/1\",\"data\":{\"version\":2,\"books\":[]}}"
        ).forEach { assertTrue(runCatching { GardenLibraryBackup.decode(it.toByteArray()) }.isFailure) }
    }
    @Test fun `malformed unicode and oversize backup rejected`() {
        assertTrue(runCatching { GardenLibraryBackup.decode(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
        assertTrue(runCatching { GardenLibraryBackup.decode(ByteArray(GardenLibraryBackup.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { GardenLibraryBackup.encode(buildJsonObject { put("version",1); put("books", "not an array") }) }.isFailure)
    }
}
