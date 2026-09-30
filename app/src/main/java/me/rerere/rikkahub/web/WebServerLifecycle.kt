package me.rerere.rikkahub.web

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DEFAULT_WEB_SERVER_PORT = 8081

fun showOrbisSeparatePortAction(port: Int, running: Boolean, loading: Boolean): Boolean =
    port == 8080 && !running && !loading

fun shouldFinishWebService(command: Long, latestCommand: Long, running: Boolean, loading: Boolean): Boolean =
    command > 0 && command == latestCommand && !running && !loading

/** A complete restart is one operation, not two independently launched jobs. */
internal class SerialWebOperations(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    fun submit(operation: suspend () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // Enqueue on the caller before IO scheduling can reorder START and STOP.
        mutex.withLock { withContext(dispatcher) { operation() } }
    }
}
