package me.rerere.rikkahub.data.orbis.consultation

import me.rerere.rikkahub.BuildConfig

/** Compile-time boundary, never enabled by saved settings, a route or a relay response. */
internal fun consultationFeatureEnabled(debugBuild: Boolean, internalOptIn: Boolean): Boolean =
    debugBuild && internalOptIn

internal class ConsultationFeaturePolicy(val enabled: Boolean) {
    fun requireEnabled() {
        check(enabled) { "consultation_not_open" }
    }
}

internal val consultationFeature = ConsultationFeaturePolicy(
    consultationFeatureEnabled(BuildConfig.DEBUG, BuildConfig.ORBIS_CONSULTATION_ENABLED),
)
