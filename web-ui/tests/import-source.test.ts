import test from "node:test";
import assert from "node:assert/strict";
import { IMPORT_SOURCES, IMPORT_SOURCE_INFO, defaultImportPathLabel, importEndpoint, importStorageKeys, readImportSource, validateImportFile } from "../app/lib/import-source";

test("import source selection is an allowlist with a compatible DeepSeek default", () => {
  assert.deepEqual(IMPORT_SOURCES, ["deepseek", "rikkahub", "codex", "operit", "kelivo"]);
  for (const value of [null, "unknown", "https://example.invalid", "../codex"]) assert.equal(readImportSource(value), "deepseek");
  for (const source of IMPORT_SOURCES) assert.equal(readImportSource(source), source);
});

test("all import endpoints bind their source and escape opaque job IDs", () => {
  for (const source of IMPORT_SOURCES) {
    assert.equal(importEndpoint(source), `imports/${source}`);
    assert.equal(importEndpoint(source, "job"), `imports/${source}/job`);
    for (const action of ["archive", "conversations", "branches", "commit"] as const) {
      assert.equal(importEndpoint(source, "job", action), `imports/${source}/job/${action}`);
    }
    assert.equal(importEndpoint(source, "../other?x=1"), `imports/${source}/..%2Fother%3Fx%3D1`);
  }
});

test("pending job and once-only confirmation receipts are isolated by source", () => {
  assert.deepEqual(importStorageKeys("deepseek"), { job: "orbis:web-deepseek-job", attempted: "orbis:web-deepseek-confirmed-job" });
  const keys = IMPORT_SOURCES.flatMap((source) => Object.values(importStorageKeys(source)));
  assert.equal(new Set(keys).size, IMPORT_SOURCES.length * 2);
});

test("archive sources use ZIP and Codex uses raw NDJSON with independent size limits", () => {
  const expected = { deepseek: [80, ".zip", "application/zip"], rikkahub: [512, ".zip", "application/zip"], codex: [64, ".jsonl", "application/x-ndjson"], operit: [64, ".json", "application/json"], kelivo: [512, ".zip", "application/zip"] } as const;
  for (const source of IMPORT_SOURCES) {
    const [limit, extension, contentType] = expected[source];
    const info = IMPORT_SOURCE_INFO[source];
    assert.equal(info.maxBytes, limit * 1024 * 1024);
    assert.equal(info.contentType, contentType);
    assert.equal(info.extension, extension);
    assert.ok(info.accept.includes(extension));
    assert.equal(validateImportFile(source, { name: `synthetic${extension.toUpperCase()}`, size: info.maxBytes }), null);
    for (const size of [0, -1, info.maxBytes + 1]) assert.ok(validateImportFile(source, { name: `synthetic${extension}`, size }));
    assert.ok(validateImportFile(source, { name: "wrong.md", size: 100 }));
  }
});

test("Codex history index and non-JSONL exports cannot be mistaken for full sessions", () => {
  for (const name of ["history.jsonl", "HISTORY.JSONL", "conversation.json", "conversation.md", "backup.zip"]) {
    assert.ok(validateImportFile("codex", { name, size: 100 }));
  }
  assert.equal(validateImportFile("codex", { name: "rollout-synthetic.jsonl", size: 100 }), null);
});

test("preview labels distinguish all-branch preservation from visible-text-only import", () => {
  assert.match(defaultImportPathLabel("all_branches_preserved"), /全部原消息分支/);
  assert.match(defaultImportPathLabel("codex_text_only"), /不含推理、工具或媒体/);
  assert.match(defaultImportPathLabel("source_current_node"), /导出时选中/);
  assert.match(defaultImportPathLabel("newest_leaf"), /最新分支/);
  assert.match(defaultImportPathLabel("operit_selected_variants_local_timezone"), /导出时选中/);
  assert.match(defaultImportPathLabel("kelivo_selected_variants"), /导出时选中/);
});
