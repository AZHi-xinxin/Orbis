package me.rerere.rikkahub.data.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.providers.openai.PrefixObservation
import org.junit.Assert.*
import org.junit.Test

class LocalPrefixDiagnosticsMergeTest {
    private fun observation(slot:String,seq:Int)=PrefixObservation(slot,seq,1,"gpt",true,true,true,1,20,null,null,2,40)
    @Test fun `late usage replaces only same slot and sequence and keeps parallel requests`() {
        val first=observation("a",1);val second=observation("a",2);val other=observation("b",1)
        val lines=listOf(first,second,other).map { Json.encodeToString(it) }
        val used=first.copy(phase="usage",promptTokens=100,cachedTokens=90)
        val merged=mergePrefixDiagnosticLines(lines,used).map { Json.decodeFromString<PrefixObservation>(it) }
        assertEquals(3,merged.size);assertTrue(merged.contains(second));assertTrue(merged.contains(other));assertTrue(merged.contains(used));assertFalse(merged.contains(first))
    }
    @Test fun `bounded journal tolerates old and malformed entries without leaking request content`() {
        val values=(1..130).map { Json.encodeToString(observation("a",it)) }
        assertEquals(120,mergePrefixDiagnosticLines(values,observation("a",131)).size)
        assertEquals(2,mergePrefixDiagnosticLines(listOf("legacy malformed"),observation("b",1)).size)
    }
}
