import * as React from "react";
import { FileArchive, LoaderCircle, Upload } from "lucide-react";
import api from "~/services/api";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "~/components/ui/dialog";
import type { ImportBranch, ImportConversation, ImportPage, ImportStatus } from "~/types/orbis-import";
import { mayConfirmImport, newerImportStatus } from "~/lib/import-policy";
import { IMPORT_SOURCES, IMPORT_SOURCE_INFO, defaultImportPathLabel, importEndpoint, importStorageKeys, readImportSource, validateImportFile, type ImportSource } from "~/lib/import-source";

const SOURCE_KEY = "orbis:web-import-source";
const BUSY = new Set(["created", "uploading", "checking", "importing", "cancelling"]);
const TERMINAL = new Set(["complete", "cancelled", "failed", "expired"]);
const PHASE: Record<string, string> = {
  created: "准备上传", uploading: "正在传到手机", checking: "正在检查导出包", ready: "请预览并选择窗口",
  importing: "正在逐会话导入", cancelling: "正在停止，保留已完成会话", complete: "导入已完成",
  cancelled: "已停止", failed: "未全部完成", expired: "预览已过期，请重新选择文件",
};

function BranchPicker({ source, jobId, row, value, onChange }: {
  source: ImportSource; jobId: string; row: ImportConversation; value: number; onChange: (branch: number) => void;
}) {
  const [branches, setBranches] = React.useState<ImportBranch[]>([]);
  const [next, setNext] = React.useState<number | null>(0);
  const [error, setError] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const load = async () => {
    if (next == null || busy) return;
    setBusy(true); setError(false);
    try {
      const page = await api.get<ImportPage<ImportBranch>>(importEndpoint(source, jobId, "branches"), {
        searchParams: { conversation: row.index, offset: next, limit: 100 }, retry: 0,
      });
      setBranches((old) => [...old, ...page.items]);
      setNext(page.nextOffset ?? null);
    } catch { setError(true); } finally { setBusy(false); }
  };
  return <div className="mt-2 space-y-2">
    <select aria-label={`选择 ${row.title || "会话"} 的路径`} value={value} onFocus={() => { if (!branches.length) void load(); }}
      onChange={(event) => onChange(Number(event.target.value))} className="w-full rounded-md border bg-background px-2 py-1.5 text-sm">
      {!branches.some((branch) => branch.index === row.defaultBranch) && <option value={row.defaultBranch}>{defaultImportPathLabel(row.defaultSelectionReason)}</option>}
      {branches.map((branch) => <option key={branch.index} value={branch.index}>
        路径 {branch.index + 1} · {branch.messageCount} 条消息 · {new Date(branch.updatedAt).toLocaleString()}{branch.isDefault ? " · 默认" : ""}
      </option>)}
    </select>
    {next != null && <Button variant="ghost" size="sm" disabled={busy} onClick={() => void load()}>
      {busy ? "正在读取路径…" : branches.length ? "显示更多路径" : `查看 ${row.branchCount} 条可选路径`}
    </Button>}
    {error && <p className="text-xs text-destructive">路径读取失败，请重试或重新预览。</p>}
  </div>;
}

type ImportDialogProps = {
  open: boolean; onOpenChange: (open: boolean) => void; onImported?: () => void;
};

export function OrbisImportDialog(props: ImportDialogProps) {
  const [source, setSource] = React.useState<ImportSource>(() =>
    readImportSource(typeof sessionStorage === "undefined" ? null : sessionStorage.getItem(SOURCE_KEY)));
  const changeSource = (value: ImportSource) => { sessionStorage.setItem(SOURCE_KEY, value); setSource(value); };
  // A different source owns a different lifecycle; late replies cannot populate another source's preview.
  return <SourceImportDialog key={source} {...props} source={source} onSourceChange={changeSource} />;
}

