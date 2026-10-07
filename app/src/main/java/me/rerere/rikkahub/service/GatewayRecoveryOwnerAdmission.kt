package me.rerere.rikkahub.service

/** Ephemeral ownership only; never log the captured model or provider configuration. */
internal class GatewayRecoveryCapturedOwner<S : Any, A, M : Any, P : Any>(
    val session: S,
    val assistantId: A,
    val model: M,
    val provider: P?,
    val settled: Boolean,
) {
    override fun toString() = "GatewayRecoveryCapturedOwner(redacted)"
}

/**
 * Checks the actual captured owners, including session identity, before probing or committing
 * current-lane recovery. Settling an old request relaxes only its old model/provider comparison;
 * it does not let a different session/assistant or a changed current route acquire authority.
 * An empty collection passes this check, not the separate durable-scope and remote-IDLE checks.
 */
internal fun <S : Any, A, M : Any, P : Any> gatewayRecoveryOwnersMatch(
    currentSession: S,
    currentAssistantId: A,
    expectedModel: M?,
    expectedProvider: P?,
    currentModel: M?,
    currentProvider: P?,
    capturedOwners: List<GatewayRecoveryCapturedOwner<S, A, M, P>>,
): Boolean = currentModel == expectedModel && currentProvider == expectedProvider &&
    capturedOwners.all { owner ->
        owner.session === currentSession && owner.assistantId == currentAssistantId &&
            (owner.settled || owner.model == currentModel && owner.provider == currentProvider)
    }
