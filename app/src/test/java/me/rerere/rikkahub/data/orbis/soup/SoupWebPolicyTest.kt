package me.rerere.rikkahub.data.orbis.soup

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SoupWebPolicyTest {
    private fun rejected(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    private fun session(id: String = "local-1", revealed: Boolean = false) = SoupSession(
        id, "soup_sample_001", SoupMode.NORMAL, 1000, revealed = revealed,
        submissions = listOf(SoupScore(SoupPlayer.HUMAN, "测试推理", 50, 60, 70, "PRIVATE_GRADING_COMMENT", 2000)),
        attempts = listOf(SoupAttempt("attempt1", SoupAction.ASK, SoupPlayer.HUMAN, "是单次吗？",
            "PRIVATE_MODEL_ID", "PRIVATE_MODEL_LABEL", "https://private-host.example", 1500, SoupAttemptState.UNKNOWN)),
    )

    @Test fun allowsOnlyFourExactOfflineAssets() {
        assertTrue(SoupWebPolicy.asset(SoupWebPolicy.PAGE))
        assertTrue(SoupWebPolicy.asset("${SoupWebPolicy.ORIGIN}/assets/orbis-soup/soup.js"))
        assertTrue(SoupWebPolicy.asset("${SoupWebPolicy.ORIGIN}/assets/orbis-soup/soup.css"))
        assertTrue(SoupWebPolicy.asset("${SoupWebPolicy.ORIGIN}/assets/orbis-garden/original.css"))
        listOf("http://appassets.androidplatform.net/assets/orbis-soup/index.html", "file:///assets/orbis-soup/index.html",
            "content://local/assets/orbis-soup/index.html", "${SoupWebPolicy.PAGE}?x=1", "${SoupWebPolicy.PAGE}#x",
            "${SoupWebPolicy.ORIGIN}:443/assets/orbis-soup/index.html", "https://x@appassets.androidplatform.net/assets/orbis-soup/index.html",
            "${SoupWebPolicy.ORIGIN}/assets/orbis-garden/garden.js", "${SoupWebPolicy.ORIGIN}/assets/orbis-soup/../orbis-garden/original.css",
            "${SoupWebPolicy.ORIGIN}/assets/orbis-soup/%69ndex.html", "https://appassets.androidplatform.net.evil/assets/orbis-soup/index.html")
            .forEach { assertFalse(it, SoupWebPolicy.asset(it)) }
    }

    @Test fun onlyExactPageCanSendBridgeMessages() {
        assertTrue(SoupWebPolicy.mainFrame(SoupWebPolicy.PAGE))
        assertFalse(SoupWebPolicy.mainFrame(null))
        assertFalse(SoupWebPolicy.mainFrame("${SoupWebPolicy.PAGE}#game"))
        assertFalse(SoupWebPolicy.mainFrame("${SoupWebPolicy.ORIGIN}/assets/orbis-garden/index.html"))
    }

    @Test fun parsesOnlyEnumeratedNativeActionsAndLocalStart() {
        assertEquals("host", SoupWebPolicy.command("""{"action":"host","params":{}}""").action)
        val start = SoupWebPolicy.command("""{"action":"start","params":{"puzzle":"soup_sample_001","mode":"NORMAL"}}""")
        assertEquals(SoupMode.NORMAL, start.mode); assertEquals("soup_sample_001", start.puzzle)
        assertEquals("local-1", SoupWebPolicy.command("""{"action":"archive","params":{"session":"local-1"}}""").session)
        for (action in listOf("execute", "eval", "fetch", "read_file", "inject", "selectHost", "private_solution"))
            rejected { SoupWebPolicy.command("""{"action":"$action","params":{}}""") }
    }

    @Test fun rejectsUnexpectedPayloadsTypesAndOversizeCommands() {
        listOf("""{"action":"host","params":{"key":"secret"}}""", """{"action":"host","params":{},"extra":1}""",
            """{"action":"host"}""", """{"action":12,"params":{}}""", """{"action":"host","params":[]}""",
            """{"action":"start","params":{"puzzle":"soup_sample_001","mode":"OTHER"}}""",
            """{"action":"start","params":{"puzzle":"private","mode":"NORMAL"}}""",
            """{"action":"start","params":{"puzzle":"soup_sample_001","mode":"NORMAL","endpoint":"evil"}}""",
            """{"action":"archive","params":{"session":"../../private"}}""",
            """{"action":"archive","params":{"session":1}}""",
            """{"action":"archive","params":{"session":"${"a".repeat(101)}"}}""",
            " " .repeat(1025)).forEach { rejected { SoupWebPolicy.command(it) } }
    }

    @Test fun activeSnapshotNeverExposesModelSelectionPrivateSolutionOrGrading() {
        val session = session(); val state = SoupState(hostModelId = "PRIVATE_HOST", activeId = session.id, sessions = listOf(session))
        val view = SoupWebPolicy.snapshot(state, false, false, null, true)
        val raw = view.toString()
        assertTrue(view.getValue("host_configured").jsonPrimitive.boolean)
        assertFalse(raw.contains("PRIVATE_")); assertFalse(raw.contains("private-host.example"))
        assertFalse(raw.contains(SoupCatalogue.get(session.puzzleId).solution))
        assertFalse(view.getValue("active").jsonObject.containsKey("soup_bottom"))
        assertTrue(raw.contains("UNKNOWN")); assertTrue(raw.contains("是单次吗？"))
    }

    @Test fun revealIsTheOnlyPathThatProjectsSolutionsAndComments() {
        val session = session(revealed = true); val state = SoupState(activeId = session.id, sessions = listOf(session))
        val visible = SoupWebPolicy.snapshot(state, false, false, null, false).getValue("active").jsonObject
        assertEquals(SoupCatalogue.get(session.puzzleId).solution, visible.getValue("soup_bottom").jsonPrimitive.content)
        assertTrue(visible.toString().contains("PRIVATE_GRADING_COMMENT"))
        assertFalse(visible.toString().contains("PRIVATE_MODEL_ID"))
    }

    @Test fun catalogAndHistoryAreMetadataOnlyEvenForRevealedGames() {
        val closed = session("old-1", revealed = true)
        val state = SoupState(sessions = listOf(closed))
        val snapshot = SoupWebPolicy.snapshot(state, false, false, null, false)
        assertEquals(JsonNull, snapshot.getValue("active")); assertEquals(JsonNull, snapshot.getValue("archive"))
        val raw = snapshot.toString()
        assertFalse(raw.contains(SoupCatalogue.get(closed.puzzleId).solution)); assertFalse(raw.contains("PRIVATE_GRADING_COMMENT"))
        assertFalse(snapshot.getValue("puzzles").toString().contains("soup_face"))
        assertEquals(closed.id, snapshot.getValue("history").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun onlySelectedArchiveGetsItsPublicProjection() {
        val old = session("old-1", revealed = true); val current = session("now-1")
        val state = SoupState(activeId = current.id, sessions = listOf(old, current))
        assertEquals(JsonNull, SoupWebPolicy.snapshot(state, false, false, null, false, "missing").getValue("archive"))
        assertEquals(JsonNull, SoupWebPolicy.snapshot(state, false, false, null, false, current.id).getValue("archive"))
        val archive = SoupWebPolicy.snapshot(state, false, false, null, false, old.id).getValue("archive").jsonObject
        assertEquals(old.id, archive.getValue("session_id").jsonPrimitive.content)
        assertTrue(archive.containsKey("soup_bottom")); assertFalse(archive.toString().contains("PRIVATE_MODEL_LABEL"))
    }
}
