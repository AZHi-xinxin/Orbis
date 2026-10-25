package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekPrefixProbeTest {
    private fun body(system:String="private-system",last:String="hello",tools:String="one")=buildJsonObject {
        put("model","deepseek-v4-pro");put("temperature",1)
        put("tools",tools)
        putJsonArray("messages") {
            add(buildJsonObject{put("role","system");put("content",system)})
            add(buildJsonObject{put("role","user");put("content",last)})
        }
    }
    @Test fun distinguishesPrefixChurnFromNewTailWithoutPersistableContentOrHashes() {
        val probe=DeepSeekPrefixProbe()
        assertFalse(probe.observe("private-conversation-id",body()).compared)
        val next=probe.observe("private-conversation-id",body(last="next-secret"))
        assertTrue(next.toolsSame);assertTrue(next.optionsSame);assertEquals(1,next.commonMessages)
        assertEquals(1,next.firstChangedMessage);assertEquals("user",next.firstChangedRole)
        val report=Json.encodeToString(next)
        listOf("private-conversation-id","private-system","next-secret").forEach{assertFalse(report.contains(it))}
        val churn=probe.observe("private-conversation-id",body(system="different",tools="two"))
        assertFalse(churn.toolsSame);assertEquals(0,churn.firstChangedMessage)
        assertFalse(probe.observe("another-session",body()).compared)
    }
    @Test fun sameRequestAndLongMessagePartialPrefixAreMeasuredWithoutChangingInput() {
        val probe=DeepSeekPrefixProbe();val original=body(system="x".repeat(12000))
        val wire=original.toString();probe.observe("chat",original)
        val same=probe.observe("chat",original)
        assertEquals(2,same.commonMessages);assertNull(same.firstChangedMessage)
        assertEquals(wire,original.toString())
        val changed=probe.observe("chat",body(system="x".repeat(10000)+"y"))
        assertEquals(0,changed.firstChangedMessage);assertEquals(8192,changed.commonMessageBytes)
    }
}
