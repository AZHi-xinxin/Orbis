package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisMiniGameTemplatesTest {
    @Test fun templateIsAnExplicitSourceOnlyStartingPoint() {
        assertEquals("tic-tac-toe-responsive-v1", OrbisMiniGameTemplates.ID)
        assertTrue(OrbisMiniGameTemplates.html.startsWith("<!doctype html>"))
        assertTrue(OrbisMiniGameTemplates.html.length < 24_000)
        assertTrue(OrbisMiniGameTemplates.usage.contains("不自动安装"))
        assertTrue(OrbisMiniGameTemplates.html.contains("O 是本地简易程序，不是 AI 模型实时对战"))
    }

    @Test fun layoutMeasuresRealViewportAndRetainsScrollableTopAlignedContent() {
        val source = OrbisMiniGameTemplates.html
        assertTrue(source.contains("width=device-width, initial-scale=1"))
        assertTrue(source.contains("window.innerWidth"))
        assertTrue(source.contains("window.innerHeight"))
        assertTrue(source.contains("header.getBoundingClientRect().height - footer.getBoundingClientRect().height"))
        assertTrue(source.contains("window.addEventListener('resize', scheduleFit)"))
        assertTrue(source.contains("new ResizeObserver(scheduleFit)"))
        assertTrue(source.contains("overflow-y: auto"))
        assertFalse(source.contains("100vh"))
        assertFalse(source.contains("justify-content: center"))
        assertFalse(source.contains("overflow: hidden"))
    }

    @Test fun sourceDoesNotRequireNetworkExternalAssetsOrStorage() {
        val source = OrbisMiniGameTemplates.html
        listOf("http://", "https://", "<script src=", "<link ", "fetch(", "XMLHttpRequest",
            "WebSocket", "localStorage", "sessionStorage", "indexedDB", "document.cookie",
            "window.open", "location.reload", "location.href").forEach {
            assertFalse("Unexpected capability: $it", source.contains(it))
        }
    }

    @Test fun terminalStateUsesHumanPerspectiveAndAcknowledgesOnlySuccessfulHostReceipt() {
        val source = OrbisMiniGameTemplates.html
        assertTrue(source.contains("if (winner('X')) result = 'win'"))
        assertTrue(source.contains("if (winner('O')) result = 'loss'"))
        assertTrue(source.contains("if (moves === 9) result = 'draw'"))
        assertTrue(source.contains("Object.freeze({result: result, move_count: moves})"))
        assertTrue(source.contains("window.OrbisGame.finish(JSON.stringify(terminal))"))
        assertTrue(source.contains("receipt.ok !== true"))
        assertTrue(source.contains("if (!terminal || saved || submitting) return"))
        assertTrue(source.contains("retryButton.addEventListener('click', submitResult)"))
        assertTrue(source.contains("回游戏库重新打开"))
        assertFalse(source.contains("OrbisGame.start"))
        assertFalse(source.contains("board.fill("))
    }

    @Test fun templateCanBeInstalledWithoutStartingAnySession() {
        var text: String? = null
        val storage = object : OrbisGameStorage {
            override fun read() = text
            override fun write(value: String) { text = value }
        }
        var sequence = 0
        val repository = OrbisMiniGameRepository(storage, { 1000L }, { "template-fixture-${++sequence}" })
        assertTrue(repository.readSnapshot().games.isEmpty())
        val html = OrbisMiniGameTemplates.html
        assertTrue(repository.readSnapshot().games.isEmpty())
        val game = repository.install("synthetic-ai", "合成 AI", "井字棋模板", "本地程序对手", html)
        assertEquals(html, game.html)
        assertTrue(repository.readSnapshot().sessions.isEmpty())
    }
}
