package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.*
import org.junit.Test

class WorkspaceApprovalPathTest {
    @Test fun safeRootsNeedExactBoundary() {
        assertFalse("/workspace/readme.md".isOutsideWritableRoots())
        assertFalse("/tmp/result".isOutsideWritableRoots())
        assertTrue("/workspace-other/file".isOutsideWritableRoots())
        assertTrue("/etc/config".isOutsideWritableRoots())
    }

    @Test fun traversalAndAmbiguousPathsRequireApprovalBeforeTheUsersGrant() {
        assertTrue("/workspace/../etc/config".isOutsideWritableRoots())
        assertTrue("/tmp/a/../../etc/config".isOutsideWritableRoots())
        assertTrue("workspace/a".isOutsideWritableRoots())
        assertTrue("/workspace/a\\b".isOutsideWritableRoots())
        assertTrue("/workspace/a\u0000b".isOutsideWritableRoots())
    }
}
