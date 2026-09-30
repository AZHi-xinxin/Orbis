package me.rerere.rikkahub.testutil

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.util.UUID
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository

/** Only a nonce-owned target-cache directory; never the application's real archive or database. */
class IsolatedVoiceCallArchive : Closeable {
    private val owner = InstrumentationRegistry.getInstrumentation().let { instrumentation ->
        check(instrumentation is IsolatedGenerationLoopRunner) { "Use the isolated test runner" }
        check(instrumentation.targetContext.packageName == "org.orbis.agent.dev")
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        instrumentation.targetContext
    }
    private val cacheRoot = owner.cacheDir.canonicalFile
    val directory = File(cacheRoot, "stage31-voice-call-${UUID.randomUUID()}").canonicalFile.also {
        check(it.parentFile == cacheRoot && !it.exists())
        check(it.mkdir()) { "Could not create the owned synthetic voice-call directory" }
    }
    val context: Context = object : ContextWrapper(owner) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getDatabasePath(name: String): File = error("Voice archive fixtures must not access a database")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            error("Voice archive fixtures must not access real settings")
        override fun getSystemService(name: String): Any? = error("Voice archive fixtures must not access device services")
    }

    fun repository() = OrbisVoiceCallRepository(context)

    fun bytes(): Map<String, List<Byte>> = directory.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes().toList() }

    override fun close() {
        check(directory.canonicalFile.parentFile == cacheRoot && directory.name.startsWith("stage31-voice-call-"))
        directory.walkTopDown().forEach { check(it.canonicalFile.toPath().startsWith(directory.toPath())) }
        if (directory.exists()) check(directory.deleteRecursively()) { "Could not remove only the owned synthetic voice-call directory" }
    }
}
