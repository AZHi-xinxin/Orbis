export type Crop = { x: number; y: number; width: number; height: number };
export const wholeFrame: Crop = { x: 0, y: 0, width: 1, height: 1 };

export function boundedCrop(crop: Crop): Crop {
  if (!Object.values(crop).every(Number.isFinite)) throw new Error("截图选区无效");
  const x = Math.max(0, Math.min(.99, crop.x));
  const y = Math.max(0, Math.min(.99, crop.y));
  return { x, y, width: Math.max(.01, Math.min(1 - x, crop.width)),
    height: Math.max(.01, Math.min(1 - y, crop.height)) };
}

export function captureError(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  if (name === "NotAllowedError" || name === "AbortError") return "已取消截图，未添加附件。";
  if (name === "NotReadableError") return "未能读取所选窗口，请重新选择；也可上传已有截图。";
  return "截图未完成，请重试或上传已有截图文件。";
}

/** Invoked by a click. The browser owns the chooser; audio is never requested. */
export async function captureFrame(): Promise<HTMLCanvasElement> {
  if (!window.isSecureContext || !navigator.mediaDevices?.getDisplayMedia) {
    throw new Error("screen_capture_unavailable");
  }
  const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
  const video = document.createElement("video");
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    video.muted = true; video.playsInline = true; video.srcObject = stream;
    // Register before play: a static window may deliver exactly one frame.
    // Waiting for another callback after play resolves would time out on it.
    const firstFrame = video.requestVideoFrameCallback
      ? new Promise<void>(resolve => video.requestVideoFrameCallback(() => resolve()))
      : Promise.resolve();
    await Promise.race([
      Promise.all([video.play(), firstFrame]),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error("capture_timeout")), 10000); }),
    ]);
    const width = video.videoWidth; const height = video.videoHeight;
    if (width < 1 || height < 1) throw new Error("empty_capture");
    const scale = Math.min(1, 4096 / Math.max(width, height), Math.sqrt(16_000_000 / (width * height)));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(width * scale)); canvas.height = Math.max(1, Math.round(height * scale));
    const context = canvas.getContext("2d");
    if (!context) throw new Error("capture_canvas_unavailable");
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    return canvas;
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
    stream.getTracks().forEach(track => track.stop());
    video.pause(); video.srcObject = null;
  }
}

export async function cropFile(source: HTMLCanvasElement, crop: Crop): Promise<File> {
  const area = boundedCrop(crop);
  const left = Math.floor(area.x * source.width), top = Math.floor(area.y * source.height);
  const width = Math.max(1, Math.min(source.width - left, Math.round(area.width * source.width)));
  const height = Math.max(1, Math.min(source.height - top, Math.round(area.height * source.height)));
  const canvas = document.createElement("canvas"); canvas.width = width; canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("capture_canvas_unavailable");
  context.drawImage(source, left, top, width, height, 0, 0, width, height);
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("capture_encode_failed")), "image/png"));
  if (blob.size > 20 * 1024 * 1024) throw new Error("截图过大，请缩小选区后再添加。");
  return new File([blob], `screenshot-${Date.now()}.png`, { type: "image/png" });
}
