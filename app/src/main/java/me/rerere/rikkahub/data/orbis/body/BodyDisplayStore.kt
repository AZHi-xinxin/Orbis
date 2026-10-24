package me.rerere.rikkahub.data.orbis.body

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage

/** This capability is held only by the human UI. It is not passed to any AI tool. */
class BodyDisplayStore(private val storage: ActivityStorage) {
    private val mutable = MutableStateFlow(storage.read()?.let {
        BodyRules.json.decodeFromString<BodyDisplayPreference>(it)
    } ?: BodyDisplayPreference())
    val state = mutable.asStateFlow()
    @Synchronized fun setPrivateVisibleFromHuman(visible: Boolean) {
        val next = BodyDisplayPreference(visible)
        if (next == mutable.value) return
        storage.write(BodyRules.json.encodeToString(next))
        mutable.value = next
    }
}
