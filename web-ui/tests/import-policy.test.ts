import test from "node:test";
import assert from "node:assert/strict";
import { newerImportStatus, mayConfirmImport } from "../app/lib/import-policy";
import type { ImportStatus } from "../app/types/orbis-import";
const ready = { id: "synthetic", state: "ready", reviewToken: "synthetic-review" } as ImportStatus;
test("preview alone never imports: explicit confirmation and a selected path required", () => {
  assert.equal(mayConfirmImport(ready, false, 1, null), false);
  assert.equal(mayConfirmImport(ready, true, 0, null), false);
  assert.equal(mayConfirmImport(ready, true, 1, null), true);
});
test("unknown confirmation outcomes cannot be replayed, including after persisted recovery", () => {
  assert.equal(mayConfirmImport(ready, true, 1, ready.id), false);
  assert.equal(mayConfirmImport({ ...ready, state: "importing" }, true, 1, null), false);
  assert.equal(mayConfirmImport({ ...ready, reviewToken: null }, true, 1, null), false);
});
test("late upload/poll replies cannot rewind importing or a terminal receipt", () => {
  const running = { ...ready, state: "importing" };
  const complete = { ...ready, state: "complete" };
  assert.equal(newerImportStatus(running, ready), running);
  assert.equal(newerImportStatus(complete, running), complete);
  assert.equal(newerImportStatus(running, complete), complete);
});
test("a newly created job has an independent lifecycle", () => {
  const next = { ...ready, id: "new", state: "created" };
  assert.equal(newerImportStatus({ ...ready, state: "complete" }, next), next);
});
