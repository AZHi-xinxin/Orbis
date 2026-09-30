import * as React from "react";

export const ImportedContentContext = React.createContext(false);
export const useImportedContent = () => React.useContext(ImportedContentContext);

/** Keep archive URLs inert until this exact image is explicitly requested. */
export function ArchivedImage({ src, alt }: { src?: string | Blob; alt?: string }) {
  const [acceptedSource, setAcceptedSource] = React.useState<string | null>(null);
  const url = typeof src === "string" && /^https?:\/\//i.test(src) ? src : null;
  if (!url) return <span className="rounded border px-2 py-1 text-xs text-muted-foreground">[历史图片引用{alt ? `：${alt}` : ""}]</span>;
  if (acceptedSource === url) return <img src={url} alt={alt ?? "历史图片"} referrerPolicy="no-referrer" loading="lazy" />;
  return <button type="button" onClick={() => setAcceptedSource(url)} className="rounded-lg border px-3 py-2 text-left text-xs text-muted-foreground">
    历史图片未联网加载{alt ? ` · ${alt}` : ""} — 点击从原地址加载
  </button>;
}
