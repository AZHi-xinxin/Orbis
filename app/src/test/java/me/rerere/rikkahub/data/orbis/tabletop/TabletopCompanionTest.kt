package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

class TabletopCompanionTest {
    @Test fun changingConversationDoesNotChangeGameCompanion() {
        val original=TabletopConversationBinding("assistant","first-chat","room","原聊天",true)
        val changed=original.copy(conversationId="second-chat")
        assertEquals(original.assistantId,changed.assistantId)
        assertEquals(original.room,changed.room)
        assertTrue(changed.automatic)
        assertTrue(changed.connected(100000))
    }
    @Test fun temporaryAbsenceKeepsCompanionForAnHour() {
        val binding=TabletopConversationBinding("assistant","chat","room","原聊天",awaySince=1000)
        assertTrue(binding.connected(1000+20*60*1000))
        assertTrue(binding.connected(1000+60*60*1000-1))
        assertFalse(binding.connected(1000+60*60*1000))
        assertTrue(binding.copy(awaySince=null).connected(99999999))
    }
    @Test fun ownSpeechDoesNotCreateAnAutomaticConversationLoop() {
        fun events(vararg records:String)=Json.parseToJsonElement("{\"items\":["+records.joinToString(",")+"]}").jsonObject
        assertFalse(tabletopHasNewCompanionEvent(events("{\"kind\":\"public_message\",\"actor\":\"me\"}"),"me"))
        assertFalse(tabletopHasNewCompanionEvent(events("{\"kind\":\"private_invite\",\"sender\":\"me\"}"),"me"))
        assertTrue(tabletopHasNewCompanionEvent(events("{\"kind\":\"public_message\",\"actor\":\"friend\"}"),"me"))
        assertTrue(tabletopHasNewCompanionEvent(events("{\"kind\":\"phase_open\"}"),"me"))
    }
    @Test fun enabledCompanionStaysConnectedWhileGamePageIsInBackground() {
        val binding=TabletopConversationBinding("assistant","chat","room","原聊天",automatic=true,awaySince=1000)
        assertTrue(binding.connected(1000+4*60*60*1000L))
        assertFalse(binding.copy(automatic=false).connected(1000+4*60*60*1000L))
    }
    @Test fun reminderContainsCurrentBookFactsWithoutInventedCharacter() {
        val text=tabletopReminder(Json.parseToJsonElement("""{"script":{"title":"合成本"},"my_role":"role-7","phase":{"name":"第二幕","kind":"reading"},"stage":"playing","phase_version":8}""").jsonObject,42)
        assertTrue(text.contains("合成本"));assertTrue(text.contains("cursor=42"));assertTrue(text.contains("第二幕"))
        assertFalse(text.contains("林宴"));assertFalse(text.contains("周野"));assertFalse(text.contains("无记忆"))
    }
    @Test fun nativeGameSourcesAreNotPublicHttpSources() {
        val source="tabletop.11111111-1111-4111-8111-111111111111.22222222-2222-4222-8222-222222222222"
        assertTrue(isTabletopEventSource(source));assertFalse(source in ORBIS_EVENT_SOURCES)
        assertFalse(isTabletopEventSource("tabletop.model-name"))
    }
}
