package me.rerere.rikkahub.data.orbis.sentinel

import java.io.File
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbisSentinelAtomicReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only a truly absent store is new without opening a stream`() {
        val base = File(temporary.root, "rules.json")
        assertNull(readSentinelAtomicText(base) { error("must not open missing store") })
    }

    @Test fun `base file is read through the atomic reader`() {
        val base = temporary.newFile("rules.json").apply { writeText("committed") }
        var opens = 0
        assertEquals("committed", readSentinelAtomicText(base) { opens++; base.inputStream() })
        assertEquals(1, opens)
    }

    @Test fun `backup alone delegates recovery rather than inventing empty rules`() {
        val base = File(temporary.root, "rules.json")
        val backup = File(base.path + ".bak").apply { writeText("old committed") }
        var opens = 0
        assertEquals("old committed", readSentinelAtomicText(base) { opens++; backup.inputStream() })
        assertEquals(1, opens)
        assertEquals("old committed", backup.readText())
        assertFalse(base.exists()) // The helper itself does not rename or delete evidence.
    }

    @Test fun `backup and new sidecar never bypass the atomic reader`() {
        val base = File(temporary.root, "rules.json")
        val backup = File(base.path + ".bak").apply { writeText("committed") }
        val unfinished = File(base.path + ".new").apply { writeText("unfinished") }
        assertEquals("committed", readSentinelAtomicText(base) { backup.inputStream() })
        assertEquals("unfinished", unfinished.readText())
        base.writeText("base")
        assertEquals("committed", readSentinelAtomicText(base) { backup.inputStream() })
    }

    @Test fun `lone unfinished file is preserved and blocks a fresh store`() {
        val base = File(temporary.root, "rules.json")
        val unfinished = File(base.path + ".new").apply { writeText("partial") }
        var writes = 0
        val error = runCatching {
            OrbisSentinelRuleStore(
                read = { readSentinelAtomicText(base) { error("must not open uncommitted data") } },
                write = { writes++ },
            )
        }.exceptionOrNull()
        assertEquals("sentinel_store_incomplete_write", error?.message)
        assertEquals(0, writes)
        assertEquals("partial", unfinished.readText())
        assertFalse(base.exists())
    }

    @Test fun `read failure remains failure without deleting the backup`() {
        val base = File(temporary.root, "rules.json")
        val backup = File(base.path + ".bak").apply { writeText("preserve") }
        val failure = IOException("synthetic_read_failure")
        assertSame(failure, runCatching { readSentinelAtomicText(base) { throw failure } }.exceptionOrNull())
        assertEquals("preserve", backup.readText())
    }

    @Test fun `existing rule in a committed backup survives store initialization without writes`() {
        val base = File(temporary.root, "rules.json")
        val rule = OrbisSentinelRule(
            id = "rule-1", assistantId = "11111111-1111-4111-8111-111111111111",
            conversationId = "22222222-2222-4222-8222-222222222222",
            type = OrbisSentinelType.INTERVAL, intervalMs = 1000, prompt = "synthetic reminder",
            enabled = true, createdAtMs = 100, updatedAtMs = 100, cooldownMs = 1000,
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(OrbisSentinelState(rules = listOf(rule)))
        val backup = File(base.path + ".bak").apply { writeText(encoded) }
        var writes = 0
        val store = OrbisSentinelRuleStore(
            read = { readSentinelAtomicText(base) { backup.inputStream() } },
            write = { writes++ },
        )
        assertEquals(listOf(rule), store.list())
        assertEquals(0, writes)
        assertEquals(encoded, backup.readText())
    }
}
