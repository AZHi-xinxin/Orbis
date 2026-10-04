package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.BuildConfig

/** Retire the old tool/prompt in Orbis, including imported settings that still enable it.
 * Keep the stored rows and serialized flags for non-destructive backup/compatibility only. */
internal fun legacyMemoryEnabled(configured: Boolean, orbisEnabled: Boolean = BuildConfig.ORBIS_ENABLED): Boolean =
    configured && !orbisEnabled
