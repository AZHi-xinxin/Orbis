// Synthetic loopback-only acceptance; never contacts a phone, provider, tools or user data.
// node tests/live-sync-browser.mjs <playwright package directory> <chrome.exe>
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { resolve, extname, sep } from "node:path";
import { pathToFileURL } from "node:url";
import assert from "node:assert/strict";

const [packageDir, executablePath] = process.argv.slice(2);
if (!packageDir || !executablePath) throw new Error("explicit_local_paths_required");
const { chromium } = await import(pathToFileURL(resolve(packageDir, "index.mjs")).href);
const root = resolve("build/client");
const assistantId = "synthetic-assistant";
const modelId = "synthetic-model";
const settings = {
  assistantId, chatModelId: modelId, titleModelId: modelId, translateModeId: modelId, suggestionModelId: modelId,
  displaySetting: { userNickname: "合成旅人", showUserAvatar: true, showModelName: true, showModelIcon: false,
    showTokenUsage: false, showThinkingContent: true, autoCloseThinking: true, sendOnEnter: true, enableAutoScroll: true, fontSizeRatio: 1 },
  providers: [{ id: "synthetic-provider", enabled: true, name: "离线示例", models: [{ id: modelId, modelId,
    displayName: "合成模型", type: "CHAT", abilities: [], tools: [], inputModalities: ["TEXT"], outputModalities: ["TEXT"] }] }],
  assistants: [{ id: assistantId, name: "合成助手", avatar: { type: "me.rerere.rikkahub.data.model.Avatar.Emoji", content: "⭐" },
    useAssistantAvatar: true, tags: [], chatModelId: modelId }],
  assistantTags: [], modeInjections: [], lorebooks: [], mcpServers: [], searchServices: [], quickMessages: [], searchServiceSelected: 0,
  webServerJwtEnabled: true, webImportEnabled: false,
};
let nextNode = 0;
const node = (text) => {
  const id = ++nextNode;
  return { id: `node-${id}`, selectIndex: 0, messages: [{ id: `message-${id}`, role: "ASSISTANT", modelId,
    createdAt: new Date().toISOString(), finishedAt: new Date().toISOString(), parts: [{ type: "text", text }] }] };
};
const conversation = (id, title, count) => ({ id, assistantId, title, isPinned: false, folderId: null, isGenerating: false,
  createAt: Date.now(), updateAt: Date.now(), chatSuggestions: [],
  messages: Array.from({ length: count }, (_, i) => node(`${title}记录 ${i + 1}：这只是本机合成测试。`)) });
