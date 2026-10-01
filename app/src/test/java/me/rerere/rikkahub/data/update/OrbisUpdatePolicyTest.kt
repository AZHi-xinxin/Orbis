package me.rerere.rikkahub.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic metadata; no network, APK downloads, installed packages or user data. */
class OrbisUpdatePolicyTest {
    private val digest = "a".repeat(64)
    private fun asset(suffix: String = "arm64-v8a", sha: String? = "sha256:$digest") = OrbisReleaseAsset(
        "Orbis-2.6.1-$suffix.apk", 40_000_000,
        "$ORBIS_RELEASES_PAGE/download/v2.6.1/Orbis-2.6.1-$suffix.apk", sha)
    private fun release(assets: List<OrbisReleaseAsset> = listOf(asset())) = OrbisPublishedRelease(
        1, "v2.6.1", "Orbis 2.6.1", "Synthetic release notes", "$ORBIS_RELEASES_PAGE/tag/v2.6.1", assets = assets)
    private fun installed() = OrbisApkIdentity(ORBIS_RELEASE_PACKAGE, 231, setOf(digest))

    @Test fun automaticChecksHaveTwentyFourHourFloorIncludingFailedAttemptsAndClockBackwards() {
        assertTrue(orbisAutomaticCheckDue(5, 0))
        assertFalse(orbisAutomaticCheckDue(1_000, 1_000))
        assertFalse(orbisAutomaticCheckDue(1_000 + ORBIS_CHECK_INTERVAL_MS - 1, 1_000))
        assertTrue(orbisAutomaticCheckDue(1_000 + ORBIS_CHECK_INTERVAL_MS, 1_000))
        assertFalse(orbisAutomaticCheckDue(999, 1_000))
    }

    @Test fun automaticVersionNoticeUsesStrictStableVersionsNotLexicalOrDevelopmentVersions() {
        assertTrue(orbisReleaseIsNewer("v2.6.1", "2.6.0"))
        assertTrue(orbisReleaseIsNewer("v2.10.0", "2.9.9"))
        assertFalse(orbisReleaseIsNewer("v2.6.0", "2.6.0"))
        assertFalse(orbisReleaseIsNewer("v2.5.9", "2.6.0"))
        assertFalse(orbisReleaseIsNewer("v2.6.1-rc1", "2.6.0"))
        assertFalse(orbisReleaseIsNewer("v2.6.1", "2.6.0-orbis-dev.48"))
        assertFalse(orbisReleaseIsNewer("v999999999999999999999.0.0", "2.6.0"))
    }

