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
    displayName: "合成模型", type: "CHAT", abilities: [], tools: [], inputModalities: ["TEXT", "IMAGE"], outputModalities: ["TEXT"] }] }],
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
let uploadFailure = false;
const uploaded = [];
const sent = [];
const streams = new Set();
const errors = [];
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".woff2": "font/woff2", ".woff": "font/woff" };
const send = (response, type, data) => response.write(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);
const server = createServer(async (request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  const json = (value) => { response.writeHead(200, { "Content-Type": "application/json" }); response.end(JSON.stringify(value)); };
  if (url.pathname.startsWith("/api/")) requests.push({ path: url.pathname, method: request.method });
  if (url.pathname === "/capture-target.html") { response.writeHead(200,{"Content-Type":"text/html"}); response.end('<title>Orbis Synthetic Capture Only</title><body style="background:#5288b3;color:white"><h1>SYNTHETIC CAPTURE</h1><p>No private data</p></body>'); return; }
  if (request.method === "POST" && url.pathname === "/api/files/upload") {
    const chunks = []; for await (const c of request) chunks.push(c);
    const body = Buffer.concat(chunks);
    if (uploadFailure) { response.writeHead(500); response.end("synthetic_failure"); return; }
    const start = body.indexOf(Buffer.from([137,80,78,71,13,10,26,10]));
    assert.ok(start >= 0);
    uploaded.push({ width: body.readUInt32BE(start + 16), height: body.readUInt32BE(start + 20) });
    return json({ files: [{ id: uploaded.length, url: "synthetic-attachment.svg", fileName: "screenshot.png", mime: "image/png", size: body.length }] });
  }
  if (request.method === "POST" && url.pathname === `/api/conversations/${conversationId}/messages`) {
    const chunks=[]; for await (const c of request) chunks.push(c);
    sent.push(JSON.parse(Buffer.concat(chunks).toString())); return json({status:"ok"});
  }
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
try {
  browser = await chromium.launch({ executablePath, headless: false, args: ["--auto-select-tab-capture-source-by-title=Orbis Synthetic Capture Only"] });
  for (const width of [1280, 390]) {
    const context = await browser.newContext({ viewport: {width,height:900}, locale:"zh-CN", reducedMotion:"reduce" });
    await context.route("**/*", route => new URL(route.request().url()).origin === origin || /^(data|blob):/.test(route.request().url()) ? route.continue() : route.abort());
    await context.addInitScript(() => {
      localStorage.setItem("rikkahub:web-auth", JSON.stringify({token:"synthetic-local-only", expiresAt:Date.now()+600000}));
      window.captureTracks = []; window.captureMode = "success";
      navigator.mediaDevices.getDisplayMedia = async options => {
        if (options.audio !== false) throw Error("audio_requested");
        if (window.captureMode === "cancel") throw new DOMException("private screen name", "NotAllowedError");
        const canvas = document.createElement("canvas"); canvas.width=800;canvas.height=600;
        const ctx=canvas.getContext("2d"); ctx.fillStyle="#5288b3"; ctx.fillRect(0,0,800,600);
        ctx.fillStyle="#ffd788"; ctx.fillRect(200,120,400,300);
        const stream=canvas.captureStream(20); window.captureTracks.push(...stream.getTracks());
        // Keep synthetic frame source alive until tracks are stopped.
        const timer=setInterval(()=>ctx.fillRect(200,120,400,300),30);
        setTimeout(()=>clearInterval(timer),12000); return stream;
      };
    });
    const page=await context.newPage(); page.on("pageerror", e=>errors.push(e.message));
    await page.goto(`${origin}/c/${conversationId}`);
    const open=async()=>{
      await page.locator('button[aria-haspopup=menu]:has(svg.lucide-plus)').click();
      await page.getByRole("menuitem",{name:"截图并裁剪"}).click();
    };
    await page.getByRole("textbox").last().waitFor();
    const before=uploaded.length;
    await open(); await page.getByTestId("capture-preview").waitFor();
    assert.equal(uploaded.length,before,"preview is local");
    assert.equal(await page.evaluate(()=>captureTracks.every(t=>t.readyState==="ended")),true,"capture tracks stopped after single frame");
    await page.getByRole("button",{name:"取消",exact:true}).click();
    assert.equal(uploaded.length,before);
    await page.evaluate(()=>window.captureMode="cancel"); await open();
    await page.getByRole("alert").filter({hasText:"已取消截图"}).waitFor();
    await page.getByRole("button",{name:"取消",exact:true}).click();
    await page.evaluate(()=>window.captureMode="success"); await open();
    await page.getByTestId("capture-preview").waitFor();
    for (const [name,value] of [["左边",25],["上边",20],["宽度",50],["高度",50]]) await page.getByRole("spinbutton",{name:`${name}百分比`}).fill(String(value));
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
    await page.screenshot({path:resolve(outputDirectory,`capture-preview-${width}.png`)});
    uploadFailure=true; await page.getByRole("button",{name:"添加到输入框",exact:true}).click();
    await page.getByRole("alert").filter({hasText:"附件未添加"}).waitFor();
    assert.equal(uploaded.length,before); uploadFailure=false;
    await page.getByRole("button",{name:"添加到输入框",exact:true}).click();
    await page.getByRole("dialog").waitFor({state:"hidden"});
    assert.deepEqual(uploaded.at(-1),{width:400,height:300});
    assert.equal(sent.length,width===1280?0:1,"adding does not send");
    await page.getByRole("button",{name:"发送消息",exact:true}).click();
    await page.waitForFunction(()=>document.querySelector('textarea')?.value==="");
    for(let n=0;n<100 && sent.length<(width===1280?1:2);n++) await new Promise(r=>setTimeout(r,20));
    assert.equal(sent.at(-1).parts.some(p=>p.type==="image"),true);
    await context.close();
  }
  // LAN HTTP disables native capture: the click must still open a working local crop dialog.
  const fallback=await browser.newContext({viewport:{width:1280,height:900},locale:"zh-CN"});
  await fallback.addInitScript(()=>{
    localStorage.setItem("rikkahub:web-auth",JSON.stringify({token:"synthetic-local-only",expiresAt:Date.now()+600000}));
    Object.defineProperty(window,"isSecureContext",{value:false});
  });
  const offline=await fallback.newPage();offline.on("pageerror",e=>errors.push(e.message));
  await offline.goto(`${origin}/c/${conversationId}`);
  await offline.locator('button[aria-haspopup=menu]:has(svg.lucide-plus)').click();
  await offline.getByRole("menuitem",{name:"截图并裁剪"}).click();
  await offline.getByRole("alert").filter({hasText:"普通 HTTP"}).waitFor();
  const fallbackBefore=uploaded.length;
  const png=await offline.evaluate(()=>{const c=document.createElement("canvas");c.width=640;c.height=480;c.getContext("2d").fillRect(0,0,640,480);return c.toDataURL().split(",")[1]});
  await offline.getByLabel("选择截图文件").setInputFiles({name:"local-screenshot.png",mimeType:"image/png",buffer:Buffer.from(png,"base64")});
  await offline.getByTestId("capture-preview").waitFor();assert.equal(uploaded.length,fallbackBefore);
  await offline.getByRole("spinbutton",{name:"宽度百分比"}).fill("50");
  await offline.getByRole("button",{name:"添加到输入框",exact:true}).click();
  await offline.getByRole("dialog").waitFor({state:"hidden"});
  assert.deepEqual(uploaded.at(-1),{width:320,height:480});assert.equal(sent.length,2);
  await fallback.close();
  // Real Chromium tab capture of our synthetic page, never the desktop or a user tab.
  const real = await browser.newContext({viewport:{width:1000,height:800}});
  const source = await real.newPage();
  await source.goto(`${origin}/capture-target.html`);
  await real.addInitScript(() => {
    localStorage.setItem("rikkahub:web-auth",JSON.stringify({token:"synthetic-local-only",expiresAt:Date.now()+600000}));
    const capture=navigator.mediaDevices.getDisplayMedia.bind(navigator.mediaDevices);
    window.realTracks=[];
    navigator.mediaDevices.getDisplayMedia=async options=>{const stream=await capture(options);window.realTracks.push(...stream.getTracks());return stream;};
  });
  const actual=await real.newPage(); actual.on("pageerror",e=>errors.push(e.message));
  await actual.goto(`${origin}/c/${conversationId}`);
  await actual.locator('button[aria-haspopup=menu]:has(svg.lucide-plus)').click();
  await actual.getByRole("menuitem",{name:"截图并裁剪"}).click();
  try { await actual.getByTestId("capture-preview").waitFor({timeout:15000}); } catch(e) { console.log(await actual.locator("body").innerText()); console.log(await actual.evaluate(()=>realTracks.map(t=>({state:t.readyState,label:t.label})))); throw e; }
  assert.equal(await actual.evaluate(()=>realTracks.length>0 && realTracks.every(t=>t.readyState==="ended")),true);
  await actual.screenshot({path:resolve(outputDirectory,"capture-real-tab.png")});
  await actual.getByRole("button",{name:"取消",exact:true}).click();
  await real.close();
  assert.deepEqual(errors,[]);
  console.log(JSON.stringify({cases:3,insecureHttpFallback:true,syntheticCanvasCapture:true,nativeChooserTested:false,nativeChromiumTabCapture:true,localPreview:true,cancel:true,croppedPixels:uploaded,uploadFailureRetained:true,explicitSend:sent.length,pageErrors:0}));
} finally { await browser?.close(); server.closeAllConnections(); await new Promise(done=>server.close(done)); }
