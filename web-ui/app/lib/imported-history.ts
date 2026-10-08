/** Imported historical content must not auto-load remote media or behave as live tool output. */
export function isImportedHistory(parts: readonly { metadata?: Record<string, unknown> | null }[]): boolean {
  return parts.some((part) => ["deepseek", "operit_json_v2", "kelivo_sqlite_v2", "polaris_export_v1", "claude_export_v1", "chatgpt_export_v1", "rikka_chat_v1"].includes(
    typeof part.metadata?.import_source === "string" ? part.metadata.import_source : "",
  ));
}
