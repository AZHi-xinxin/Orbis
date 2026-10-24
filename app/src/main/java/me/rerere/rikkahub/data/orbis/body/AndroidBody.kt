package me.rerere.rikkahub.data.orbis.body

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.UUID
import me.rerere.rikkahub.data.orbis.activities.AndroidActivityStorage

/** Foreground elapsed time only; no alarm, service, polling loop, network or app launch at startup. */
object OrbisBody {
    private val repositories = mutableMapOf<String, BodyRepository>()
    private val process = UUID.randomUUID().toString()
    private var installed = false
    private var activeStart: Long? = null
    private var accumulated = 0L
    private fun install() {
        if(installed) return
        installed = true
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { synchronized(this@OrbisBody) { activeStart = SystemClock.elapsedRealtime() } }
                override fun onStop(owner: LifecycleOwner) { synchronized(this@OrbisBody) {
                    activeStart?.let { accumulated += (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) }
                    activeStart = null
                } }
            })
        }
    }
    @Synchronized private fun clock(): BodyClock = BodyClock(process,
        accumulated + (activeStart?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0) } ?: 0), System.currentTimeMillis())
    @Synchronized fun open(context: Context, assistantId: String): BodyRepository {
        require(BodyRules.id(assistantId)) { "body_owner_invalid" }
        install()
        return repositories.getOrPut(context.filesDir.canonicalPath + "/" + assistantId) {
            BodyRepository(AndroidActivityStorage(context.applicationContext.filesDir, assistantId, "body"), ::clock)
        }
    }
}
