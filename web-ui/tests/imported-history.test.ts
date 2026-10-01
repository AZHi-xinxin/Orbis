import test from "node:test";
import assert from "node:assert/strict";
import { isImportedHistory } from "../app/lib/imported-history";

test("every inert chat importer uses the remote-media history guard", () => {
  for (const source of ["deepseek", "operit_json_v2", "kelivo_sqlite_v2"]) {
    assert.equal(isImportedHistory([{}, { metadata: { import_source: source } }]), true);
  }
  for (const parts of [[], [{}], [{ metadata: null }], [{ metadata: { import_source: "unknown" } }]]) {
    assert.equal(isImportedHistory(parts), false);
  }
});
