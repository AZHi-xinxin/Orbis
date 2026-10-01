export interface ImportRowResult { conversation: number; state: string; reason?: string | null }
export interface ImportStatus {
  id: string; state: string; assistantId: string; expiresAt: number; uploadedBytes: number;
  maxArchiveBytes: number; conversationCount: number; reviewToken?: string | null;
  total: number; completed: number; imported: number; skipped: number; failed: number;
  messages: number; attachmentReferences: number; rows: ImportRowResult[]; error?: string | null;
  warnings?: string[]; skippedSummaries?: number;
}
export interface ImportConversation {
  index: number; title: string; totalNodes: number; messageCount: number;
  branchPointCount: number; branchCount: number; defaultBranch: number; defaultSelectionReason: string;
  omittedSummaryCount?: number;
}
export interface ImportBranch { index: number; messageCount: number; updatedAt: string; isDefault: boolean }
export interface ImportPage<T> { items: T[]; nextOffset?: number | null; hasMore: boolean }
