// Development-only loopback fixture; no phone/provider/history access or mutations.
// node tests/orbis-event-browser.mjs <playwright-dir> <chrome.exe> <output-dir>
import { createServer } from "node:http";
import { readFile, mkdir } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const [packageDir, executablePath, outputDirectory] = process.argv.slice(2);
if (!packageDir || !executablePath || !outputDirectory) throw new Error("explicit_local_paths_required");
const { chromium } = await import(pathToFileURL(resolve(packageDir, "index.mjs")).href);
const root = resolve("build/client");
const assistantId = "synthetic-assistant";
const modelId = "synthetic-model";
const conversationId = "synthetic-sentinel";
const stamp = "2026-09-27T21:15:00Z";
const settings = {
  assistantId, chatModelId: modelId, displaySetting: { userNickname: "合成旅人", showUserAvatar: true,
    showModelName: true, showModelIcon: false, showTokenUsage: false, showThinkingContent: true,
    autoCloseThinking: true, sendOnEnter: true, enableAutoScroll: true, fontSizeRatio: 1 },
  providers: [{ id: "synthetic-provider", enabled: true, name: "离线示例", models: [{ id: modelId, modelId,
    displayName: "合成模型", type: "CHAT", abilities: [], tools: [], inputModalities: ["TEXT"], outputModalities: ["TEXT"] }] }],
  assistants: [{ id: assistantId, name: "合成助手", avatar: { type: "me.rerere.rikkahub.data.model.Avatar.Emoji", content: "⭐" },
    useAssistantAvatar: true, tags: [], chatModelId: modelId }],
  assistantTags: [], modeInjections: [], lorebooks: [], mcpServers: [], searchServices: [], quickMessages: [],
  searchServiceSelected: 0, webServerJwtEnabled: true, webImportEnabled: false,
};
const ordinary = "【哨兵提示】这其实是用户写的普通消息，不能误判。";
const original = "【合成哨兵】\n  现在可以轻轻唤醒助手。\n<script>window.sentinelInjected = true</script>\n![不应自动联网](https://example.invalid/not-an-image)  ";
const metadata = { recordId: "synthetic-record", source: "native_sentinel.synthetic", eventId: "synthetic-event",
  receivedAt: Date.parse(stamp), occurredAt: Date.parse(stamp) - 60_000, read: false, collapsed: true };
const node = (id, role, text, extra = {}) => ({ id, selectIndex: 0, messages: [{ id: `message-${id}`, role,
  modelId, createdAt: stamp, finishedAt: stamp, parts: [{ type: "text", text }], ...extra }] });
const conversation = { id: conversationId, assistantId, title: "哨兵显示 · 离线合成测试", isPinned: false,
  folderId: null, isGenerating: false, createAt: Date.parse(stamp), updateAt: Date.parse(stamp), chatSuggestions: [], messages: [
    node("ordinary", "USER", ordinary),
    node("event", "USER", original, { orbisEvent: metadata,
      parts: [{ type: "text", text: original }, { type: "image", url: "synthetic-attachment.svg" }] }),
    node("legacy", "USER", "旧记录仍完整保留。", { orbisEvent: { recordId: "legacy-record", source: "legacy_sentinel",
      eventId: "legacy-event", receivedAt: Date.parse(stamp) - 86_400_000 } }),
    node("assistant", "ASSISTANT", "这是正常回复，哨兵时间单独显示。"),
  ] };
