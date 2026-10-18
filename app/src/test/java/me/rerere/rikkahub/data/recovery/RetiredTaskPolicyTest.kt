package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class RetiredTaskPolicyTest {
    private val owner = "org.orbis.agent"
    private val router = "me.rerere.rikkahub.ui.activity.OrbisLauncherActivity"
    private val rescue = "me.rerere.rikkahub.ui.activity.EmergencyBackupActivity"
    private val chat = "me.rerere.rikkahub.RouteActivity"
    private fun retired(base: String?, top: String? = base, enabled: Boolean = false,
        basePackage: String? = owner, topPackage: String? = owner, id: Int = 2) =
        isRetiredOrbisTask(owner, 1, id, basePackage, base, topPackage, top, enabled)

    @Test fun removesOnlyOwnedRouterAndDisabledRescueCards() {
        assertTrue(retired(router))
        assertTrue(retired(router, enabled = true))
        assertTrue(retired(rescue))
        assertTrue(retired(rescue, top = null, topPackage = null))
        assertFalse(retired(rescue, enabled = true))
    }
    @Test fun neverRemovesChatForeignOrCurrentTasks() {
        assertFalse(retired(chat))
        assertFalse(retired(router, top = chat))
        assertFalse(retired(router, basePackage = "org.orbis.agent.dev"))
        assertFalse(retired(router, topPackage = "foreign"))
        assertFalse(retired(router, id = 1))
        assertFalse(retired(router, id = -1))
    }
    @Test fun unknownMetadataAndExternalSavePickerAreNotGuessed() {
        assertFalse(retired(null))
        assertFalse(retired(rescue, top = "DocumentsActivity", topPackage = "com.android.documentsui"))
        assertFalse(retired(router, top = null))
        assertFalse(retired(router, basePackage = null))
    }
}
