package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiStreamDecoderTest {
    private val api = ResponseAPI(OkHttpClient())

    @Test
    fun `incomplete response should preserve terminal metadata and usage`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(SseEvent(
            event = "response.incomplete",
            data = json.encodeToString(buildJsonObject {
                put("type", "response.incomplete")
                put("response", buildJsonObject {
                    put("id", "resp_incomplete")
                    put("model", "test-model")
                    put("status", "incomplete")
                    put("incomplete_details", buildJsonObject {
                        put("reason", "max_output_tokens")
                    })
                    put("usage", buildJsonObject {
                        put("input_tokens", 10)
                        put("output_tokens", 20)
                        put("total_tokens", 30)
                        put("input_tokens_details", buildJsonObject {
                            put("cached_tokens", 4)
                        })
                    })
                })
            }),
        ))

        assertTrue(result.completed)
        assertEquals(
            TokenUsage(promptTokens = 10, completionTokens = 20, cachedTokens = 4, totalTokens = 30),
            (result.chunks[0] as StreamChunk.Usage).usage,
        )
        assertEquals(
            StreamChunk.Finish(
                finishReason = "incomplete:max_output_tokens",
                responseId = "resp_incomplete",
                model = "test-model",
            ),
            result.chunks[1],
        )
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `failed response should terminate with provider error`() {
        val decoder = ResponseApiStreamDecoder()

        val exception = assertThrows(HttpException::class.java) {
            decoder.accept(SseEvent(
                event = "response.failed",
                data = json.encodeToString(buildJsonObject {
                    put("type", "response.failed")
                    put("response", buildJsonObject {
                        put("id", "resp_failed")
                        put("status", "failed")
                        put("error", buildJsonObject {
                            put("code", "server_error")
                            put("message", "Upstream generation failed")
                        })
                    })
                }),
            ))
        }

        assertEquals("Upstream generation failed", exception.message)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `reasoning item without summary should preserve encrypted content`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is StreamChunk.ReasoningStart)
        assertTrue(chunks[1] is StreamChunk.ReasoningEnd)

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoning = messages.last().parts.single() as UIMessagePart.Reasoning

        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        assertFalse(messages.last().isValidToUpload())

        // ResponseAPI must still replay metadata-only reasoning even though generic providers consider it empty.
        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning item should keep final metadata after summary done event`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.done")
                put("item_id", "rs_test")
                put("summary_index", 0)
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(1, reasoningParts.size)
        assertEquals("summary", reasoningParts.single().reasoning)
        assertEquals(
            "encrypted",
            reasoningParts.single().metadataAs<OpenAIReasoningMetadata>()?.encryptedContent,
        )
        assertEquals(ReasoningType.SUMMARY_TEXT, reasoningParts.single().reasoningType)
    }

    @Test
    fun `raw reasoning and summary with the same index should remain distinct without replaying plaintext`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(reasoningItemEvent("response.output_item.added")))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_text.delta")
                put("item_id", "rs_test")
                put("content_index", 0)
                put("delta", "raw")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.reasoning_summary_text.delta")
                put("item_id", "rs_test")
                put("summary_index", 0)
                put("delta", "summary")
            }))
            addAll(decoder.decode(reasoningItemEvent("response.output_item.done", "encrypted")))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("hello"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val reasoningParts = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>()

        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)

        val reasoningItem = api.buildMessages(messages).last().jsonObject
        assertEquals(
            "summary",
            reasoningItem["summary"]?.jsonArray?.single()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content,
        )
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse(reasoningItem.containsKey("content"))
    }

    @Test
    fun `web search events should produce one completed server tool`() {
        val decoder = ResponseApiStreamDecoder()
        val chunks = buildList {
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.added")
                put("item", webSearchItem("in_progress"))
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.searching")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.web_search_call.completed")
                put("item_id", "ws_1")
            }))
            addAll(decoder.decode(buildJsonObject {
                put("type", "response.output_item.done")
                put("item", webSearchItem("completed"))
            }))
        }

        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        val messages = chunks.fold(listOf(UIMessage.user("search"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }
        val tool = messages.last().parts.single() as UIMessagePart.ServerTool

        assertEquals("ws_1", tool.toolCallId)
        assertEquals("web_search", tool.toolName)
        assertEquals("search", tool.input?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(
            "web_search_call",
            tool.metadataAs<ServerToolMetadata>()?.call?.get("type")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `annotation added should emit the supplied source without completing the response`() {
        val decoder = ResponseApiStreamDecoder()
        val result = decoder.accept(SseEvent(data = json.encodeToString(annotationEvent(citation()))))

        assertFalse(result.completed)
        assertEquals(listOf(StreamChunk.Annotations(listOf(expectedCitation()))), result.chunks)
    }

    @Test
    fun `repeated citations across added done and terminal snapshots should appear only once`() {
        val decoder = ResponseApiStreamDecoder()
        val part = outputTextPart(citation())
        val item = messageItem(part)
        val events = listOf(
            buildJsonObject {
                put("type", "response.content_part.added")
                put("item_id", "msg_1")
                put("content_index", 0)
                put("part", outputTextPart())
            },
            buildJsonObject {
                put("type", "response.output_text.delta")
                put("item_id", "msg_1")
                put("content_index", 0)
                put("delta", "Source")
            },
            annotationEvent(citation()),
            annotationEvent(citation()),
            buildJsonObject {
                put("type", "response.output_text.done")
                put("item_id", "msg_1")
                put("content_index", 0)
                put("text", "Source")
            },
            buildJsonObject {
                put("type", "response.content_part.done")
                put("item_id", "msg_1")
                put("content_index", 0)
                put("part", part)
            },
            buildJsonObject {
                put("type", "response.output_item.done")
                put("item", item)
            },
            terminalEvent(item),
        )
        val chunks = events.flatMap { decoder.decode(it) }

        assertEquals(1, chunks.filterIsInstance<StreamChunk.Annotations>().size)
        val message = assemble(chunks)
        assertEquals(listOf(expectedCitation()), message.annotations)
        assertEquals("Source", message.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Finish>().size)
        assertTrue(decoder.onClosed().isEmpty())
    }

    @Test
    fun `each official snapshot stage can supply citations without an annotation event`() {
        val part = outputTextPart(citation())
        val snapshots = listOf(
            buildJsonObject {
                put("type", "response.content_part.added")
                put("item_id", "msg_1")
                put("part", part)
            },
            buildJsonObject {
                put("type", "response.content_part.done")
                put("item_id", "msg_1")
                put("part", part)
            },
            buildJsonObject {
                put("type", "response.output_item.done")
                put("item", messageItem(part))
            },
            terminalEvent(messageItem(part)),
        )

        snapshots.forEach { snapshot ->
            val chunks = ResponseApiStreamDecoder().decode(snapshot)
            assertEquals(
                snapshot["type"].toString(),
                listOf(expectedCitation()),
                chunks.filterIsInstance<StreamChunk.Annotations>().flatMap { it.annotations },
            )
        }
    }

    @Test
    fun `terminal citations retain distinct sources across messages and content parts`() {
        val first = messageItem(outputTextPart(citation()), outputTextPart(citation("https://example.org/second", "Second")))
        val second = JsonObject(messageItem(outputTextPart(citation())) + ("id" to JsonPrimitive("msg_2")))
        val chunks = ResponseApiStreamDecoder().decode(terminalEvent(first, second))

        assertEquals(
            listOf(expectedCitation(), UIMessageAnnotation.UrlCitation("Second", "https://example.org/second")),
            chunks.filterIsInstance<StreamChunk.Annotations>().flatMap { it.annotations },
        )
    }

    @Test
    fun `unsafe malformed and non URL annotations should not become clickable sources`() {
        val invalidUrls = listOf(
            "javascript:alert(1)", "data:text/plain,source", "file:///tmp/source", "intent://source",
            "//example.org/source", "/source", "https://", "https://user:secret@example.org/source",
            "https://exa mple.org/source", "https://example.org/%broken",
        )
        val invalidAnnotations = invalidUrls.map { citation(it) } + listOf(
            JsonNull,
            JsonPrimitive("https://example.org/source"),
            JsonObject(citation() - "url"),
            JsonObject(citation() - "title"),
            JsonObject(citation() + ("url" to JsonPrimitive(123))),
            JsonObject(citation() + ("title" to JsonPrimitive(123))),
            JsonObject(citation() + ("type" to JsonPrimitive("file_citation"))),
            buildJsonObject {
                put("type", "file_path")
                put("file_id", "file_test")
            },
        )
        val decoder = ResponseApiStreamDecoder()

        invalidAnnotations.forEach { annotation ->
            assertTrue(decoder.decode(annotationEvent(annotation)).isEmpty())
        }
        val chunks = decoder.decode(terminalEvent(messageItem(outputTextPart(*invalidAnnotations.toTypedArray()))))
        assertTrue(chunks.none { it is StreamChunk.Annotations })
        assertEquals(1, chunks.filterIsInstance<StreamChunk.Finish>().size)
    }

    @Test
    fun `web search source lists do not manufacture citations`() {
        val item = JsonObject(webSearchItem("completed") + ("action" to buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
            put("sources", buildJsonArray {
                add(buildJsonObject {
                    put("type", "url")
                    put("url", "https://example.org/source")
                })
            })
        }))
        val chunks = ResponseApiStreamDecoder().decode(terminalEvent(item))

        assertTrue(chunks.none { it is StreamChunk.Annotations })
        assertEquals("web_search", assemble(chunks).parts.filterIsInstance<UIMessagePart.ServerTool>().single().toolName)
    }

    @Test
    fun `web search terminal status without a start keeps its tool name`() {
        listOf("completed", "failed", "cancelled", "incomplete").forEach { status ->
            val chunks = ResponseApiStreamDecoder().decode(buildJsonObject {
                put("type", "response.web_search_call.$status")
                put("item_id", "ws_1")
            })
            val tool = assemble(chunks).parts.single() as UIMessagePart.ServerTool

            assertEquals("web_search", tool.toolName)
            assertEquals("ws_1", tool.toolCallId)
            assertEquals(if (status == "completed") ServerToolStatus.COMPLETED else ServerToolStatus.FAILED, tool.status)
        }
    }

    @Test
    fun `web search done and final snapshot without a start produce one named tool`() {
        val decoder = ResponseApiStreamDecoder()
        val item = webSearchItem("completed")
        val chunks = decoder.decode(buildJsonObject {
            put("type", "response.output_item.done")
            put("item", item)
        }) + decoder.decode(terminalEvent(item))
        val tool = assemble(chunks).parts.single() as UIMessagePart.ServerTool

        assertEquals("web_search", tool.toolName)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals(null, tool.output)
    }

    @Test
    fun `response completion does not promote failed unfinished or unknown search statuses`() {
        val expected = mapOf(
            "completed" to ServerToolStatus.COMPLETED,
            "failed" to ServerToolStatus.FAILED,
            "incomplete" to ServerToolStatus.FAILED,
            "cancelled" to ServerToolStatus.FAILED,
            "in_progress" to ServerToolStatus.IN_PROGRESS,
            "unknown" to ServerToolStatus.IN_PROGRESS,
        )
        expected.forEach { (status, expectedStatus) ->
            val chunks = ResponseApiStreamDecoder().decode(terminalEvent(webSearchItem(status)))
            val tool = assemble(chunks).parts.single() as UIMessagePart.ServerTool

            assertEquals(status, expectedStatus, tool.status)
            assertEquals("web_search", tool.toolName)
        }
        val missingStatus = JsonObject(webSearchItem("completed") - "status")
        val chunks = ResponseApiStreamDecoder().decode(terminalEvent(missingStatus))
        assertEquals(ServerToolStatus.IN_PROGRESS, (assemble(chunks).parts.single() as UIMessagePart.ServerTool).status)
    }

    @Test
    fun `non streaming response should parse web search without synthetic output`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray { add(webSearchItem("completed")) })
        })

        val tool = result.message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals(null, tool.output)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should preserve metadata-only reasoning`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {})
                    put("content", buildJsonArray {})
                })
            })
        })

        val reasoning = result.message.parts.single() as UIMessagePart.Reasoning
        assertEquals("", reasoning.reasoning)
        assertEquals("rs_test", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
        assertEquals("encrypted", reasoning.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)

        val reasoningItem = api.buildMessages(listOf(result.message)).single().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_test", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `non streaming response should distinguish raw reasoning and summary`() {
        val result = api.parseResponseOutput(buildJsonObject {
            put("id", "resp_1")
            put("model", "test-model")
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("id", "rs_test")
                    put("encrypted_content", "encrypted")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "summary_text")
                            put("text", "summary")
                        })
                    })
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "reasoning_text")
                            put("text", "raw")
                        })
                    })
                })
            })
        })

        val reasoningParts = result.message.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasoningParts.size)
        assertEquals("raw", reasoningParts.single { it.reasoningType == ReasoningType.REASONING_TEXT }.reasoning)
        assertEquals("summary", reasoningParts.single { it.reasoningType == ReasoningType.SUMMARY_TEXT }.reasoning)
        reasoningParts.forEach {
            assertEquals("rs_test", it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
            assertEquals("encrypted", it.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent)
        }
    }

    private fun expectedCitation() = UIMessageAnnotation.UrlCitation("Source title", "https://example.org/source")

    private fun citation(url: String = "https://example.org/source", title: String = "Source title") = buildJsonObject {
        put("type", "url_citation")
        put("url", url)
        put("title", title)
        put("start_index", 0)
        put("end_index", 6)
    }

    private fun annotationEvent(annotation: JsonElement) = buildJsonObject {
        put("type", "response.output_text.annotation.added")
        put("item_id", "msg_1")
        put("output_index", 0)
        put("content_index", 0)
        put("annotation_index", 0)
        put("sequence_number", 1)
        put("annotation", annotation)
    }

    private fun outputTextPart(vararg annotations: JsonElement) = buildJsonObject {
        put("type", "output_text")
        put("text", "Source")
        put("annotations", buildJsonArray { annotations.forEach { add(it) } })
    }

    private fun messageItem(vararg parts: JsonObject) = buildJsonObject {
        put("type", "message")
        put("id", "msg_1")
        put("role", "assistant")
        put("status", "completed")
        put("content", buildJsonArray { parts.forEach { add(it) } })
    }

    private fun terminalEvent(vararg output: JsonObject) = buildJsonObject {
        put("type", "response.completed")
        put("response", buildJsonObject {
            put("id", "resp_1")
            put("status", "completed")
            put("output", buildJsonArray { output.forEach { add(it) } })
        })
    }

    private fun assemble(chunks: List<StreamChunk>): UIMessage {
        val handler = StreamChunkHandler(Model(modelId = "test-model"))
        return chunks.fold(listOf(UIMessage.user("test"))) { messages, chunk ->
            handler.handle(messages, chunk)
        }.last()
    }

    private fun webSearchItem(status: String) = buildJsonObject {
        put("type", "web_search_call")
        put("id", "ws_1")
        put("status", status)
        put("action", buildJsonObject {
            put("type", "search")
            put("query", "Kotlin")
        })
    }

    private fun reasoningItemEvent(type: String, encryptedContent: String? = null) = buildJsonObject {
        put("type", type)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", "rs_test")
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    private fun ResponseApiStreamDecoder.decode(payload: kotlinx.serialization.json.JsonObject): List<StreamChunk> =
        accept(SseEvent(
            event = payload["type"]?.jsonPrimitive?.content,
            data = json.encodeToString(payload),
        )).chunks
}
