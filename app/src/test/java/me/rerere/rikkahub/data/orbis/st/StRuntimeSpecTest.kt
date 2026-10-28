package me.rerere.rikkahub.data.orbis.st

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StRuntimeSpecTest {
    private val spec = StRuntimeSpec("/workspace/ST", "/root/st/venv/bin/python", "/root/st/config.env")
    @Test fun invalidPathsAndConfigInsideSourceAreRejectedBeforeLaunch() {
        listOf("../x", "/proc/1/environ", "/workspace/../secret", "/workspace//ST", "/root/ST\nsh").forEach { path ->
            assertTrue(runCatching { spec.copy(source = path).validate() }.isFailure)
        }
        assertTrue(runCatching { spec.copy(config = "/workspace/ST/config.env").validate() }.isFailure)
    }
    @Test fun argvDoesNotInterpretMetacharactersAndKeepsChildCleanup() {
        val value = spec.copy(source = "/workspace/ST ';touch bad'")
        val args = stRuntimeCommand(File("/native"), File("/private"), File("/private/host.py"), value)
        assertEquals(value.source, args[args.indexOf("-w") + 1])
        assertEquals(listOf(value.source, value.config), args.takeLast(2))
        assertTrue(args.contains("--kill-on-exit")); assertFalse(args.contains("-c"))
        assertFalse(args.contains("nohup")); assertFalse(args.any { it.contains("api_key") })
    }
}