function SourceImportDialog({ open, onOpenChange, onImported, source, onSourceChange }: ImportDialogProps & {
  source: ImportSource; onSourceChange: (source: ImportSource) => void;
}) {
  const info = IMPORT_SOURCE_INFO[source];
  const { job: JOB_KEY, attempted: ATTEMPT_KEY } = importStorageKeys(source);
  const { settings, currentAssistantId, assistants } = useCurrentAssistant();
  const [status, setStatus] = React.useState<ImportStatus | null>(null);
  const [rows, setRows] = React.useState<ImportConversation[]>([]);
  const [selection, setSelection] = React.useState<Record<number, number>>({});
  const [next, setNext] = React.useState<number | null>(null);
  const [confirmed, setConfirmed] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [recovering, setRecovering] = React.useState(() => typeof sessionStorage !== "undefined" && Boolean(sessionStorage.getItem(JOB_KEY)));
  const [error, setError] = React.useState<string | null>(null);
  const upload = React.useRef<AbortController | null>(null);
  const input = React.useRef<HTMLInputElement | null>(null);
  const notified = React.useRef<string | null>(null);
  const currentJob = React.useRef<string | null>(null);
  const loadingRows = React.useRef(new Set<string>());
  const [attemptedJob, setAttemptedJob] = React.useState<string | null>(() => typeof sessionStorage === "undefined" ? null : sessionStorage.getItem(ATTEMPT_KEY));
  const canImport = settings?.webImportEnabled === true;
  const boundAssistant = assistants.find((assistant) => assistant.id === status?.assistantId);

  const adoptStatus = React.useCallback((value: ImportStatus) => {
    if (value.id === currentJob.current) setStatus((old) => newerImportStatus(old, value));
  }, []);
  const refresh = React.useCallback(async (id: string) => {
    const value = await api.get<ImportStatus>(importEndpoint(source, id), { retry: 0 });
    adoptStatus(value);
    return value;
  }, [adoptStatus, source]);

  React.useEffect(() => {
    if (!open || !canImport) return;
    const id = status?.id ?? sessionStorage.getItem(JOB_KEY);
    currentJob.current = id;
    if (!id) { setRecovering(false); return; }
    setRecovering(true);
    void refresh(id).catch(() => {
      setError("任务已不可读取或已过期。请重新选择文件；已导入的内容会自动跳过。");
      sessionStorage.removeItem(JOB_KEY);
    }).finally(() => setRecovering(false));
  }, [open, canImport, refresh]);

  React.useEffect(() => {
    if (!open || !status || !BUSY.has(status.state)) return;
    const timer = window.setTimeout(() => { void refresh(status.id).catch(() => setError("连接暂时中断。请查看任务状态，不要重复确认导入。")); }, 800);
    return () => window.clearTimeout(timer);
  }, [open, status, refresh]);

  const loadRows = React.useCallback(async (id: string, offset: number, replace: boolean) => {
    const requestKey = `${id}:${offset}`;
    if (loadingRows.current.has(requestKey)) return;
    loadingRows.current.add(requestKey);
    try {
    const page = await api.get<ImportPage<ImportConversation>>(importEndpoint(source, id, "conversations"), {
      searchParams: { offset, limit: 50 }, retry: 0,
    });
    if (id !== currentJob.current) return;
    setRows((old) => replace ? page.items : [...old, ...page.items]);
    setSelection((old) => ({ ...(replace ? {} : old), ...Object.fromEntries(page.items.map((row) => [row.index, row.defaultBranch])) }));
    setNext(page.nextOffset ?? null);
    } finally { loadingRows.current.delete(requestKey); }
  }, [source]);

  React.useEffect(() => {
    if (!status || status.state !== "ready") return;
    setConfirmed(false);
    void loadRows(status.id, 0, true).catch(() => setError("预览读取失败，请重新打开或重新选择文件。"));
  }, [status?.id, status?.state, loadRows]);

  React.useEffect(() => {
    if (status && ["complete", "cancelled", "failed"].includes(status.state) && status.imported > 0 && notified.current !== status.id) {
      notified.current = status.id; onImported?.();
    }
  }, [status, onImported]);

  const choose = async (file: File) => {
    if (!canImport || !currentAssistantId || busy || recovering || (status && BUSY.has(status.state))) return;
    const validationError = validateImportFile(source, file);
    if (validationError) { setError(validationError); return; }
    setBusy(true); setError(null); setConfirmed(false);
    try {
      if (source === "codex" && await file.slice(-1).text() !== "\n") {
        setError("Codex 文件必须是完整且以换行结尾的原始 rollout JSONL；请先完成会话写入再复制，不要手动删减内容。"); return;
      }
      if (status) {
        const stopped = await api.delete<ImportStatus>(importEndpoint(source, status.id), { retry: 0 });
        adoptStatus(stopped);
        if (!TERMINAL.has(stopped.state)) { setError("旧任务尚未停止，请查看任务状态后再选择文件。"); return; }
      }
      currentJob.current = null;
      const created = await api.post<ImportStatus>(importEndpoint(source), { assistantId: currentAssistantId }, { retry: 0 });
      currentJob.current = created.id;
      setRows([]); setSelection({});
      sessionStorage.removeItem(ATTEMPT_KEY); setAttemptedJob(null);
      setStatus(created); sessionStorage.setItem(JOB_KEY, created.id);
      const controller = new AbortController(); upload.current = controller;
      try {
        const ready = await api.uploadImport<ImportStatus>(importEndpoint(source, created.id, "archive"), file, info.contentType, { signal: controller.signal });
        adoptStatus(ready);
      } catch {
        await refresh(created.id).catch(() => setError("上传结果暂时未知。请查看任务状态，未确认前不会导入任何聊天。"));
      } finally { upload.current = null; }
    } catch { setError("无法开始导入。请确认手机 Web 连接正常且没有其他导入任务；如果你已开启密码保护，请先登录。不开启密码也可以导入。"); }
    finally { setBusy(false); }
  };

  const commit = async () => {
    if (!status || busy || !mayConfirmImport(status, confirmed, Object.keys(selection).length, attemptedJob)) return;
    // An unknown POST outcome must never become a second confirm after polling or a page reload.
    sessionStorage.setItem(ATTEMPT_KEY, status.id); setAttemptedJob(status.id);
    setBusy(true); setError(null);
    try {
      adoptStatus(await api.post<ImportStatus>(importEndpoint(source, status.id, "commit"), {
        reviewToken: status.reviewToken, confirmed: true,
        selections: Object.entries(selection).map(([conversation, branch]) => ({ conversation: Number(conversation), branch })),
      }, { retry: 0 }));
    } catch {
      await refresh(status.id).catch(() => setError("确认结果暂时未知。只查看任务状态，不要重复点击确认。"));
    } finally { setBusy(false); }
  };

  const cancel = async () => {
    if (!status) return;
    upload.current?.abort(); setBusy(true);
    try { adoptStatus(await api.delete<ImportStatus>(importEndpoint(source, status.id), { retry: 0 })); }
    catch { setError("停止结果暂时未知，请重新查看状态；已完成的会话不会被删除。"); }
    finally { setBusy(false); }
  };

  const changeSource = async (value: ImportSource) => {
    if (source === value || busy || recovering || (status && BUSY.has(status.state))) return;
    setBusy(true); setError(null);
    try {
      if (status?.state === "ready") {
        const stopped = await api.delete<ImportStatus>(importEndpoint(source, status.id), { retry: 0 });
        adoptStatus(stopped);
        if (!TERMINAL.has(stopped.state)) { setError("当前任务尚未停止，暂时不能切换来源。"); return; }
        sessionStorage.removeItem(JOB_KEY);
      }
      onSourceChange(value);
    } catch { setError("未确认当前预览已停止，请查看状态后再切换来源。已有聊天不会被删除。"); }
    finally { setBusy(false); }
  };

  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent className="flex max-h-[90svh] flex-col sm:max-w-2xl">
      <DialogHeader><DialogTitle className="flex items-center gap-2"><FileArchive className="size-5 text-primary" />导入 {info.label} 聊天</DialogTitle>
        <DialogDescription>上传 → 预览 → 人工确认 → 导入结果。文件只发到这台 Orbis 手机，不发送给模型。</DialogDescription></DialogHeader>
      <div className="min-h-0 space-y-4 overflow-auto pr-1">
        <div className="space-y-2"><label htmlFor="orbis-import-source" className="text-sm font-medium">聊天记录来源</label>
          <select id="orbis-import-source" value={source} disabled={busy || recovering || Boolean(status && BUSY.has(status.state))}
            onChange={(event) => void changeSource(readImportSource(event.target.value))} className="w-full rounded-md border bg-background px-3 py-2 text-sm">
            {IMPORT_SOURCES.map((value) => <option key={value} value={value}>{IMPORT_SOURCE_INFO[value].label}</option>)}
          </select>
          <p className="text-xs leading-relaxed text-muted-foreground">{info.description}</p>
          {status?.state === "ready" && <p className="text-xs text-muted-foreground">切换来源会清理当前未导入的预览，不会删除已有聊天。</p>}
        </div>
        {!canImport && <p className="rounded-lg bg-muted p-3 text-sm">正在等待手机连接。若已开启 Web 密码保护，请完成登录；不开启密码也可上传导入。</p>}
        <input ref={input} type="file" accept={info.accept} aria-label={`选择 ${info.label} 导入文件`} className="hidden" onChange={(event) => { const file = event.target.files?.[0]; if (file) void choose(file); event.target.value = ""; }} />
        <Button variant="outline" disabled={!canImport || busy || recovering || Boolean(status && BUSY.has(status.state))} onClick={() => input.current?.click()}>
          <Upload className="size-4" />{recovering ? "正在恢复导入任务…" : info.fileLabel}</Button>
        <p className="text-xs text-muted-foreground">接收身份：{boundAssistant?.name || assistants.find((assistant) => assistant.id === currentAssistantId)?.name || "当前身份"}。{info.limit}</p>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        {status && <section className="space-y-2 rounded-xl border p-3" aria-live="polite">
          <p className="flex items-center gap-2 font-medium">{BUSY.has(status.state) && <LoaderCircle className="size-4 animate-spin" />}{PHASE[status.state] ?? status.state}</p>
          {["created", "uploading", "checking"].includes(status.state) && <p className="text-sm text-muted-foreground">已接收 {(status.uploadedBytes / 1024 / 1024).toFixed(1)} MiB · 上传和检查不会写入聊天。</p>}
          {status.state === "importing" && <p>已处理 {status.completed} / {status.total} 个会话</p>}
          {status.error && <p className="text-sm text-destructive">{status.error === "insufficient_storage" ? "手机剩余存储空间不足，未满足导入安全预留。请先腾出空间再重试，不要删除尚未备份的聊天。" : status.error === "archive_too_large" ? "文件超过大小限制。" : status.error === "invalid_archive" ? "文件格式不兼容或已损坏，没有覆盖现有聊天。" : "任务未全部完成。已完成会话保留，可重新选择原文件，已有内容会跳过。"}</p>}
          {status.state === "ready" && <p className="text-sm">发现 {status.conversationCount} 个窗口。{info.preview}</p>}
          {status.warnings?.map((warning, index) => <p key={index} className="text-xs text-muted-foreground">{warning}</p>)}
          {["complete", "failed", "cancelled"].includes(status.state) && <p className="text-sm">导入 {status.imported} · 已有内容跳过 {status.skipped} · 未导入 {status.failed} · 消息 {status.messages}</p>}
          {status.attachmentReferences > 0 && <p className="text-xs text-muted-foreground">有 {status.attachmentReferences} 个未还原附件 / 媒体引用，消息中保留说明；不会自动下载外部内容。</p>}
          {(status.skippedSummaries ?? 0) > 0 && <p className="text-xs text-muted-foreground">本次新增会话跳过 {status.skippedSummaries} 条内部摘要；未作为聊天或系统提示导入，原件不变。</p>}
          {status.rows.map((row) => <p key={row.conversation} className="text-xs">窗口 {row.conversation + 1}：{row.state === "imported" ? "已导入" : row.state === "skipped" ? "已有路径，已跳过" : "单条消息过大，该窗口未导入，原文未截断"}</p>)}
        </section>}
        {status?.state === "ready" && <>
          <div className="space-y-2">{rows.map((row) => <section key={row.index} className="rounded-lg border p-3">
            <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={selection[row.index] != null}
              onChange={(event) => { setConfirmed(false); setSelection((old) => { const value = { ...old }; if (event.target.checked) value[row.index] = row.defaultBranch; else delete value[row.index]; return value; }); }} />
              <span className="min-w-0 break-words font-medium">{row.title || `窗口 ${row.index + 1}`}</span></label>
            <p className="mt-1 text-xs text-muted-foreground">{source === "deepseek" ? `${row.totalNodes} 个源节点 · ${row.branchPointCount} 处分叉 · ${row.branchCount} 条可选路径` : `${row.messageCount} 条消息 · ${defaultImportPathLabel(row.defaultSelectionReason)}`}</p>
            {(row.omittedSummaryCount ?? 0) > 0 && <p className="text-xs text-muted-foreground">跳过 {row.omittedSummaryCount} 条内部摘要，不作为聊天或系统提示导入。</p>}
            {selection[row.index] != null && source === "deepseek" && <BranchPicker key={`${status.id}:${row.index}`} source={source} jobId={status.id} row={row} value={selection[row.index]} onChange={(branch) => { setConfirmed(false); setSelection((old) => ({ ...old, [row.index]: branch })); }} />}
          </section>)}</div>
          {next != null && <Button variant="outline" onClick={() => { setConfirmed(false); void loadRows(status.id, next, false).catch(() => setError("读取更多窗口失败，请重试。")); }}>显示更多窗口（仅已显示且勾选的窗口会导入）</Button>}
          <p className="text-xs text-muted-foreground">导入内容以当前来源说明为准。继续聊天可能发送长历史并产生较高模型用量；请先查看上下文。历史中的工具记录不会执行，外部内容不会自动下载。</p>
          <label className="flex items-start gap-2 text-sm"><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
            {info.confirmation}</label>
          {attemptedJob === status.id && <p role="status" className="text-sm text-muted-foreground">这次确认已发出，不会重发。请查看任务状态；若仍未开始，请先停止旧任务，再重新预览导入。已完成路径会自动跳过。</p>}
          <Button disabled={busy || !mayConfirmImport(status, confirmed, Object.keys(selection).length, attemptedJob)} onClick={() => void commit()}>确认导入 {Object.keys(selection).length} 个所选{source === "deepseek" ? "窗口路径" : "窗口"}</Button>
        </>}
      </div>
      <div className="flex justify-end gap-2 border-t pt-3">
        {status && <Button variant="ghost" onClick={() => void refresh(status.id).catch(() => setError("暂时无法查看任务状态。"))}>查看任务状态</Button>}
        {status && (BUSY.has(status.state) || status.state === "ready") && <Button variant="outline" onClick={() => void cancel()}>停止并清理临时文件</Button>}
        <Button variant="ghost" onClick={() => onOpenChange(false)}>关闭</Button>
      </div>
    </DialogContent>
  </Dialog>;
}
