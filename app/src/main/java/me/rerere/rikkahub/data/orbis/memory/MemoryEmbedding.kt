package me.rerere.rikkahub.data.orbis.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import java.security.MessageDigest

internal interface MemoryEmbedding : AutoCloseable {
    fun encode(text: String, query: Boolean): FloatArray
}

/** Bundled pinned model, CPU only, one inference thread. No HTTP client or provider credentials. */
internal class LocalMemoryEmbedding(context: Context) : MemoryEmbedding {
    private val environment = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setMemoryPatternOptimization(false)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
    }
    private val tokenizer = MemoryWordPiece(context.assets.open("orbis-memory/vocab.txt").use { input ->
        val bytes = input.readBytes()
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ==
            "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c")
        bytes.toString(Charsets.UTF_8).lineSequence().toList()
    })
    private val session: OrtSession

    init {
        try {
            val bytes = context.assets.open("orbis-memory/model.onnx").use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == MODEL_SHA)
            session = environment.createSession(bytes, options)
        } catch (failure: Throwable) { options.close(); throw failure }
    }

    override fun encode(text: String, query: Boolean): FloatArray {
        val ids = tokenizer.encode((if (query) "为这个句子生成表示以用于检索相关文章：" else "") + text)
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
            if ("token_type_ids" in session.inputNames)
                tensors["token_type_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape)
            session.run(tensors).use { result ->
                val output = result.get("last_hidden_state").orElseThrow() as OnnxTensor
                val buffer = output.floatBuffer
                val vector = FloatArray(512)
                buffer.get(vector) // CLS pooling, followed by L2 normalization.
                return normalizedVector(vector)
            }
        } finally { tensors.values.forEach(OnnxTensor::close) }
    }

    override fun close() { session.close(); options.close() }

    companion object {
        const val MODEL_SHA = "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc"
        const val CACHE_VERSION = "bge-zh-v1.5-int8-cls-180-30-v1"
    }
}
