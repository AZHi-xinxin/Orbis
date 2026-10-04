package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.StreamChunk

internal const val VOICE_ARCHIVE_FIRST_CONTENT_MS = 10_000L
internal const val VOICE_ARCHIVE_ATTEMPT_TIMEOUT_MS = 180_000L
internal const val VOICE_ARCHIVE_RESPONSE_CHARS = 512 * 1024

/** Each provider attempt, not the entire fallback chain, has a separate complete-response limit. */
internal suspend fun collectVoiceArchiveStream(
    firstContentTimeoutMs: Long?,
    attemptTimeoutMs: Long = VOICE_ARCHIVE_ATTEMPT_TIMEOUT_MS,
    stream: suspend () -> Flow<StreamChunk>,
): OrbisVoiceModelArchive {
    require(firstContentTimeoutMs == null || firstContentTimeoutMs > 0)
    require(attemptTimeoutMs > 0)
    return withTimeoutOrNull(attemptTimeoutMs) {
        coroutineScope {
            val firstContent = CompletableDeferred<Unit>()
            val generation = async {
                val texts = linkedMapOf<String, StringBuilder>()
                var probe = VoiceArchiveContentProbe()
                var characters = 0
                var finished = false
                var finishReason: String? = null
                fun reserve(count: Int) {
                    if (count > VOICE_ARCHIVE_RESPONSE_CHARS - characters) throw VoiceArchiveFailure("archive_response_too_large")
                    characters += count
                }
                fun combined() = texts.values.joinToString("\n")
                stream().collect { chunk ->
                    currentCoroutineContext().ensureActive()
                    when (chunk) {
                        is StreamChunk.TextStart -> {
                            if (finished) throw VoiceArchiveFailure("invalid_archive_response")
                            if (chunk.id !in texts) {
                                if (texts.size >= 32) throw VoiceArchiveFailure("archive_response_too_large")
                                if (texts.isNotEmpty()) { reserve(1); probe.append("\n") }
                                texts[chunk.id] = StringBuilder()
                            }
                        }
                        is StreamChunk.TextDelta -> {
                            if (finished) throw VoiceArchiveFailure("invalid_archive_response")
                            if (chunk.id !in texts) {
                                if (texts.size >= 32) throw VoiceArchiveFailure("archive_response_too_large")
                                if (texts.isNotEmpty()) { reserve(1); probe.append("\n") }
                                texts[chunk.id] = StringBuilder()
                            }
                            reserve(chunk.text.length)
                            texts.getValue(chunk.id).append(chunk.text)
                            if (texts.keys.last() == chunk.id) probe.append(chunk.text)
                            else { probe = VoiceArchiveContentProbe(); probe.append(combined()) }
                            if (probe.meaningful) firstContent.complete(Unit)
                        }
                        is StreamChunk.ReasoningDelta -> reserve(chunk.text.length) // Neither thinking nor heartbeat is summary progress.
                        is StreamChunk.TextEnd, is StreamChunk.ReasoningStart, is StreamChunk.ReasoningEnd,
                        is StreamChunk.Usage, is StreamChunk.Annotations -> Unit
                        is StreamChunk.Finish -> {
                            if (finished) throw VoiceArchiveFailure("invalid_archive_response")
                            finished = true
                            finishReason = chunk.finishReason
                        }
                        else -> throw VoiceArchiveFailure("invalid_archive_response") // No tools, images, audio or server actions.
                    }
                }
                // onClosed() may synthesize Finish(null). Only provider-confirmed success is
                // complete; a valid-looking JSON prefix is not evidence that generation finished.
                if (!finished || finishReason?.lowercase() !in setOf("stop", "end_turn", "stop_sequence", "completed"))
                    throw VoiceArchiveFailure("invalid_archive_response")
                val archive = OrbisVoiceCallProtocol.parseModelArchive(combined()) ?: throw VoiceArchiveFailure("invalid_archive_response")
                if (archive.summary.toByteArray(Charsets.UTF_8).size > 64 * 1024 ||
                    archive.transcript.toByteArray(Charsets.UTF_8).size > 512 * 1024)
                    throw VoiceArchiveFailure("archive_response_too_large")
                archive
            }
            if (firstContentTimeoutMs != null) {
                val responded = withTimeoutOrNull(firstContentTimeoutMs) {
                    select {
                        firstContent.onAwait { true }
                        generation.onAwait { true }
                    }
                } ?: false
                if (!responded) {
                    generation.cancel()
                    throw VoiceArchiveFailure("archive_first_reply_timeout")
                }
            }
            generation.await()
        }
    } ?: throw VoiceArchiveFailure("archive_timeout")
}

