import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const source = fs.readFileSync(new URL("../../main/assets/orbis-garden/library.js", import.meta.url), "utf8");
const window = { __ORBIS_LIBRARY_TEST__: true };
vm.runInNewContext(source, { window, TextEncoder, console }, { filename: "library.js" });
const { normalizeData, mergeLibraryData } = window.GardenLibrary.test;

function book(id, title = id) {
  return {
    id,
    title,
    text: "第一段。\n\n第二段。",
    chapters: [{ title: "全文", start: 0, end: 10 }],
    progress: { chapter: 0, offset: 0, updatedAt: 0 },
    font: { size: 18, lineHeight: 1.9 },
    bookmarks: [],
    notes: [{ id: `note_${id}`, chapter: 0, offset: 0, text: "旧笔记", createdAt: 1 }],
    importedAt: 1,
  };
}

const normalized = normalizeData({ version: 1, books: [book("one")] });
assert.equal(normalized.books[0].notes[0].author, "human");
assert.equal(normalized.books[0].notes[0].authorName, "我");

const merged = mergeLibraryData(
  { version: 1, books: [book("one")] },
  { version: 1, books: [book("two")] },
);
assert.equal(merged.added, 1);
assert.deepEqual(JSON.parse(JSON.stringify(merged.data.books.map((item) => item.id))), ["one", "two"]);

const identical = mergeLibraryData(
  { version: 1, books: [book("one")] },
  { version: 1, books: [book("one")] },
);
assert.equal(identical.added, 0);
assert.equal(identical.identical, 1);

const before = JSON.stringify({ version: 1, books: [book("one")] });
assert.throws(() => mergeLibraryData(
  JSON.parse(before),
  { version: 1, books: [book("one", "冲突书名")] },
), /不会被覆盖|没有导入/);
assert.equal(JSON.stringify({ version: 1, books: [book("one")] }), before);

console.log("orbis garden library merge tests: ok");
