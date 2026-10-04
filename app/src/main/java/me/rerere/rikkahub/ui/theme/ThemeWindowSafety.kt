package me.rerere.rikkahub.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Collections
import java.util.IdentityHashMap

/** Window cosmetics are optional for non-Activity hosts; malformed wrapper chains must terminate. */
internal fun themeActivityOrNull(context: Context): Activity? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Context, Boolean>())
    var current: Context? = context
    repeat(64) {
        val candidate = current ?: return null
        if (!seen.add(candidate)) return null
        when (candidate) {
            is Activity -> return candidate
            is ContextWrapper -> current = candidate.baseContext
            else -> return null
        }
    }
    return null
}
