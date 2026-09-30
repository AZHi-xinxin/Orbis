import type { ImportStatus } from "../types/orbis-import";

const phases: Record<string, number> = { created: 0, uploading: 1, checking: 2, ready: 3, importing: 4, cancelling: 5, complete: 6, cancelled: 6, failed: 6, expired: 6 };
export function newerImportStatus(previous: ImportStatus | null, incoming: ImportStatus): ImportStatus {
  if (previous?.id === incoming.id && (phases[previous.state] ?? 6) > (phases[incoming.state] ?? -1)) return previous;
  return incoming;
}
export function mayConfirmImport(status: ImportStatus | null, confirmed: boolean, count: number, attemptedJob: string | null): boolean {
  return Boolean(status?.state === "ready" && status.reviewToken && confirmed && count > 0 && attemptedJob !== status.id);
}
