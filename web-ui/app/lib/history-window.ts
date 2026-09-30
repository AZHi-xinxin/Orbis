/** Only the visible window is mapped/rendered. The underlying conversation is never truncated. */
export const HISTORY_WINDOW_SIZE = 80;
export function historyWindow(total: number, pinnedEnd: number | null) {
  const count = Math.max(0, Math.floor(total));
  const end = pinnedEnd === null ? count : Math.min(count, Math.max(0, Math.floor(pinnedEnd)));
  return { start: Math.max(0, end - HISTORY_WINDOW_SIZE), end, newer: count - end };
}
export function olderHistoryEnd(start: number) { return Math.max(1, start); }
export function newerHistoryEnd(end: number, total: number): number | null {
  return end + HISTORY_WINDOW_SIZE >= total ? null : end + HISTORY_WINDOW_SIZE;
}
