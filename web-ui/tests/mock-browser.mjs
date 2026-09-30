// Optional development-only UI smoke test. Synthetic data, loopback only, fresh browser profile.
// Usage: node tests/mock-browser.mjs <playwright-package-dir> <chrome.exe> <screenshot-directory>
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const [packageDir, executablePath, outputDir] = process.argv.slice(2);
if (!packageDir || !executablePath || !outputDir) throw new Error("explicit_local_paths_required");
const { chromium } = await import(pathToFileURL(resolve(packageDir, "index.mjs")).href);
const root = resolve("build/client");
const assistantId = "11111111-1111-4111-8111-111111111111";
const conversationId = "22222222-2222-4222-8222-222222222222";
const modelId = "33333333-3333-4333-8333-333333333333";
const stamp = "2026-09-25T10:00:00Z";
const settings = {
  dynamicColor: false, themeId: "default", developerMode: false, favoriteModels: [], chatModelId: modelId,
  assistantId, displaySetting: { userNickname: "示例旅人", userAvatar: { type: "me.rerere.rikkahub.data.model.Avatar.Emoji", content: "🌙" },
    showUserAvatar: true, showModelName: true, showModelIcon: false, showTokenUsage: false,
    showThinkingContent: true, autoCloseThinking: true, sendOnEnter: true, enableAutoScroll: true, fontSizeRatio: 1 },
  providers: [{ id: "synthetic-provider", enabled: true, name: "离线示例", models: [{ id: modelId, modelId: "synthetic-model", displayName: "示例模型", type: "CHAT", abilities: [], tools: [], inputModalities: ["TEXT"], outputModalities: ["TEXT"] }] }],
  assistants: [{ id: assistantId, name: "Orbis", avatar: { type: "me.rerere.rikkahub.data.model.Avatar.Emoji", content: "⭐" }, useAssistantAvatar: true, tags: [], chatModelId: modelId }],
  assistantTags: [], modeInjections: [], lorebooks: [], mcpServers: [], searchServices: [], quickMessages: [], searchServiceSelected: 0,
  webServerJwtEnabled: true, webImportEnabled: true,
};
const conversation = { id: conversationId, assistantId, title: "星空手记 · 合成示例", isPinned: true, folderId: null, isGenerating: false,
  createAt: Date.now(), updateAt: Date.now(), chatSuggestions: [], messages: Array.from({ length: 23690 }, (_, i) => ({
    id: `node-${i}`, selectIndex: 0, messages: [{ id: `message-${i}`, role: i % 2 ? "ASSISTANT" : "USER", modelId,
      createdAt: stamp, finishedAt: stamp, annotations: [], parts: [{ type: "text", metadata: { import_source: "deepseek" },
        text: i === 23689 ? "## 今晚的星光\n\n长记录仍完整保留，这里只绘制最近的一小段。\n\n![合成图片](https://example.invalid/no-network.png)\n\n<img src=\"https://example.invalid/raw.png\">\n\n**只在你主动点击后，才加载原地址的图片。**" : `合成记录 ${i + 1}：把今天的一点星光留在这里。` }],
    }],
  })) };
const status = { id: "synthetic-preview", state: "ready", assistantId, expiresAt: Date.now() + 600000, uploadedBytes: 28000000,
  maxArchiveBytes: 83886080, conversationCount: 2, reviewToken: "synthetic-review", total: 0, completed: 0, imported: 0, skipped: 0, failed: 0, messages: 0, attachmentReferences: 0, rows: [], error: null };
const previewRows = [{ index: 0, title: "一起看星星 · 示例窗口", totalNodes: 1800, messageCount: 1700, branchPointCount: 3, branchCount: 4, defaultBranch: 0, defaultSelectionReason: "source_current_node" },
  { index: 1, title: "远行的准备 · 示例窗口", totalNodes: 2100, messageCount: 2050, branchPointCount: 2, branchCount: 3, defaultBranch: 0, defaultSelectionReason: "newest_leaf" }];
