import test from "node:test";
import assert from "node:assert/strict";
import { BRAND_THEMES, readColorTheme, welcomeName } from "../app/lib/orbis-appearance";

const key = "synthetic-ui";
function memoryStorage(initial: Record<string, string> = {}) {
  const values = new Map(Object.entries(initial));
  return { values, getItem: (name: string) => values.get(name) ?? null,
    setItem: (name: string, value: string) => { values.set(name, value); } };
}

test("welcome greets the human nickname, with the requested unset placeholder", () => {
  assert.equal(welcomeName("  示例旅人  "), "示例旅人");
  for (const empty of ["", "   ", undefined, null]) assert.equal(welcomeName(empty), "---");
  assert.equal(welcomeName("<script>literal nickname</script>"), "<script>literal nickname</script>");
});

test("only Orbis and DeepSeek are branded choices", () => {
  assert.deepEqual(BRAND_THEMES, ["orbis", "deepseek"]);
});

test("new visitors use Orbis, and the supported brand survives a reload", () => {
  const fresh = memoryStorage();
  assert.equal(readColorTheme(fresh, key), "orbis");
  fresh.setItem(`${key}-color`, "deepseek");
  assert.equal(readColorTheme(fresh, key), "deepseek");
  assert.equal(readColorTheme(fresh, key), "deepseek");
});

test("old palettes migrate once and retain their former choice", () => {
  for (const old of ["default", "claude", "t3-chat", "mono", "bubblegum", "custom"]) {
    const store = memoryStorage({ [`${key}-color`]: old });
    assert.equal(readColorTheme(store, key), "orbis");
    assert.equal(store.getItem(`${key}-color`), "orbis");
    assert.equal(store.getItem(`${key}-legacy-color`), old);
    store.setItem(`${key}-color`, "deepseek");
    assert.equal(readColorTheme(store, key), "deepseek");
    assert.equal(store.getItem(`${key}-legacy-color`), old);
  }
});

test("migration preserves both custom CSS buffers and later explicit reapplication", () => {
  const css = { [`${key}-custom-light`]: ":root { --primary: blue; }",
    [`${key}-custom-dark`]: ".dark { --primary: pink; }" };
  const store = memoryStorage({ ...css, [`${key}-color`]: "custom", [key]: "dark" });
  assert.equal(readColorTheme(store, key), "orbis");
  for (const [name, value] of Object.entries(css)) assert.equal(store.getItem(name), value);
  assert.equal(store.getItem(key), "dark");
  store.setItem(`${key}-color`, "custom");
  assert.equal(readColorTheme(store, key), "custom");
});

test("unknown data or unavailable browser storage cannot prevent rendering", () => {
  assert.equal(readColorTheme(memoryStorage({ [`${key}-color`]: "unknown" }), key), "orbis");
  const denied = { getItem: () => { throw new Error("denied"); }, setItem: () => { throw new Error("denied"); } };
  assert.equal(readColorTheme(denied, key), "orbis");
});
