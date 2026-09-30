export const IMPORT_SOURCES = ["deepseek", "rikkahub", "codex"] as const;
export type ImportSource = (typeof IMPORT_SOURCES)[number];

export const IMPORT_SOURCE_INFO = {
  deepseek: {
    label: "DeepSeek", extension: ".zip", accept: ".zip,application/zip", contentType: "application/zip", maxBytes: 80 * 1024 * 1024,
    fileLabel: "选择电脑上的 DeepSeek ZIP", limit: "最多 80 MiB ZIP / 64 MiB 对话 JSON。",
    description: "选择 DeepSeek 官方导出的 ZIP，无需解压。每个窗口预览后选择一条路径，其他分支仍保留在原文件中。",
    preview: "每个窗口只导入所选路径；其他分支仍保留在原 ZIP，可另选路径再次导入。",
    confirmation: "我确认接收身份与所选路径；导入不覆盖已有聊天，不代表保留全部分支。",
  },
  rikkahub: {
    label: "RikkaHub", extension: ".zip", accept: ".zip,application/zip", contentType: "application/zip", maxBytes: 512 * 1024 * 1024,
    fileLabel: "选择电脑上的 RikkaHub ZIP", limit: "最多 512 MiB ZIP。只导入聊天，不覆盖设置或密钥。",
    description: "选择 RikkaHub 导出的备份 ZIP。预览并勾选窗口，保留每个窗口的全部原消息分支；不导入身份设置、服务配置或密钥。",
    preview: "所选窗口的全部原消息分支都会保留，不需要另选路径；不导入备份中的设置或密钥。",
    confirmation: "我确认接收身份与所选窗口；保留这些窗口的全部原消息分支，不覆盖已有聊天，不导入设置或密钥。",
  },
  codex: {
    label: "Codex", extension: ".jsonl", accept: ".jsonl,application/x-ndjson", contentType: "application/x-ndjson", maxBytes: 64 * 1024 * 1024,
    fileLabel: "选择电脑上的 Codex JSONL", limit: "最多 64 MiB，须为完整且以换行结尾的原始 rollout .jsonl。",
    description: "只支持原始 rollout 会话 JSONL（session_meta / response_item / event_msg）。只导入人类与助手可见文字；跳过系统、开发者、推理、工具及媒体。不支持 exec --json、history.jsonl、Markdown 或 ChatGPT JSON。",
    preview: "只导入所选会话的人类与助手可见文字，不含推理、工具或媒体，不是完整工作区或全部活动备份。",
    confirmation: "我确认接收身份与所选会话，并知晓仅导入可见文字、不含推理、工具或媒体；不会覆盖已有聊天。",
  },
} as const;

export function readImportSource(value: string | null): ImportSource {
  return IMPORT_SOURCES.includes(value as ImportSource) ? value as ImportSource : "deepseek";
}

export function importEndpoint(source: ImportSource, jobId?: string, action?: "archive" | "conversations" | "branches" | "commit"): string {
  const base = `imports/${source}`;
  if (!jobId) return base;
  return `${base}/${encodeURIComponent(jobId)}${action ? `/${action}` : ""}`;
}

export function importStorageKeys(source: ImportSource) {
  // Retain the existing DeepSeek keys so pending confirmations cannot accidentally replay.
  return { job: `orbis:web-${source}-job`, attempted: `orbis:web-${source}-confirmed-job` };
}

export function validateImportFile(source: ImportSource, file: Pick<File, "name" | "size">): string | null {
  const info = IMPORT_SOURCE_INFO[source];
  if (!file.name.toLowerCase().endsWith(info.extension) || file.size <= 0 || file.size > info.maxBytes) {
    return `请选择 ${info.label} 的 ${info.extension} 文件，大小须大于 0 且不超过 ${info.maxBytes / 1024 / 1024} MiB。`;
  }
  if (source === "codex" && file.name.toLowerCase() === "history.jsonl") {
    return "history.jsonl 不是完整会话，请选择原始 rollout .jsonl。";
  }
  return null;
}

export function defaultImportPathLabel(reason: string): string {
  if (reason === "all_branches_preserved") return "全部原消息分支 · 完整保留";
  if (reason === "codex_text_only") return "可见文字 · 不含推理、工具或媒体";
  return reason === "source_current_node" ? "默认路径 · 导出时选中的位置" : "默认路径 · 最新分支";
}
