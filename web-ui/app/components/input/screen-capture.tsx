import * as React from "react";
import { Button } from "~/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "~/components/ui/dialog";
import { boundedCrop, captureError, captureFrame, cropFile, wholeFrame, type Crop } from "~/lib/screen-capture";

export function useScreenCapture(draftKey: string | null | undefined, enabled: boolean, attach: (file: File) => Promise<boolean>) {
  const [frame, setFrame] = React.useState<HTMLCanvasElement | null>(null);
  const [crop, setCrop] = React.useState<Crop>(wholeFrame);
  const [busy, setBusy] = React.useState(false);
  const [notice, setNotice] = React.useState<string | null>(null);
  const mounted = React.useRef(true);
  const generation = React.useRef(0);
  const activeKey = React.useRef(draftKey);
  const enabledRef = React.useRef(enabled); enabledRef.current = enabled;
  const dragStart = React.useRef<{ x: number; y: number } | null>(null);
  // Invalidate during render too: a resolved picker must never target a different chat.
  if (activeKey.current !== draftKey) { activeKey.current = draftKey; generation.current++; }
  React.useEffect(() => {
    setFrame(null); setBusy(false); setNotice(null); setCrop(wholeFrame);
  }, [draftKey]);
  React.useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  const close = () => { if (busy) return; generation.current++; setFrame(null); setNotice(null); };
  const start = async () => {
    if (!enabledRef.current || busy) return;
    if (!window.isSecureContext || !navigator.mediaDevices?.getDisplayMedia) {
      setNotice("当前地址或浏览器不支持截图。请使用 HTTPS 或本机 localhost 的受支持桌面浏览器；也可以上传已有截图。"); return;
    }
    const attempt = ++generation.current;
    setBusy(true); setNotice(null);
    try {
      const captured = await captureFrame();
      if (mounted.current && attempt === generation.current && enabledRef.current) { setFrame(captured); setCrop(wholeFrame); }
    } catch (error) {
      if (mounted.current && attempt === generation.current) setNotice(captureError(error));
    } finally { if (mounted.current && attempt === generation.current) setBusy(false); }
  };
  const add = async () => {
    if (!frame || busy || !enabledRef.current) return;
    const attempt = generation.current;
    setBusy(true);
    try {
      const file = await cropFile(frame, crop);
      if (!mounted.current || attempt !== generation.current || !enabledRef.current) return;
      if (await attach(file)) { if (mounted.current && attempt === generation.current) setFrame(null); }
      else if (mounted.current && attempt === generation.current) setNotice("附件未添加，截图仍保留在预览中，可重试。");
    } catch { if (mounted.current && attempt === generation.current) setNotice("截图无法添加，请缩小选区后重试。"); }
    finally { if (mounted.current && attempt === generation.current) setBusy(false); }
  };
  const preview = React.useMemo(() => frame?.toDataURL("image/png"), [frame]);
  const point = (event: React.PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)), y: Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)) };
  };
  return { start, busy, notice: !frame ? notice : null, dialog: (
    <Dialog open={frame !== null} onOpenChange={open => { if (!open) close(); }}>
      <DialogContent className="sm:max-w-3xl max-h-[90dvh] overflow-y-auto" showCloseButton={!busy}>
        <DialogTitle>截图预览</DialogTitle>
        <DialogDescription>拖动框选需要的部分。确认后添加到输入框，发送时才交给 AI。</DialogDescription>
        {frame && preview && <div className="flex justify-center bg-black/5 rounded-xl p-2">
          <div className="relative select-none touch-none cursor-crosshair" data-testid="capture-preview"
            onPointerDown={event => { if (!busy) { dragStart.current = point(event); event.currentTarget.setPointerCapture(event.pointerId); } }}
            onPointerMove={event => { if (dragStart.current && !busy) { const p = point(event), a = dragStart.current;
              setCrop(boundedCrop({ x: Math.min(a.x, p.x), y: Math.min(a.y, p.y), width: Math.abs(a.x - p.x), height: Math.abs(a.y - p.y) })); } }}
            onPointerUp={() => { dragStart.current = null; }} onPointerCancel={() => { dragStart.current = null; }}>
            <img src={preview} alt="本次截图预览" draggable={false} className="block max-h-[48dvh] max-w-full object-contain rounded" />
            <div data-testid="capture-selection" className="pointer-events-none absolute border-2 border-blue-500 bg-blue-400/10"
              style={{ left: `${crop.x * 100}%`, top: `${crop.y * 100}%`, width: `${crop.width * 100}%`, height: `${crop.height * 100}%` }} />
          </div>
        </div>}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
          {([['x','左边'],['y','上边'],['width','宽度'],['height','高度']] as const).map(([key,label]) =>
            <label className="text-xs" key={key}>{label} %<input aria-label={`${label}百分比`} type="number" min={key === 'x' || key === 'y' ? 0 : 1} max={100}
              disabled={busy} value={Math.round(crop[key] * 100)} className="mt-1 block w-full rounded border p-2"
              onChange={event => setCrop(boundedCrop({ ...crop, [key]: (Number(event.target.value) || 0) / 100 }))} /></label>)}
        </div>
        {notice && <p role="alert" className="text-sm text-destructive">{notice}</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="ghost" disabled={busy} onClick={() => setCrop(wholeFrame)}>恢复整张</Button>
          <Button variant="outline" disabled={busy} onClick={close}>取消</Button>
          <Button disabled={busy || !enabled} onClick={() => void add()}>{busy ? "正在添加…" : "添加到输入框"}</Button>
        </div>
      </DialogContent>
    </Dialog>
  ) };
}