const chats = new Map([["synthetic-a", conversation("synthetic-a", "合成甲", 160)], ["synthetic-b", conversation("synthetic-b", "合成乙", 3)]]);
const streams = new Map();
const requests = [];
const errors = [];
let origin;
function event(response, type, data) { if (!response.writableEnded) response.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`); }
function addNode(id, text) {
  const chat = chats.get(id); const added = node(text); chat.messages.push(added); chat.updateAt++;
  for (const [response, stream] of streams) if (stream.id === id) event(response, "node_update", {
    type: "node_update", seq: ++stream.seq, conversationId: id, nodeId: added.id, nodeIndex: chat.messages.length - 1,
    node: added, updateAt: chat.updateAt, isGenerating: false, serverTime: Date.now(),
  });
}
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".woff2": "font/woff2" };
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname.startsWith("/api/")) {
    requests.push({ method: request.method, path: url.pathname });
    assert.equal(request.method, "GET", "read-only synchronization must not send messages or replay tools");
  }
  const json = (value) => { response.writeHead(200, { "Content-Type": "application/json" }); response.end(JSON.stringify(value)); };
  const openStream = () => {
    response.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store" });
    const heartbeat = setInterval(() => { if (!response.writableEnded) response.write(": heartbeat\n\n"); }, 1000);
    response.on("close", () => { clearInterval(heartbeat); streams.delete(response); });
  };
  if (url.pathname === "/api/events") { openStream(); event(response, "settings", settings); return; }
  const route = url.pathname.match(/^\/api\/conversations\/(synthetic-[ab])(\/stream)?$/);
  if (route) {
    const chat = chats.get(route[1]);
    if (!route[2]) return json(chat);
    openStream(); streams.set(response, { id: chat.id, seq: 1 });
    event(response, "snapshot", { type: "snapshot", seq: 1, conversation: chat, serverTime: Date.now() }); return;
  }
  if (url.pathname === "/api/conversations/paged") return json({ items: [...chats.values()].map(({ messages, ...rest }) => rest), nextOffset: null, hasMore: false });
  if (url.pathname === "/api/folders") return json([]);
  if (url.pathname.startsWith("/api/")) { response.writeHead(404); response.end(); return; }
  try {
    const path = url.pathname === "/" || url.pathname.startsWith("/c/") ? resolve(root, "index.html") : resolve(root, `.${decodeURIComponent(url.pathname)}`);
    if (!path.startsWith(root + sep)) throw new Error("outside_static_root");
    response.writeHead(200, { "Content-Type": types[extname(path)] ?? "application/octet-stream" }); response.end(await readFile(path));
  } catch { response.writeHead(404); response.end(); }
});
await new Promise((done) => server.listen(0, "127.0.0.1", done));
origin = `http://127.0.0.1:${server.address().port}`;
let browser;
let mainFrameNavigations = 0;
try {
  browser = await chromium.launch({ executablePath, headless: true });
  const context = await browser.newContext({ viewport: { width: 1300, height: 900 }, locale: "zh-CN", reducedMotion: "reduce" });
  await context.route("**/*", (route) => {
    const url = new URL(route.request().url());
    if (url.origin === origin || ["data:", "blob:"].includes(url.protocol)) return route.continue();
    errors.push("external_request"); return route.abort();
  });
  await context.addInitScript(() => {
    localStorage.setItem("rikkahub:web-auth", JSON.stringify({ token: "synthetic-local-only", expiresAt: Date.now() + 600000 }));
    localStorage.setItem("vite-ui-theme", "light");
  });
  const page = await context.newPage();
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => { if (request.isNavigationRequest() && request.frame() === page.mainFrame()) mainFrameNavigations++; });
  await page.goto(`${origin}/c/synthetic-a`);
  await page.getByText("合成甲记录 160：这只是本机合成测试。", { exact: true }).waitFor();
  addNode("synthetic-a", "手机新消息直接出现");
  await page.getByText("手机新消息直接出现", { exact: true }).waitFor();
  assert.equal(mainFrameNavigations, 1);
  assert.equal(requests.filter((r) => r.path === "/api/conversations/synthetic-a").length, 1);
  const streamRequestsBeforeTokens = requests.filter((r) => r.path.endsWith("/stream")).length;
  for (let i = 0; i < 40; i++) {
    const chat = chats.get("synthetic-a"); const last = chat.messages.at(-1);
    last.messages[0].parts[0].text = `流式更新第 ${i + 1} 段`;
    for (const [response, stream] of streams) if (stream.id === chat.id) event(response, "node_update", {
      type: "node_update", seq: ++stream.seq, conversationId: chat.id, nodeId: last.id, nodeIndex: chat.messages.length - 1,
      node: last, updateAt: ++chat.updateAt, isGenerating: i !== 39, serverTime: Date.now(),
    });
  }
  await page.getByText("流式更新第 40 段", { exact: true }).waitFor();
  assert.equal(requests.filter((r) => r.path.endsWith("/stream")).length, streamRequestsBeforeTokens);
  assert.equal(requests.filter((r) => r.path === "/api/conversations/synthetic-a").length, 1);

  // Reading older records stays anchored even while the phone adds new nodes.
  await page.getByRole("button", { name: "更早记录", exact: true }).click();
  await page.getByText("显示 2–81 / 161 条 · 原记录完整保留").waitFor();
  const reading = page.getByText("合成甲记录 81：这只是本机合成测试。", { exact: true });
  const before = await reading.boundingBox();
  addNode("synthetic-a", "看旧消息时的新内容");
  await page.getByText("显示 2–81 / 162 条 · 原记录完整保留").waitFor();
  const after = await reading.boundingBox();
  assert.ok(Math.abs(before.y - after.y) < 4, "new messages do not yank the history viewport");
  assert.equal(await page.getByText("看旧消息时的新内容", { exact: true }).count(), 0);
  await page.getByRole("button", { name: "回到最新 (81)", exact: true }).click();
  await page.getByText("看旧消息时的新内容", { exact: true }).waitFor();

  // Simulate an idle socket closing; update the phone-side data before automatic reconnect.
  for (const [response, stream] of streams) if (stream.id === "synthetic-a") response.end();
  addNode("synthetic-a", "断线期间手机发出的消息");
  await page.getByText("断线期间手机发出的消息", { exact: true }).waitFor({ timeout: 10000 });
  assert.ok(requests.filter((r) => r.path === "/api/conversations/synthetic-a/stream").length >= 2);
  assert.equal(requests.filter((r) => r.path === "/api/conversations/synthetic-a").length, 1);
  assert.equal(mainFrameNavigations, 1);

  // Malformed delta must trigger recovery, never freeze quietly while subsequent heartbeats arrive.
  for (const [response, stream] of streams) if (stream.id === "synthetic-a") response.write("event: node_update\ndata: {invalid\n\n");
  addNode("synthetic-a", "损坏流后的完整快照恢复");
  await page.getByText("损坏流后的完整快照恢复", { exact: true }).waitFor({ timeout: 10000 }).catch(async (error) => {
    console.log(JSON.stringify({ syntheticDiagnostics: true, requests, errors, tail: (await page.locator("body").innerText()).slice(-1600) }));
    throw error;
  });

  // The actual mouse wheel (not just history paging buttons) is respected during live updates.
  await page.getByRole("log").hover({ position: { x: 300, y: 200 } });
  await page.mouse.wheel(0, -400);
  await page.getByRole("button", { name: "回到最新", exact: true }).waitFor();
  const historyLine = await page.getByText(/显示 \d+–\d+ \/ 164 条 · 原记录完整保留/).innerText();
  addNode("synthetic-a", "鼠标翻旧记录时不要拉底");
  await page.getByRole("button", { name: "回到最新 (1)", exact: true }).waitFor();
  assert.equal(await page.getByText(historyLine.replace("/ 164", "/ 165"), { exact: true }).count(), 1);
  assert.equal(await page.getByText("鼠标翻旧记录时不要拉底", { exact: true }).count(), 0);
  await page.getByRole("button", { name: "回到最新 (1)", exact: true }).click();
  await page.getByText("鼠标翻旧记录时不要拉底", { exact: true }).waitFor();
  for (let i = 0; i < 85; i++) {
    addNode("synthetic-a", `回最新后继续新增 ${i + 1}`);
    await page.getByText(`回最新后继续新增 ${i + 1}`, { exact: true }).waitFor();
  }
  await page.getByText("显示 171–250 / 250 条 · 原记录完整保留", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: /^回到最新/ }).count(), 0);

  // Client-side conversation switching aborts old subscriptions; old conversation stays out.
  await page.getByRole("button", { name: "合成乙", exact: true }).click();
  await page.getByText("合成乙记录 3：这只是本机合成测试。", { exact: true }).waitFor();
  addNode("synthetic-a", "不应串入乙窗口"); addNode("synthetic-b", "乙窗口实时消息");
  await page.getByText("乙窗口实时消息", { exact: true }).waitFor();
  assert.equal(await page.getByText("不应串入乙窗口", { exact: true }).count(), 0);
  assert.deepEqual(errors, []);
  assert.ok(requests.every((r) => r.method === "GET"));
  console.log(JSON.stringify({ syntheticOnly: true, livePhoneMessages: true, reconnectCatchesMissedMessages: true,
    malformedStreamRecovery: true, historyViewportPreserved: true, realWheelHistoryPreserved: true,
    streamingDeltasWithoutRefetch: 40, appendedAfterReturningToLatest: 85, conversationSwitchIsolation: true,
    fullHistoryGetsForFirstChat: 1, automaticPageReloads: 0, apiMutations: 0, externalRequests: 0, pageErrors: 0 }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise((done) => server.close(done));
}