    @Test fun publishedGithubResponseIsParsedWithoutCredentialsOrUnrelatedFields() {
        val json = """{"id":1,"tag_name":"v2.6.1","name":"Orbis","body":null,"html_url":"$ORBIS_RELEASES_PAGE/tag/v2.6.1","draft":false,"prerelease":false,"author":{"login":"unused"},"assets":[]}"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<OrbisPublishedRelease>(json)
        assertEquals("v2.6.1", validateOrbisRelease(parsed).tag)
        assertNull(parsed.body)
    }

    @Test fun draftsPrereleasesWrongRepositoriesAndPathTraversalAreRejected() {
        val examples = listOf(release().copy(draft = true), release().copy(prerelease = true),
            release().copy(pageUrl = "https://github.com/rikkahub/rikkahub/releases/tag/v2.6.1"),
            release().copy(tag = "../escape"), release().copy(assets = listOf(asset().copy(name = "../outside.apk"))),
            release().copy(assets = listOf(asset().copy(downloadUrl = "http://localhost/private.apk"))))
        examples.forEach { assertThrows(IllegalArgumentException::class.java) { validateOrbisRelease(it) } }
    }

    @Test fun duplicateAssetNamesAreRejectedInsteadOfPickingArbitrarily() {
        assertThrows(IllegalArgumentException::class.java) { validateOrbisRelease(release(listOf(asset(), asset()))) }
    }

    @Test fun deviceAbiPreferenceAndUniversalFallbackNeverOfferCurrent64bitBuildTo32bitOnlyDevice() {
        val arm = asset(); val x86 = asset("x86_64"); val universal = asset("universal")
        val all = release(listOf(arm, x86, universal))
        assertEquals(arm, orbisApkForDevice(all, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(x86, orbisApkForDevice(all, listOf("x86_64", "x86")))
        assertEquals(universal, orbisApkForDevice(release(listOf(universal)), listOf("arm64-v8a")))
        assertNull(orbisApkForDevice(all, listOf("armeabi-v7a", "armeabi")))
    }

    @Test fun invalidSizeDebugUnsignedAndAmbiguousArchitectureAssetsAreNotSelected() {
        assertNull(orbisApkForDevice(release(listOf(asset().copy(size = 0))), listOf("arm64-v8a")))
        assertNull(orbisApkForDevice(release(listOf(asset().copy(size = ORBIS_MAX_APK_BYTES + 1))), listOf("arm64-v8a")))
        assertNull(orbisApkForDevice(release(listOf(asset().copy(name = "Orbis-debug-arm64-v8a.apk"))), listOf("arm64-v8a")))
        assertNull(orbisApkForDevice(release(listOf(asset().copy(name = "Orbis-unsigned-arm64-v8a.apk"))), listOf("arm64-v8a")))
        assertNull(orbisApkForDevice(release(listOf(asset(), asset().copy(name = "Orbis-other-arm64-v8a.apk"))), listOf("arm64-v8a")))
    }

    @Test fun checksumAcceptsGithubDigestOrManifestAndRequiresConsistencyWhenBothExist() {
        val chosen = asset()
        val sums = "$digest  ${chosen.name}\n"
        assertEquals(digest, orbisExpectedSha(chosen, null))
        assertEquals(digest, orbisExpectedSha(chosen.copy(digest = null), sums))
        assertEquals(digest, orbisExpectedSha(chosen, sums))
        assertEquals(digest, orbisShaFromSums("${digest.uppercase()} *${chosen.name}\r\n", chosen.name))
        assertThrows(IllegalArgumentException::class.java) { orbisExpectedSha(chosen, "${"b".repeat(64)}  ${chosen.name}\n") }
    }

    @Test fun missingMalformedDuplicateAndWrongFilenameChecksumsNeverSkipValidation() {
        val chosen = asset(sha = null)
        assertThrows(IllegalStateException::class.java) { orbisExpectedSha(chosen, null) }
        assertThrows(IllegalArgumentException::class.java) { orbisShaFromDigest("md5:1234") }
        assertThrows(IllegalStateException::class.java) { orbisShaFromSums("malformed", chosen.name) }
        assertThrows(IllegalStateException::class.java) { orbisShaFromSums("$digest  other.apk", chosen.name) }
        assertThrows(IllegalArgumentException::class.java) {
            orbisShaFromSums("$digest  ${chosen.name}\n$digest  ${chosen.name}", chosen.name)
        }
    }

    @Test fun redirectPolicyAllowsOnlyHttpsGithubReleaseInfrastructure() {
        assertTrue(orbisTrustedDownloadRedirect("https://release-assets.githubusercontent.com/path?token=synthetic"))
        assertTrue(orbisTrustedDownloadRedirect("https://objects.githubusercontent.com/path"))
        assertTrue(orbisTrustedDownloadRedirect(asset().downloadUrl))
        listOf("http://release-assets.githubusercontent.com/path", "https://github.com/evil/repo/releases/download/a/b.apk",
            "https://release-assets.githubusercontent.com.evil.test/path", "https://user:pass@objects.githubusercontent.com/path",
            "https://localhost/private", "https://objects.githubusercontent.com:8443/path", "file:///tmp/update.apk")
            .forEach { assertFalse(it, orbisTrustedDownloadRedirect(it)) }
    }

    @Test fun onlySamePackageSignerAndHigherVersionCodeCanReplaceCurrentRelease() {
        requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232), 26)
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 231), 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 230), 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232, packageName = "org.other.app"), 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232, signerSha256 = setOf("b".repeat(64))), 26)
        }
    }

    @Test fun debugInstallIsNotAReleaseUpgradeAndMissingOrExtraSignersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed().copy(packageName = "org.orbis.agent.dev"), installed().copy(versionCode = 232), 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232, signerSha256 = emptySet()), 26)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232, signerSha256 = setOf(digest, "b".repeat(64))), 26)
        }
    }

    @Test fun compatibilityRecoveryMustAlsoHaveHigherVersionCodeAndSupportedMinimumSdk() {
        // A label saying rollback grants no privilege; exactly the same identity/version gate applies.
        requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 500), 26)
        assertThrows(IllegalArgumentException::class.java) {
            requireOrbisSafeUpgrade(installed(), installed().copy(versionCode = 232, minSdk = 35), 26)
        }
    }
}
