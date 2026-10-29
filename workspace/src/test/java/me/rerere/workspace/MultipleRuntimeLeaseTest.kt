package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test

class MultipleRuntimeLeaseTest {
    @Test fun stAndOmbreCoexistButDestructiveOperationsWaitForBoth() {
        val st = WorkspaceRuntimeLease.acquireRuntime("multi-test", "st")
        val ob = WorkspaceRuntimeLease.acquireRuntime("multi-test", "ombre")
        try {
            assertThrows(IllegalStateException::class.java) { WorkspaceRuntimeLease.acquire("multi-test") }
            assertThrows(IllegalStateException::class.java) { WorkspaceRuntimeLease.acquireRuntime("multi-test", "st") }
            st.close()
            assertThrows(IllegalStateException::class.java) { WorkspaceRuntimeLease.acquire("multi-test") }
        } finally { st.close(); ob.close() }
        WorkspaceRuntimeLease.acquire("multi-test").close()
    }
    @Test fun installLeaseCannotBeBypassedByEitherRuntime() {
        WorkspaceRuntimeLease.acquire("install-test").use {
            for (service in listOf("st", "ombre")) assertThrows(IllegalStateException::class.java) {
                WorkspaceRuntimeLease.acquireRuntime("install-test", service)
            }
        }
    }
}
