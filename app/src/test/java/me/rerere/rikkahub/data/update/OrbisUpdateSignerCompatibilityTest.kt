package me.rerere.rikkahub.data.update

import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test

/** Synthetic policy/flag checks only; no APK, network, installed package or private data access. */
@Suppress("DEPRECATION")
class OrbisUpdateSignerCompatibilityTest {
    private val signer = "a".repeat(64)
    private val otherSigner = "b".repeat(64)
    private val installed = OrbisApkIdentity(ORBIS_RELEASE_PACKAGE, 234, setOf(signer))
    private val candidate = installed.copy(versionCode = 235)

    @Test fun android26And27ArchiveRequestsKeepLegacyCertificateFlag() {
        listOf(26, 27).forEach { sdk ->
            assertEquals("API $sdk", PackageManager.GET_SIGNATURES, orbisArchiveIdentityFlags(sdk))
        }
    }

    @Test fun android28AndLaterArchiveRequestsCollectCertificatesAndReturnSigningInfo() {
        listOf(28, 29, 30, 33, 36).forEach { sdk ->
            // GET_SIGNING_CERTIFICATES alone does not collect the archive certificates on
            // Android 9/10. Both bits are necessary; no extra package permissions are requested.
            assertEquals("API $sdk", PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES,
                orbisArchiveIdentityFlags(sdk))
            assertNotEquals("API $sdk", PackageManager.GET_SIGNING_CERTIFICATES, orbisArchiveIdentityFlags(sdk))
        }
    }

    @Test fun sameCurrentSignerAndHigherVersionAreAcceptedAcrossSupportedApis() {
        listOf(26, 27, 28, 29, 33, 36).forEach { sdk ->
            requireOrbisSafeUpgrade(installed, candidate, sdk)
        }
    }

    @Test fun missingInstalledSignerIsAReadFailureNotAFalseMismatchOrPermissionToInstall() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed.copy(signerSha256 = emptySet()), candidate, 29)
        }
        assertTrue(error.message.orEmpty().startsWith("无法读取当前应用签名"))
        assertFalse(error.message.orEmpty().contains("不一致"))
    }

    @Test fun missingCandidateSignerIsAReadFailureNotAFalseMismatchOrPermissionToInstall() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed, candidate.copy(signerSha256 = emptySet()), 29)
        }
        assertTrue(error.message.orEmpty().startsWith("无法读取安装包签名"))
        assertFalse(error.message.orEmpty().contains("不一致"))
    }

    @Test fun twoMissingSignersNeverPassByEmptySetEquality() {
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed.copy(signerSha256 = emptySet()),
                candidate.copy(signerSha256 = emptySet()), 29)
        }
    }

    @Test fun actualDifferentCurrentSignerRemainsAnExplicitMismatch() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed, candidate.copy(signerSha256 = setOf(otherSigner)), 29)
        }
        assertEquals("安装包签名与当前应用不一致，已阻止安装", error.message)
    }

    @Test fun sharingOneSignerDoesNotPermitAnExtraOrMissingSigner() {
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed, candidate.copy(signerSha256 = setOf(signer, otherSigner)), 29)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed.copy(signerSha256 = setOf(signer, otherSigner)), candidate, 29)
        }
        requireOrbisSafeUpgrade(installed.copy(signerSha256 = setOf(signer, otherSigner)),
            candidate.copy(signerSha256 = setOf(otherSigner, signer)), 29)
    }

    @Test fun fixedCertificateReadingDoesNotRelaxPackageVersionOrAndroidRequirements() {
        listOf(candidate.copy(packageName = "org.orbis.agent.dev"), candidate.copy(versionCode = 234),
            candidate.copy(versionCode = 233), candidate.copy(minSdk = 30)).forEach {
            assertThrows(IllegalArgumentException::class.java) { requireOrbisSafeUpgrade(installed, it, 29) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed.copy(packageName = "org.orbis.agent.dev"), candidate, 29)
        }
    }
}
