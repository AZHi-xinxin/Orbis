import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ArchivedImage } from "../app/components/message/imported-content";
import { CODE_PREVIEW_SANDBOX, isolatedPreviewDocument } from "../app/lib/preview-policy";
import { isImportedHistory } from "../app/lib/imported-history";
import { readFileSync } from "node:fs";

test("archived remote images have no image/source/preload in initial markup", () => {
  const html = renderToStaticMarkup(createElement(ArchivedImage, { src: "https://example.invalid/private.png", alt: "synthetic" }));
  assert.match(html, /button/);
  assert.doesNotMatch(html, /<img|src=|<link|example.invalid/);
});

test("ChatGPT JSON, HTML and media text retain the same inert renderer gate", () => {
  const imported = isImportedHistory([{ metadata: { import_source: "chatgpt_export_v1" } }]);
  assert.equal(imported, true);
  // The actual Markdown component routes that gate to raw-HTML/KaTeX and image safeguards.
  const renderer = readFileSync(new URL("../app/components/markdown/markdown.tsx", import.meta.url), "utf8");
  assert.match(renderer, /skipHtml=\{imported\}/);
  assert.match(renderer, /imported \? \[\[rehypeKatex, \{ trust: false/);
  assert.match(renderer, /imported \? \{ img:/);
  for (const alt of ['<img src="https://invalid.example/private">', '{"url":"https://invalid.example/private"}']) {
    const html = renderToStaticMarkup(createElement(ArchivedImage, { src: "https://invalid.example/private", alt }));
    assert.doesNotMatch(html, /<(?:img|iframe|script|link)\b/);
  }
});
test("unsupported URLs remain inert references", () => {
  for (const src of ["javascript:alert(1)", "file:///private", "/api/private", "data:image/svg+xml,<svg/>"]) {
    const html = renderToStaticMarkup(createElement(ArchivedImage, { src }));
    assert.doesNotMatch(html, /<img|src=|<button/);
  }
});
test("code previews retain inline code but cannot inherit app origin or network privileges", () => {
  assert.equal(CODE_PREVIEW_SANDBOX, "allow-scripts");
  const source = "<script>/* synthetic local-only preview */</script>";
  const html = isolatedPreviewDocument(source);
  assert.ok(html.endsWith(source));
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /form-action 'none'/);
  assert.match(html, /base-uri 'none'/);
  assert.match(html, /frame-src 'none'/);
  assert.ok(html.indexOf("Content-Security-Policy") < html.indexOf(source));
});
