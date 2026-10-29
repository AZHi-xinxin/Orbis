import * as React from "react";
import type { ConversationDto } from "~/types/dto";
import api from "~/services/api";
import { useAppStore } from "~/stores";

export function BoundaryPauseCard({ conversation }: { conversation: ConversationDto }) {
  const pause = conversation.boundary;
  const settings = useAppStore(state => state.settings);
  const assistant = settings?.assistants.find(a => a.id === conversation.assistantId);
  const modelId = assistant?.chatModelId || settings?.chatModelId;
  const modelAvailable = Boolean(modelId && settings?.providers.some(p => p.enabled && p.models.some(m => m.id === modelId)) &&
    !settings?.boundaryChoices?.[conversation.assistantId]?.disabledModels.includes(modelId));
  const [text, setText] = React.useState("");
  const [busy, setBusy] = React.useState(false);
  const [status, setStatus] = React.useState("");
  const [now, setNow] = React.useState(() => Date.now());
  React.useEffect(() => {
    if (!pause?.until) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [pause?.until]);
  React.useEffect(() => { setStatus(""); setText(""); }, [conversation.id, pause?.revision]);
  if (!pause) return null;
  const remainingRequests = Math.max(0, 3 - Math.max(0, pause.requestCount ?? 0));
  const seconds = pause.until ? Math.max(0, Math.ceil((pause.until - now) / 1000)) : null;
  const remaining = seconds === null ? null : seconds === 0 ? "已到约定恢复时间" :
    `还剩 ${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分 ${seconds % 60} 秒`;
  return <section aria-label="对话暂停" className="rounded-2xl border bg-muted/30 p-4 space-y-3">
    <p className="font-medium">{pause.scope === "assistant" ? "此助手的所有对话已由对方暂停" : "此对话已由对方暂停"}</p>
    {pause.scope === "assistant" && <p className="text-sm">本助手已有和新建的窗口都适用；其他助手照常使用。</p>}
    {pause.reason && <p className="text-sm whitespace-pre-wrap">{pause.reason}</p>}
    <p className="text-sm text-muted-foreground">{pause.until ? `约定恢复时间：${new Date(pause.until).toLocaleString()}` : "由对方决定何时恢复；聊天记录和草稿保留。"}</p>
    {remaining && <p className="text-sm">{remaining} · 到期自动恢复</p>}
    {pause.allowRequest && <p className="text-sm">剩余申请次数 {remainingRequests}/3</p>}
    {pause.allowRequest && remainingRequests === 0 && !pause.requestUsed && <p className="text-sm">本次暂停的申请次数已用完，到约定时间自动恢复。</p>}
    {pause.allowRequest && remainingRequests > 0 && !pause.requestUsed && modelAvailable && <form onSubmit={async event => {
      event.preventDefault(); if (busy || !text.trim()) return;
      setBusy(true);
      try { const result = await api.post<{ message: string }>(`conversations/${conversation.id}/boundary/request`, { revision: pause.revision, text }, { timeout: false, retry: 0 }); setStatus(result.message); }
      catch { setStatus("暂未收到处理结果，请查看本对话中的申请与答复。"); }
      finally { setBusy(false); }
    }} className="space-y-2">
      <label className="block text-sm">申请继续 · 发到这段对话，由对方答复并决定
        <textarea className="block w-full rounded border bg-background p-2" value={text} onChange={e => setText(e.target.value)} aria-label="继续申请" />
      </label>
      <button disabled={busy || !text.trim()} className="rounded border px-3 py-1 disabled:opacity-50">{busy ? "处理中" : "提交申请"}</button>
    </form>}
    {pause.allowRequest && remainingRequests > 0 && !pause.requestUsed && !modelAvailable && <p className="text-sm">暂无可用模型处理继续申请。</p>}
    <p className="text-sm" role="status">{busy ? "申请处理中，答复会显示在本对话。" : status || (pause.requestUsed ? "继续申请正在处理；答复和工具结果请看本对话。" : "约定与设置：当前 AI → 工具设置 → 拒绝的权利。")}</p>
  </section>;
}
