package me.rerere.rikkahub.data.ai.checkpoint

/** Closed vocabulary: never log the checkpoint, exception message or private model response. */
internal fun checkpointDiagnostic(failure: Throwable): String =
    (failure as? GenerationCheckpointException)?.code?.takeIf { it in checkpointDiagnosticCodes }
        ?: "unclassified"

private val checkpointDiagnosticCodes = setOf(
    "baseline_branch_missing", "checkpoint_checksum_mismatch", "checkpoint_not_committed",
    "checkpoint_overlaps_protected_nodes", "checkpoint_storage_or_decode_failed",
    "checkpoint_tail_limit_or_missing", "checkpoint_too_large", "damage_not_isolated",
    "damage_outside_checkpoint", "database_branch_changed", "database_prefix_changed",
    "database_suffix_changed", "database_tail_changed", "duplicate_tail_nodes", "invalid_baseline",
    "invalid_checkpoint_header", "invalid_rebase", "invalid_tail_branch", "invalid_tool_identity",
    "no_complete_recovery_copy", "owner_or_epoch_changed", "stale_generation_handle",
    "tool_completion_mismatch", "tool_identity_changed", "tool_must_not_be_replayed",
    "tool_receipt_changed", "tool_receipt_limit_or_duplicate", "tool_receipt_target_missing_or_ambiguous",
    "tool_start_receipt_missing", "unknown_tool_cannot_rebase", "unknown_tool_requires_interrupted_receipt",
    "unknown_tool_requires_manual_review", "unresolved_previous_generation",
)
