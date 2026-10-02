package me.rerere.rikkahub.data.update

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Reads this test APK's public installed file only; never launches Orbis or touches its data. */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class OrbisArchiveSignerCompatibilityTest {
    @Test fun archiveFlagsReturnTheSameNonemptyCurrentSignersAsInstalledTestApk() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val pm = context.packageManager
        val sdk = Build.VERSION.SDK_INT
        val installedFlags = if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = pm.getPackageInfo(context.packageName, installedFlags)
        val archive = requireNotNull(pm.getPackageArchiveInfo(context.applicationInfo.publicSourceDir,
            orbisArchiveIdentityFlags(sdk))) { "Cannot parse this test APK" }
        assertEquals(context.packageName, archive.packageName)
        val installedSigners = if (sdk >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val archiveSigners = if (sdk >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        assertFalse("Installed test APK current signers are missing", installedSigners.isNullOrEmpty())
        assertFalse("Archive current signers are missing on API $sdk", archiveSigners.isNullOrEmpty())
        assertEquals(fingerprints(installedSigners!!), fingerprints(archiveSigners!!))
    }

    private fun fingerprints(signatures: Array<Signature>): Set<String> = signatures.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }.toSet()
}
