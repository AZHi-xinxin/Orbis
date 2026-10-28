package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceRuntimeLeaseTest {
    @Test fun busyWorkspaceCannotBeReplacedWhileOtherWorkspaceIsIndependent() {
        val one = WorkspaceRuntimeLease.acquire("test-one")
        try {
            assertTrue(runCatching { WorkspaceRuntimeLease.acquire("test-one") }.isFailure)
            WorkspaceRuntimeLease.acquire("test-two").close()
        } finally { one.close() }
        val next = WorkspaceRuntimeLease.acquire("test-one")
        one.close() // stale lease must not release a later run
        assertTrue(runCatching { WorkspaceRuntimeLease.acquire("test-one") }.isFailure)
        next.close()
    }
}