const contentTypes = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".woff": "font/woff", ".json": "application/json" };
const settingsSubscribers = new Set();
const unexpectedApiMutations = [];
const importRequests = { creates: [], uploads: [], commits: [], cancels: [] };
const importJobs = new Map([["synthetic-preview", { source: "deepseek", status, rows: previewRows }]]);
async function requestBuffer(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks);
}
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  const json = (value) => { response.writeHead(200, { "Content-Type": "application/json" }); response.end(JSON.stringify(value)); };
  const sse = (event, data) => {
    response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    response.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    const timer = setInterval(() => response.write(": keepalive\n\n"), 15000);
    response.on("close", () => clearInterval(timer));
  };
  if (url.pathname === "/api/events") {
    settingsSubscribers.add(response);
    response.on("close", () => settingsSubscribers.delete(response));
    return sse("settings", settings);
  }
  if (url.pathname === "/api/orbis/profile" && request.method === "POST") {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const profile = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    assert.equal(typeof profile.nickname, "string");
    assert.ok(profile.nickname.length <= 80);
    settings.displaySetting.userNickname = profile.nickname;
    for (const subscriber of settingsSubscribers) subscriber.write(`event: settings\ndata: ${JSON.stringify(settings)}\n\n`);
    return json({ ok: true, nickname: profile.nickname });
  }
  const importRoute = url.pathname.match(/^\/api\/imports\/(deepseek|rikkahub|codex)(?:\/([^/]+))?(?:\/(archive|conversations|branches|commit))?$/);
  if (importRoute) {
    const [, source, id, action] = importRoute;
    assert.equal(request.headers.authorization, "Bearer synthetic-local-only");
    assert.equal(request.headers["x-orbis-web-origin"], origin);
    if (!id && request.method === "POST") {
      const created = JSON.parse((await requestBuffer(request)).toString("utf8"));
      assert.deepEqual(created, { assistantId });
      importRequests.creates.push(source);
      const jobId = `synthetic-${source}-${importRequests.creates.length}`;
      const jobStatus = { ...status, id: jobId, state: "created", uploadedBytes: 0, conversationCount: 1,
        reviewToken: `synthetic-review-${source}`, rows: [], imported: 0 };
      const rows = [{ ...previewRows[0], title: `${source} · 合成导入窗口`, branchCount: source === "deepseek" ? 4 : 1,
        defaultSelectionReason: source === "rikkahub" ? "all_branches_preserved" : source === "codex" ? "codex_text_only" : "source_current_node" }];
      importJobs.set(jobId, { source, status: jobStatus, rows });
      return json(jobStatus);
    }
    const job = importJobs.get(id);
    assert.ok(job, "synthetic job must exist");
    assert.equal(job.source, source, "every request stays within the job's source");
    if (action === "archive" && request.method === "PUT") {
      const body = await requestBuffer(request);
      const expectedContentType = source === "codex" ? "application/x-ndjson" : "application/zip";
      assert.equal(request.headers["content-type"], expectedContentType);
      assert.ok(body.length > 0);
      if (source === "codex") assert.equal(body.at(-1), 10);
      importRequests.uploads.push({ source, contentType: request.headers["content-type"] });
      Object.assign(job.status, { state: "ready", uploadedBytes: body.length });
      return json(job.status);
    }
    if (action === "commit" && request.method === "POST") {
      const commit = JSON.parse((await requestBuffer(request)).toString("utf8"));
      assert.deepEqual(commit, { reviewToken: job.status.reviewToken, confirmed: true, selections: [{ conversation: 0, branch: 0 }] });
      assert.ok(!importRequests.commits.includes(source), "a confirmation cannot replay");
      importRequests.commits.push(source);
      Object.assign(job.status, { state: "complete", total: 1, completed: 1, imported: 1, messages: 1700, rows: [{ conversation: 0, state: "imported" }] });
      return json(job.status);
    }
    if (!action && request.method === "DELETE") {
      importRequests.cancels.push(source);
      job.status.state = "cancelled";
      return json(job.status);
    }
    if (action === "conversations" && request.method === "GET") return json({ items: job.rows, nextOffset: null, hasMore: false });
    if (action === "branches" && request.method === "GET") return json({ items: [0, 1, 2, 3].map((index) => ({ index, messageCount: 1700 - index * 50, updatedAt: stamp, isDefault: index === 0 })), nextOffset: null, hasMore: false });
    if (!action && request.method === "GET") return json(job.status);
  }
  if (url.pathname.startsWith("/api/") && request.method !== "GET") unexpectedApiMutations.push(url.pathname);
  if (url.pathname === `/api/conversations/${conversationId}/stream`) return sse("snapshot", { type: "snapshot", seq: 1, conversation, serverTime: Date.now() });
  if (url.pathname === `/api/conversations/${conversationId}`) return json(conversation);
  if (url.pathname === "/api/conversations/paged") return json({ items: [{ ...conversation, messages: undefined }], nextOffset: null, hasMore: false });
  if (url.pathname === "/api/folders") return json([]);
  if (url.pathname === "/api/settings") return json(settings);
  if (url.pathname.startsWith("/api/")) { response.writeHead(404, { "Content-Type": "application/json" }); return response.end('{"error":"synthetic_endpoint_not_enabled","code":404}'); }
  try {
    const file = url.pathname === "/" || url.pathname.startsWith("/c/") ? resolve(root, "index.html") : resolve(root, `.${decodeURIComponent(url.pathname)}`);
    if (!file.startsWith(root + sep)) throw new Error("outside_static_root");
    response.writeHead(200, { "Content-Type": contentTypes[extname(file)] ?? "application/octet-stream" });
    response.end(await readFile(file));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const origin = `http://127.0.0.1:${server.address().port}`;
await mkdir(outputDir, { recursive: true });
let browser;
const externalAttempts = [];
const errors = [];
try {
  browser = await chromium.launch({ executablePath, headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: "zh-CN", colorScheme: "light", reducedMotion: "reduce" });
  await context.route("**/*", (route) => {
    const url = new URL(route.request().url());
    if (url.origin === origin || url.protocol === "data:" || url.protocol === "blob:") return route.continue();
    externalAttempts.push(url.origin); return route.abort();
  });
  await context.addInitScript(() => {
    localStorage.setItem("rikkahub:web-auth", JSON.stringify({ token: "synthetic-local-only", expiresAt: Date.now() + 600000 }));
    localStorage.setItem("vite-ui-theme", "light"); localStorage.setItem("vite-ui-theme-color", "orbis");
    sessionStorage.setItem("orbis:web-deepseek-job", "synthetic-preview");
  });
  const page = await context.newPage();
  page.on("pageerror", () => errors.push("page_error"));
  await page.goto(origin);
  await page.getByText("示例旅人，欢迎回家", { exact: true }).waitFor();
  const profileButton = page.getByRole("button", { name: "修改用户名与外观", exact: true });
  await profileButton.focus();
  await page.keyboard.press("Enter");
  await page.getByRole("heading", { name: "外观与昵称", exact: true }).waitFor();
  await page.getByLabel("你的昵称", { exact: true }).fill("归家的旅人");
  await page.getByRole("button", { name: "保存昵称", exact: true }).click();
  await page.getByText("昵称已保存，手机与 Web 会同步显示。", { exact: true }).waitFor();
  await page.keyboard.press("Escape");
  await page.getByText("归家的旅人，欢迎回家", { exact: true }).waitFor();
  await page.getByRole("button", { name: "修改用户名与外观", exact: true }).click();
  await page.getByLabel("你的昵称", { exact: true }).fill("");
  await page.getByRole("button", { name: "保存昵称", exact: true }).click();
  await page.getByText("昵称已保存，手机与 Web 会同步显示。", { exact: true }).waitFor();
  await page.keyboard.press("Escape");
  await page.getByText("---，欢迎回家", { exact: true }).waitFor();
  await page.getByRole("button", { name: "修改用户名与外观", exact: true }).click();
  await page.getByRole("button", { name: "DeepSeek 风格", exact: true }).click();
  await page.keyboard.press("Escape");
  assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), "deepseek");
  assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), "rgb(255, 255, 255)");
  await page.screenshot({ path: resolve(outputDir, "orbis-web-deepseek-desktop-light.png"), animations: "disabled" });
  await page.getByRole("button", { name: "修改用户名与外观", exact: true }).click();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await page.keyboard.press("Escape");
  assert.equal(await page.evaluate(() => getComputedStyle(document.body).backgroundColor), "rgb(41, 42, 45)");
  await page.screenshot({ path: resolve(outputDir, "orbis-web-deepseek-desktop-dark.png"), animations: "disabled" });
  await page.getByRole("button", { name: "主题色：DeepSeek 风格", exact: true }).click();
  await page.getByRole("menuitem", { name: "Orbis 白灰蓝", exact: true }).waitFor();
  await page.getByRole("menuitem", { name: "DeepSeek 风格", exact: true }).waitFor();
  assert.equal(await page.getByRole("menuitem", { name: /Claude|T3 Chat|Mono|Bubblegum/ }).count(), 0);
  await page.keyboard.press("Escape");
  await page.goto(`${origin}/c/${conversationId}`);
  await page.getByRole("heading", { name: "今晚的星光" }).waitFor();
  await page.waitForFunction(() => {
    const heading = [...document.querySelectorAll("h2")].find((item) => item.textContent === "今晚的星光");
    return heading && heading.getBoundingClientRect().bottom < window.innerHeight - 170;
  });
  assert.equal(await page.locator("[data-message-role]").count(), 80);
  assert.equal(await page.locator('[data-message-role] img[src*="example.invalid"]').count(), 0);
  await page.screenshot({ path: resolve(outputDir, "orbis-web-desktop-light.png"), animations: "disabled" });
  await page.getByRole("button", { name: "更早记录", exact: true }).click();
  await page.getByText("显示 23531–23610 / 23690 条 · 原记录完整保留").waitFor();
  assert.equal(await page.locator("[data-message-role]").count(), 80);
  await page.getByRole("button", { name: "回到最新 (80)", exact: true }).click();
  await page.getByRole("heading", { name: "今晚的星光" }).waitFor();
  await page.getByRole("button", { name: "导入聊天", exact: true }).click();
  await page.getByText("一起看星星 · 示例窗口", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "确认导入 2 个所选窗口路径", exact: true }).isDisabled(), true);
  await page.screenshot({ path: resolve(outputDir, "orbis-web-import-preview.png"), animations: "disabled" });
  await page.getByLabel("聊天记录来源", { exact: true }).selectOption("rikkahub");
  await page.getByRole("heading", { name: "导入 RikkaHub 聊天", exact: true }).waitFor();
  assert.deepEqual(importRequests.cancels, ["deepseek"]);
  assert.deepEqual(importRequests.commits, []);
  await page.getByLabel("聊天记录来源", { exact: true }).selectOption("deepseek");
  for (const source of ["deepseek", "rikkahub", "codex"]) {
    await page.getByLabel("聊天记录来源", { exact: true }).selectOption(source);
    const label = source === "deepseek" ? "DeepSeek" : source === "rikkahub" ? "RikkaHub" : "Codex";
    await page.getByRole("heading", { name: `导入 ${label} 聊天`, exact: true }).waitFor();
    const fileInput = page.getByLabel(`选择 ${label} 导入文件`, { exact: true });
    assert.equal(await fileInput.getAttribute("accept"), source === "codex" ? ".jsonl,application/x-ndjson" : ".zip,application/zip");
    if (source === "codex") {
      await fileInput.setInputFiles({ name: "rollout-synthetic.jsonl", mimeType: "application/x-ndjson", buffer: Buffer.from('{"type":"session_meta","payload":{}}') });
      await page.getByRole("alert").filter({ hasText: "完整且以换行结尾" }).waitFor();
      assert.equal(importRequests.creates.includes("codex"), false);
    }
    await fileInput.setInputFiles({ name: source === "codex" ? "rollout-synthetic.jsonl" : "synthetic.zip",
      mimeType: source === "codex" ? "application/x-ndjson" : "application/zip",
      buffer: source === "codex" ? Buffer.from('{"type":"session_meta","payload":{"id":"synthetic"}}\n') : Buffer.from("PK synthetic UI fixture; parser tested separately") });
    await page.getByText(`${source} · 合成导入窗口`, { exact: true }).waitFor();
    const confirm = page.getByRole("button", { name: source === "deepseek" ? "确认导入 1 个所选窗口路径" : "确认导入 1 个所选窗口", exact: true });
    assert.equal(await confirm.isDisabled(), true);
    assert.equal(importRequests.commits.includes(source), false);
    await page.getByLabel(/^我确认接收身份/).check();
    await confirm.click();
    await page.getByText("导入已完成", { exact: true }).waitFor();
    assert.equal(importRequests.commits.filter((value) => value === source).length, 1);
  }
  assert.deepEqual(importRequests.creates, ["deepseek", "rikkahub", "codex"]);
  assert.deepEqual(importRequests.uploads, [
    { source: "deepseek", contentType: "application/zip" },
    { source: "rikkahub", contentType: "application/zip" },
    { source: "codex", contentType: "application/x-ndjson" },
  ]);
  assert.deepEqual(importRequests.commits, ["deepseek", "rikkahub", "codex"]);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page.getByRole("button", { name: "北斗 · Orbis 功能", exact: true }).click();
  await page.getByRole("menuitem", { name: "外观与昵称", exact: true }).click();
  await page.getByRole("button", { name: "深色", exact: true }).click();
  await page.keyboard.press("Escape");
  await page.getByRole("dialog").waitFor({ state: "hidden" });
  await page.screenshot({ path: resolve(outputDir, "orbis-web-desktop-dark.png"), animations: "disabled" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: resolve(outputDir, "orbis-web-mobile-dark.png"), animations: "disabled" });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
  await page.getByRole("button", { name: "北斗 · Orbis 功能", exact: true }).click();
  await page.getByRole("menuitem", { name: "外观与昵称", exact: true }).click();
  await page.getByRole("button", { name: "浅色", exact: true }).click();
  await page.keyboard.press("Escape");
  await page.getByRole("dialog").waitFor({ state: "hidden" });
  await page.screenshot({ path: resolve(outputDir, "orbis-web-mobile-light.png"), animations: "disabled" });
  assert.deepEqual(errors, []);
  assert.deepEqual(externalAttempts, []);
  assert.deepEqual(unexpectedApiMutations, []);
  console.log(JSON.stringify({ syntheticOnly: true, messageCount: 23690, renderedMessages: 80, historyNavigation: true, importConfirmationDisabledByDefault: true, importSources: importRequests.commits, importContentTypesVerified: true, importSourceIsolation: true, codexIncompleteRejectedBeforeUpload: true, nicknameEditAndFallback: true, deepseekLightAndDark: true, keyboardProfileEntry: true, screenshots: 7, pageErrors: 0, externalRequests: 0, unexpectedApiMutations: 0 }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise((done) => server.close(done));
}
