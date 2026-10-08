package me.rerere.rikkahub.data.datastore

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class StaleSettingsSnapshotException : IllegalStateException("settings_snapshot_stale")

/**
 * A failed read is not an empty, writable store. Keep its last successful snapshot for
 * presentation, but latch full-snapshot writes off for this SettingsStore instance.
 * A second collector of the cold source must not implicitly unlock the failed instance.
 */
internal class SettingsReadProtection {
    private val mutex = Mutex()
    private var lastSuccessful: Settings? = null
    private var unavailable = false

    fun protect(source: Flow<Settings>): Flow<Settings> = source
        .map { settings ->
            mutex.withLock {
                check(!settings.init) { "settings_source_not_ready" }
                check(settings.readRevision >= 0) { "settings_revision_invalid" }
                if (unavailable) {
                    readOnlySnapshot()
                } else {
                    // Emissions can wait behind several writes. Only the durable source version
                    // can tell an old queued emission apart from the latest persisted state.
                    if (lastSuccessful == null || settings.readRevision >= lastSuccessful!!.readRevision) {
                        lastSuccessful = settings
                    }
                    checkNotNull(lastSuccessful)
                }
            }
        }
        .catch { error ->
            if (error !is IOException) throw error
            emit(mutex.withLock {
                unavailable = true
                readOnlySnapshot()
            })
        }

    /** The transaction must transform its latest persisted value, never a cached UI/source value. */
    suspend fun update(
        transform: (Settings) -> Settings,
        transaction: suspend (transformLatest: (Settings) -> Settings) -> Settings,
        publish: (Settings) -> Unit,
    ): Boolean = mutex.withLock {
        // A raw.first() may have succeeded before the separate UI collector publishes it.
        // Use the trusted source snapshot, not a possibly still-dummy or stale UI value.
        val previous = lastSuccessful
        check(!unavailable && previous != null) {
            "settings_not_ready_or_read_failed"
        }
        val saved = try {
            transaction { latest ->
                check(!latest.init) { "settings_source_not_ready" }
                transform(latest).also { candidate ->
                    check(!candidate.init) { "settings_not_ready_or_read_failed" }
                }
            }.also {
                check(!it.init && it.readRevision > previous.readRevision) { "settings_revision_not_advanced" }
            }
        } catch (_: StaleSettingsSnapshotException) {
            // Older UI snapshots are an ordinary concurrent edit, not an application crash.
            // The persistence transaction has aborted without changing any fields or version.
            return@withLock false
        } catch (error: IOException) {
            unavailable = true
            publish(readOnlySnapshot())
            throw error
        }
        // Never publish a value as saved before DataStore has accepted it.
        lastSuccessful = saved
        publish(saved)
        true
    }

    private fun readOnlySnapshot(): Settings = lastSuccessful?.copy(init = true) ?: Settings.dummy()
}