const persisted = JSON.stringify(conversation);
const requests = [];
const streams = new Set();
const errors = [];
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".woff": "font/woff" };
const send = (response, type, data) => response.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  const json = (value) => { response.writeHead(200, { "Content-Type": "application/json" }); response.end(JSON.stringify(value)); };
  if (url.pathname.startsWith("/api/")) requests.push({ path: url.pathname, method: request.method });
  if (request.method !== "GET") { response.writeHead(405); response.end(); return; }
  const stream = () => {
    response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    const timer = setInterval(() => response.write(": heartbeat\n\n"), 1000);
    response.on("close", () => { clearInterval(timer); streams.delete(response); });
  };
  if (url.pathname === "/api/events") { stream(); send(response, "settings", settings); return; }
  if (url.pathname === `/api/conversations/${conversationId}/stream`) {
    stream(); streams.add(response); send(response, "snapshot", { type: "snapshot", seq: 1, conversation, serverTime: Date.now() }); return;
  }
  if (url.pathname === `/api/conversations/${conversationId}`) return json(conversation);
  if (url.pathname === "/api/conversations/paged") {
    const { messages, ...summary } = conversation;
    return json({ items: [summary], nextOffset: null, hasMore: false });
  }
  if (url.pathname === "/api/folders") return json([]);
  if (url.pathname === "/api/files/path/synthetic-attachment.svg") {
    response.writeHead(200, { "Content-Type": "image/svg+xml" });
    response.end('<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60"><rect width="120" height="60" fill="#547ab5"/><text x="12" y="35" fill="white">Synthetic</text></svg>'); return;
  }
  if (url.pathname.startsWith("/api/")) { response.writeHead(404); response.end(); return; }
  try {
    const file = url.pathname === "/" || url.pathname.startsWith("/c/") ? resolve(root, "index.html") : resolve(root, `.${decodeURIComponent(url.pathname)}`);
    assert.ok(file.startsWith(root + sep));
    response.writeHead(200, { "Content-Type": types[extname(file)] ?? "application/octet-stream" }); response.end(await readFile(file));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
const origin = `http://127.0.0.1:${server.address().port}`;
await mkdir(outputDirectory, { recursive: true });
let browser;
let checked = 0;
try {
  browser = await chromium.launch({ executablePath, headless: true });
  for (const appearance of [
    { theme: "orbis", mode: "light", width: 1280, height: 900 },
    { theme: "deepseek", mode: "dark", width: 1280, height: 900 },
    { theme: "orbis", mode: "dark", width: 390, height: 844 },
    { theme: "deepseek", mode: "light", width: 390, height: 844 },
  ]) {
    const context = await browser.newContext({ viewport: { width: appearance.width, height: appearance.height },
      locale: "zh-CN", timezoneId: "Asia/Shanghai", reducedMotion: "reduce" });
    await context.route("**/*", (route) => {
      const url = new URL(route.request().url());
      if (url.origin === origin || ["data:", "blob:"].includes(url.protocol)) return route.continue();
      errors.push(`external_request:${url.origin}`); return route.abort();
    });
    await context.addInitScript(({ theme, mode }) => {
      localStorage.setItem("rikkahub:web-auth", JSON.stringify({ token: "synthetic-local-only", expiresAt: Date.now() + 600000 }));
      localStorage.setItem("vite-ui-theme", mode);
      localStorage.setItem("vite-ui-theme-color", theme);
      localStorage.setItem("vite-ui-theme-orbis-theme-version", "2");
    }, appearance);
    const page = await context.newPage();
    page.on("pageerror", (error) => errors.push(error.message));
    const attachmentRequests = () => requests.filter((r) => r.path === "/api/files/path/synthetic-attachment.svg").length;
    const beforeAttachments = attachmentRequests();
    await page.goto(`${origin}/c/${conversationId}`);
    const cards = page.locator("[data-orbis-event-card]");
    await cards.nth(1).waitFor();
    assert.equal(await cards.count(), 2);
    assert.equal(await page.locator("[data-message-role=user]").count(), 1);
    assert.equal(await page.locator("[data-message-role=user]").innerText().then((value) => value.includes(ordinary)), true);
    assert.equal(await cards.locator("[data-message-bubble], [data-message-actions], img").count(), 0);
    assert.equal(attachmentRequests(), beforeAttachments);
    const toggle = cards.first().locator("[data-orbis-event-toggle]");
    assert.equal(await toggle.getAttribute("aria-expanded"), "false");
    assert.match(await toggle.getAttribute("aria-label"), /来源提供的触发时间/);
    assert.match(await cards.nth(1).locator("button").getAttribute("aria-label"), /接收时间（未提供触发时间）/);
    assert.ok((await cards.first().boundingBox()).height <= 48, "collapsed sentinel stays a quiet single timestamp row");
    await page.screenshot({ path: resolve(outputDirectory, `${appearance.theme}-${appearance.mode}-${appearance.width}-collapsed.png`) });
    await toggle.focus(); await page.keyboard.press("Enter");
    await cards.first().locator("[data-orbis-event-original]").waitFor();
    assert.equal(await toggle.getAttribute("aria-expanded"), "true");
    assert.equal(await cards.first().locator("[data-orbis-event-original]").textContent(), original + "\n");
    await cards.first().locator("img").waitFor({ state: "visible" });
    assert.ok(attachmentRequests() > beforeAttachments);
    assert.equal(await page.evaluate(() => window.sentinelInjected), undefined);
    assert.equal(await cards.first().locator("script, a[href*=example]").count(), 0);
    // An SSE snapshot must neither repaint the card as a user nor reset a local disclosure.
    for (const subscriber of streams) send(subscriber, "snapshot", { type: "snapshot", seq: 2, conversation, serverTime: Date.now() });
    await page.getByText("这是正常回复，哨兵时间单独显示。", { exact: true }).waitFor();
    assert.equal(await toggle.getAttribute("aria-expanded"), "true");
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false);
    await page.screenshot({ path: resolve(outputDirectory, `${appearance.theme}-${appearance.mode}-${appearance.width}-expanded.png`) });
    await toggle.focus(); await page.keyboard.press("Space");
    assert.equal(await toggle.getAttribute("aria-expanded"), "false");
    assert.equal(await cards.first().locator("img").count(), 0);
    assert.equal(JSON.stringify(conversation), persisted, "UI disclosure never mutates source fixtures");
    await context.close(); checked++;
  }
  assert.deepEqual(errors, []);
  assert.ok(requests.every((request) => request.method === "GET"));
  console.log(JSON.stringify({ syntheticOnly: true, cases: checked, themes: 2, mobileAndDesktop: true,
    keyboardToggle: true, metadataOnlyDetection: true, plainTextPreserved: true, lazyAttachments: true,
    sseDisclosureStable: true, sourceDataUnchanged: true, apiMutations: 0, externalRequests: 0, pageErrors: 0 }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise((done) => server.close(done));
}