/** Incremental JSON prefix recognizer. Only decoded non-whitespace inside a top-level archive
 * value counts; reasoning, fences, keys, colons, empty strings and escaped spaces never do.
 * This is NOT the final validator; Finish plus parseModelArchive is still mandatory. */
internal class VoiceArchiveContentProbe {
    private enum class State { START, FENCE, KEY_START, KEY, COLON, VALUE_START, VALUE, AFTER_VALUE, END, INVALID }
    private var state = State.START
    private val key = StringBuilder()
    private val fence = StringBuilder()
    private var escaped = false
    private var unicodeDigits = 0
    private var unicodeValue = 0
    private var pendingHighSurrogate = false
    var meaningful = false
        private set

    fun append(text: String) {
        if (meaningful || state == State.INVALID) return
        for (character in text) {
            when (state) {
                State.START -> when {
                    character.isWhitespace() -> Unit
                    character == '{' -> state = State.KEY_START
                    character == '`' -> { fence.append(character); state = State.FENCE }
                    else -> state = State.INVALID
                }
                State.FENCE -> {
                    if (character == '\n') {
                        state = if (fence.toString().trimEnd().lowercase() in setOf("```", "```json")) State.START else State.INVALID
                    } else {
                        if (fence.length >= 16) state = State.INVALID else fence.append(character)
                    }
                }
                State.KEY_START -> when {
                    character.isWhitespace() -> Unit
                    character == '"' -> { key.clear(); state = State.KEY }
                    character == '}' -> state = State.END
                    else -> state = State.INVALID
                }
                State.KEY, State.VALUE -> stringCharacter(character)
                State.COLON -> when {
                    character.isWhitespace() -> Unit
                    character == ':' -> state = State.VALUE_START
                    else -> state = State.INVALID
                }
                State.VALUE_START -> when {
                    character.isWhitespace() -> Unit
                    character == '"' -> state = State.VALUE
                    else -> state = State.INVALID
                }
                State.AFTER_VALUE -> when {
                    character.isWhitespace() -> Unit
                    character == ',' -> state = State.KEY_START
                    character == '}' -> state = State.END
                    else -> state = State.INVALID
                }
                State.END, State.INVALID -> Unit
            }
            if (meaningful || state == State.INVALID) return
        }
    }

    private fun stringCharacter(character: Char) {
        if (unicodeDigits > 0) {
            val value = character.digitToIntOrNull(16) ?: run { state = State.INVALID; return }
            unicodeValue = unicodeValue * 16 + value
            if (--unicodeDigits == 0) decoded(unicodeValue.toChar())
        } else if (escaped) {
            escaped = false
            when (character) {
                'u' -> { unicodeDigits = 4; unicodeValue = 0 }
                '"', '\\', '/' -> decoded(character)
                'b' -> decoded('\b')
                'f' -> decoded('\u000c')
                'n' -> decoded('\n')
                'r' -> decoded('\r')
                't' -> decoded('\t')
                else -> state = State.INVALID
            }
        } else when {
            character == '\\' -> escaped = true
            character == '"' -> {
                state = if (state == State.KEY) {
                    if (key.toString() in setOf("summary", "transcript")) State.COLON else State.INVALID
                } else State.AFTER_VALUE
            }
            character.code < 32 -> state = State.INVALID
            else -> decoded(character)
        }
    }

    private fun decoded(character: Char) {
        if (state == State.KEY) {
            if (key.length >= 16) state = State.INVALID else key.append(character)
        } else {
            if (character.isHighSurrogate()) { pendingHighSurrogate = true; return }
            if (character.isLowSurrogate()) {
                if (pendingHighSurrogate) meaningful = true
                pendingHighSurrogate = false
                return
            }
            pendingHighSurrogate = false
            if (!character.isWhitespace() && !character.isISOControl()) meaningful = true
        }
    }
}
