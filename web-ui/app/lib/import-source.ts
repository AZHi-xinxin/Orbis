export const IMPORT_SOURCES = ["deepseek", "rikkahub", "codex", "operit", "kelivo"] as const;
export type ImportSource = (typeof IMPORT_SOURCES)[number];

export const IMPORT_SOURCE_INFO = {
  kelivo: {
    label: "Kelivo", extension: ".zip", accept: ".zip,application/zip", contentType: "application/zip", maxBytes: 8 * 1024 * 1024 * 1024,
    fileLabel: "选择电脑上的 Kelivo 安卓 ZIP", limit: "最多 8 GiB ZIP，仅支持 Kelivo v2 备份中的聊天；另有单窗与手机可用空间保护。",
    description: "先预览再追加导出时选中的回答。附件只保留引用说明，不导入配置、密钥、人格或技能。原备份可能包含密钥，请私密保管。",
    preview: "保留导出时选中的回答；其他版本和附件实体不迁移，历史工具仅为记录，不执行。",
    confirmation: "我确认接收身份与所选会话；仅追加聊天，不覆盖记录，不导入设置或密钥。",
  },
  operit: {
    label: "Operit", extension: ".json", accept: ".json,application/json", contentType: "application/json", maxBytes: 1024 * 1024 * 1024,
    fileLabel: "选择电脑上的 Operit v2 JSON", limit: "最多 1 GiB JSON，逐窗口读取；单窗与手机可用空间仍有限制。仅支持 operit_chat_archive / formatVersion 2。",
    description: "预览后选择会话，仅追加导出时选中的回答。不导入模型设置、人格、工具权限、工作区或附件实体。",
    preview: "保留当前选中的回答；内部摘要会跳过并提示，不当作聊天或系统提示。备用回答与附件实体不迁移，历史工具只作为记录，不执行。",
    confirmation: "我确认接收身份和所选会话；仅追加记录，不覆盖聊天或导入设置。",
  },
  deepseek: {
    label: "DeepSeek", extension: ".zip", accept: ".zip,application/zip", contentType: "application/zip", maxBytes: 8 * 1024 * 1024 * 1024,
    fileLabel: "选择电脑上的 DeepSeek ZIP", limit: "最多 8 GiB ZIP / 1 GiB 对话 JSON，逐窗口读取；单窗与手机可用空间仍有限制。",
    description: "选择 DeepSeek 官方导出的 ZIP，无需解压。每个窗口预览后选择一条路径，其他分支仍保留在原文件中。",
    preview: "每个窗口只导入所选路径；其他分支仍保留在原 ZIP，可另选路径再次导入。",
    confirmation: "我确认接收身份与所选路径；导入不覆盖已有聊天，不代表保留全部分支。",
  },
  rikkahub: {
    label: "RikkaHub", extension: ".zip", accept: ".zip,application/zip", contentType: "application/zip", maxBytes: 8 * 1024 * 1024 * 1024,
    fileLabel: "选择电脑上的 RikkaHub ZIP", limit: "最多 8 GiB ZIP，逐窗口读取；另有单窗与手机可用空间保护。只导入聊天，不覆盖设置或密钥。",
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
  if (reason.startsWith("operit_selected") || reason.startsWith("kelivo_selected")) return "导出时选中的回答";
  if (reason === "all_branches_preserved") return "全部原消息分支 · 完整保留";
  if (reason === "codex_text_only") return "可见文字 · 不含推理、工具或媒体";
  return reason === "source_current_node" ? "默认路径 · 导出时选中的位置" : "默认路径 · 最新分支";
}
