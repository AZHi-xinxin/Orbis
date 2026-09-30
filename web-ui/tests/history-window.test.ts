import test from "node:test";
import assert from "node:assert/strict";
import { HISTORY_WINDOW_SIZE, historyWindow, olderHistoryEnd, newerHistoryEnd } from "../app/lib/history-window";

test("large archives render only a tail window without changing source length", () => {
  const nodes = Array.from({ length: 23690 }, (_, index) => index);
  const range = historyWindow(nodes.length, null);
  assert.equal(range.end - range.start, HISTORY_WINDOW_SIZE);
  assert.equal(nodes.slice(range.start, range.end).at(-1), 23689);
  assert.equal(nodes.length, 23690);
});
test("backwards windows visit every message once without expanding the render bound", () => {
  let range = historyWindow(23690, null);
  const seen = new Set<number>();
  while (true) {
    assert.ok(range.end - range.start <= HISTORY_WINDOW_SIZE);
    for (let i = range.start; i < range.end; i++) { assert.ok(!seen.has(i)); seen.add(i); }
    if (range.start === 0) break;
    range = historyWindow(23690, olderHistoryEnd(range.start));
  }
  assert.equal(seen.size, 23690);
});
test("reading history stays pinned while new messages arrive", () => {
  assert.deepEqual(historyWindow(1000, 400), { start: 320, end: 400, newer: 600 });
  assert.deepEqual(historyWindow(1010, 400), { start: 320, end: 400, newer: 610 });
  assert.equal(newerHistoryEnd(400, 1010), 480);
  assert.equal(newerHistoryEnd(1000, 1010), null);
});
test("empty/short/deleted histories stay in range", () => {
  assert.deepEqual(historyWindow(0, null), { start: 0, end: 0, newer: 0 });
  assert.deepEqual(historyWindow(10, 400), { start: 0, end: 10, newer: 0 });
  assert.deepEqual(historyWindow(10, null), { start: 0, end: 10, newer: 0 });
});
