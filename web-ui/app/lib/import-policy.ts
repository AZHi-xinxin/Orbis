import type { ImportStatus } from "../types/orbis-import";

const phases: Record<string, number> = { created: 0, uploading: 1, checking: 2, ready: 3, importing: 4, cancelling: 5, complete: 6, cancelled: 6, failed: 6, expired: 6 };
export function newerImportStatus(previous: ImportStatus | null, incoming: ImportStatus): ImportStatus {
  if (previous?.id === incoming.id && (phases[previous.state] ?? 6) > (phases[incoming.state] ?? -1)) return previous;
  return incoming;
}
export function mayConfirmImport(status: ImportStatus | null, confirmed: boolean, count: number, attemptedJob: string | null): boolean {
  return Boolean(status?.state === "ready" && status.reviewToken && confirmed && count > 0 && attemptedJob !== status.id);
}

/** Unknown server errors never become arbitrary HTML, filenames, credentials or parser excerpts. */
export function importErrorMessage(code: string): string {
  const messages: Record<string, string> = {
    insufficient_storage: "手机剩余存储空间不足，未满足导入安全预留。请先腾出空间再用原文件重试，不要删除尚未备份的聊天。",
    archive_too_large: "文件或展开后的数据超过安全处理上限；请保留原文件，按窗口或附件拆分后重试。没有截断原文。",
    conversation_too_large: "单个窗口的内容或结构超过内存保护上限；请按会话分段导出，原文没有截断。",
    message_too_large: "单条消息超过安全读取大小；该窗口未完成导入，原文没有截断。",
    archive_encoding_invalid: "文件文字不是完整的 UTF-8，请重新复制或从来源导出。",
    archive_checksum_failed: "压缩包完整性校验失败，可能复制不完整或已损坏；请重新复制或导出。",
    archive_unsafe_path: "压缩包含不安全或冲突路径，已拒绝读取；请保留原文件。",
    file_access_failed: "文件读写失败，请检查手机存储与文件访问权限后重新选择文件。",
    invalid_archive: "导出格式或聊天结构暂不兼容，请保留原文件以便适配。",
    upload_timeout: "上传或检查超时；请检查连接并重新选择文件。",
  };
  return `${messages[code] ?? "任务未全部完成。"} 已完成的窗口保留，可重新选择原文件继续，已有窗口会跳过。`;
}
