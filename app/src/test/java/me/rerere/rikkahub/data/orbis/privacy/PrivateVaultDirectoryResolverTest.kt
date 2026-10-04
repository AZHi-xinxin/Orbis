package me.rerere.rikkahub.data.orbis.privacy

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PrivateVaultDirectoryResolverTest {
    @get:Rule val temporary = TemporaryFolder()
    private val unusedProtector = object : PrivateVaultKeyProtector {
        override fun wrap(vaultId: String, dataKey: ByteArray): ByteArray = error("unexpected_key_access")
        override fun unwrap(vaultId: String, wrappedKey: ByteArray): ByteArray = error("unexpected_key_access")
    }

    @Test fun constructorDoesNotRunFilesystemResolver() {
        var calls = 0
        val repo = PrivateVaultRepository(temporary.root, "synthetic", unusedProtector,
            baseDirectoryResolver = { calls++; temporary.root })
        assertEquals(0, calls)
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
        assertEquals(1, calls)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun resolverIoFailureHasFixedStatusAndWriteErrorWithoutCause() {
        val repo = PrivateVaultRepository(temporary.root, "synthetic", unusedProtector,
            baseDirectoryResolver = { throw IOException("SYNTHETIC-SENSITIVE-PATH") })
        assertEquals(PrivateVaultAvailability.UNREADABLE, repo.status().availability)
        try { repo.create(); fail("expected failure") } catch (error: PrivateVaultException) {
            assertEquals("storage_failed", error.reasonCode)
            assertNull(error.cause)
            assertFalse(error.toString().contains("SYNTHETIC"))
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun preciseUnsafeReasonIsRetainedWithoutWritingFallback() {
        val repo = PrivateVaultRepository(temporary.root, "synthetic", unusedProtector,
            baseDirectoryResolver = { throw PrivateVaultException("unsafe_path") })
        assertEquals(PrivateVaultAvailability.UNREADABLE, repo.status().availability)
        try { repo.create(); fail("expected failure") } catch (error: PrivateVaultException) {
            assertEquals("unsafe_path", error.reasonCode)
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun failedLazyResolutionCanBeExplicitlyCheckedAgain() {
        var unavailable = true
        val repo = PrivateVaultRepository(temporary.root, "synthetic", unusedProtector,
            baseDirectoryResolver = { if (unavailable) throw IOException(); temporary.root })
        assertEquals(PrivateVaultAvailability.UNREADABLE, repo.status().availability)
        unavailable = false
        assertEquals(PrivateVaultAvailability.ABSENT, repo.status().availability)
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
