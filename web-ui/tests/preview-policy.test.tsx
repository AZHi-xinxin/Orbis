import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ArchivedImage } from "../app/components/message/imported-content";
import { CODE_PREVIEW_SANDBOX, isolatedPreviewDocument } from "../app/lib/preview-policy";

test("archived remote images have no image/source/preload in initial markup", () => {
  const html = renderToStaticMarkup(createElement(ArchivedImage, { src: "https://example.invalid/private.png", alt: "synthetic" }));
  assert.match(html, /button/);
  assert.doesNotMatch(html, /<img|src=|<link|example.invalid/);
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
