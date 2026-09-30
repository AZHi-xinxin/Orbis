import { useEffect, useState } from "react";
import { Archive, Loader2 } from "lucide-react";
import api, { ApiError } from "~/services/api";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "~/components/ui/dialog";

interface Boundary { number: number; role: string; createdAt: string; text: string; branchCount: number }
interface ContextInfo { conversationId: string; totalCount: number; boundaries: Boundary[] }
interface ContextPreview {
  previewId: string; requestedArchiveCount: number; archivedCount: number; keptCount: number;
  beforeTokens: number; afterTokens: number; summaryText: string; boundaries: Boundary[];
}
interface Props {
  conversationId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onCommitted?: () => void;
}

/** The server holds the immutable preview. The browser never sends replacement message data. */
export function OrbisContextDialog({ conversationId, open, onOpenChange, onCommitted }: Props) {
  const [info, setInfo] = useState<ContextInfo | null>(null);
  const [end, setEnd] = useState(1);
  const [summary, setSummary] = useState("");
  const [preview, setPreview] = useState<ContextPreview | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [archiveId, setArchiveId] = useState<string | null>(null);
  const [pendingTicket, setPendingTicket] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !conversationId || pendingTicket) return;
    const controller = new AbortController();
    setInfo(null); setError(null); setPreview(null); setArchiveId(null);
    setSummary(""); setConfirmed(false); setTarget(conversationId); setBusy(true);
    api.get<ContextInfo>(`orbis/context/${conversationId}`, { signal: controller.signal, retry: 0 })
      .then((result) => { if (!controller.signal.aborted) {
        setInfo(result); setEnd(Math.max(1, result.totalCount - 32));
      } })
      .catch((failure: unknown) => { if (!controller.signal.aborted) setError(contextError(failure)); })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
    // A pending confirmation belongs to its original window even if the active route changes.
  }, [open, conversationId]);

  const validRange = info !== null && Number.isInteger(end) && end >= 1 && end <= info.totalCount;
  const locked = busy || pendingTicket !== null || archiveId !== null;
  function invalidate() { setPreview(null); setConfirmed(false); setError(null); }

  async function prepare() {
    if (!target || !validRange || locked) return;
    setBusy(true); invalidate();
    try {
      setPreview(await api.post<ContextPreview>(`orbis/context/${target}/preview`,
        { archiveThroughCount: end, summary }, { timeout: 120_000, retry: 0 }));
    } catch (failure) { setError(contextError(failure)); }
    finally { setBusy(false); }
  }

  async function apply() {
    const ticket = pendingTicket ?? preview?.previewId;
    if (!target || !ticket || busy || (!confirmed && pendingTicket === null)) return;
    setBusy(true); setError(null); setPendingTicket(ticket);
    try {
      const result = await api.post<{ status: string; archiveId: string }>(`orbis/context/${target}/apply`,
        { previewId: ticket, confirmed: true }, { timeout: 180_000, retry: 0 });
      if (result.status !== "committed") throw new Error("结果尚未确认，请用同一次预览重试确认。");
      setArchiveId(result.archiveId); setPendingTicket(null); setPreview(null);
      onCommitted?.();
    } catch (failure) {
      if (failure instanceof ApiError && [400, 404, 409].includes(failure.code)) {
        setPendingTicket(null); setPreview(null); setConfirmed(false);
        setError(`${contextError(failure)} 请先查看当前窗口，再重新预览。`);
      } else {
        setError("连接中断或结果尚未确认。不要重复选择范围；下面的重试会查询／确认同一次操作，不会再次整理。");
      }
    } finally { setBusy(false); }
  }

  return <Dialog open={open} onOpenChange={(value) => { if (!busy && !pendingTicket) onOpenChange(value); }}>
    <DialogContent className="max-h-[88svh] overflow-y-auto rounded-3xl sm:max-w-2xl" showCloseButton={!busy && !pendingTicket}>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2"><Archive className="size-5 text-blue-500" />上下文整理</DialogTitle>
        <DialogDescription>先保存完整原文，再缩小当前窗口发给模型的历史。超限、无法回复时也能使用。</DialogDescription>
      </DialogHeader>
      <div className="rounded-2xl border border-blue-200 bg-blue-50/60 p-4 text-sm leading-6 dark:border-blue-900 dark:bg-blue-950/30">
        所有原文、替代分支和已有附件引用，会保存在独立的「原文存档」聊天中。
        当前窗口保留所选终点之后的原文和整理说明。这不是永久删除，也不会自动生成摘要、请求模型或继续旧队列。
      </div>
      {!conversationId && !target && <p>请先打开需要整理的聊天窗口。</p>}
      {info && !archiveId && <>
        <label className="grid gap-2 text-sm font-medium">从第 1 条整理到第几条？（共 {info.totalCount.toLocaleString()} 条）
          <input className="h-11 rounded-xl border bg-background px-3" type="number" min={1} max={info.totalCount}
            value={Number.isNaN(end) ? "" : end} disabled={locked}
            onChange={(event) => { setEnd(event.target.valueAsNumber); invalidate(); }} />
        </label>
        <p className="text-xs leading-5 text-muted-foreground">默认留下最近 32 条，你可以自行改终点。一次整理整个节点，包含该节点的替代分支。
          工具调用和结果必须成组保留，实际边界以预览为准。</p>
        <label className="grid gap-2 text-sm font-medium">给后续对话的摘要／交接说明（可留空）
          <textarea className="min-h-28 rounded-xl border bg-background p-3 font-normal" maxLength={20_000}
            value={summary} disabled={locked} placeholder="可粘贴你认可的摘要。留空只写明归档操作，不虚构历史内容。"
            onChange={(event) => { setSummary(event.target.value); invalidate(); }} />
        </label>
        <Button variant="outline" disabled={!validRange || locked} onClick={() => void prepare()}>预览范围与用量 · 不修改记录</Button>
      </>}
      {preview && <section className="space-y-4 rounded-2xl border p-4" aria-label="整理预览">
        <div className="grid grid-cols-2 gap-3 text-sm">
          <div>移出当前上下文 <strong>{preview.archivedCount.toLocaleString()}</strong> 条</div>
          <div>保留原文 <strong>{preview.keptCount.toLocaleString()}</strong> 条</div>
          <div>整理前估算<br /><strong>{preview.beforeTokens.toLocaleString()}</strong> tokens</div>
          <div>整理后估算<br /><strong>{preview.afterTokens.toLocaleString()}</strong> tokens</div>
        </div>
        <p className="text-xs leading-5 text-muted-foreground">这是本地文字与工具记录估算，不包含完整系统提示／工具定义开销，不是供应商实测，也不是模型容量保证。
          {preview.archivedCount !== preview.requestedArchiveCount && " 为保留完整工具组，实际归档终点已提前；请核对下面边界。"}</p>
        {preview.afterTokens >= preview.beforeTokens && <p className="text-sm text-amber-700 dark:text-amber-300">这次整理没有减少估算用量，请增大范围或缩短说明。</p>}
        <div className="space-y-2">{preview.boundaries.map((row) => <div key={row.number} className="rounded-xl bg-muted/60 p-3 text-sm">
          <div className="text-xs text-muted-foreground">第 {row.number} 条 · {row.role === "USER" ? "人类" : row.role === "ASSISTANT" ? "AI" : row.role} · {row.createdAt} · {row.branchCount} 个分支</div>
          <p className="mt-1 whitespace-pre-wrap break-words">{row.text}</p>
          <div className="mt-1 text-xs text-blue-600 dark:text-blue-300">{row.number <= preview.archivedCount ? "保存在原文存档，不再发送" : "继续保留并发送"}</div>
        </div>)}</div>
        <details className="text-sm"><summary className="cursor-pointer">查看将插入的整理说明</summary>
          <p className="mt-2 max-h-48 overflow-y-auto whitespace-pre-wrap break-words rounded-xl bg-muted p-3">{preview.summaryText}</p>
        </details>
        <label className="flex items-start gap-2 text-sm leading-6"><input className="mt-1" type="checkbox" disabled={locked}
          checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />我已核对范围：保存独立原文存档，并整理当前窗口。</label>
        {!pendingTicket && <Button disabled={!confirmed || busy} onClick={() => void apply()}>确认保存原文并整理</Button>}
      </section>}
      {pendingTicket && <Button disabled={busy} onClick={() => void apply()}>重试确认同一次操作</Button>}
      {busy && <p role="status" className="flex items-center gap-2 text-sm"><Loader2 className="size-4 animate-spin" />正在处理，请保持手机与网页连接…</p>}
      {error && <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
      {archiveId && <div role="status" className="space-y-3 rounded-2xl border border-blue-200 p-4">
        <p>整理完成，原文存档已经保存。请核对当前窗口用量，留足模型余量后再继续聊天；没有发送新消息，也没有续发旧队列。</p>
        <a href={`/c/${encodeURIComponent(archiveId)}`} className="text-blue-600 underline dark:text-blue-300">查看独立原文存档</a>
        <p className="text-xs text-muted-foreground">原文存档仍然很长，直接在存档里发送可能再次超限；请在整理后的窗口继续。</p>
      </div>}
      <Button variant="ghost" disabled={busy || pendingTicket !== null} onClick={() => onOpenChange(false)}>关闭</Button>
    </DialogContent>
  </Dialog>;
}

function contextError(failure: unknown): string {
  if (failure instanceof ApiError && failure.code === 404) return "该整理入口或预览暂不可用，请确认手机已升级并重新打开预览。";
  if (failure instanceof ApiError && failure.code === 401) return "请先在手机开启 Web 访问密码，并在网页登录。";
  return failure instanceof Error ? failure.message : "未能完成，请检查手机连接后重试。";
}
