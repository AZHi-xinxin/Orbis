/** srcDoc is deliberately opaque-origin; it must never share Orbis credentials. */
export const CODE_PREVIEW_SANDBOX = "allow-scripts";
export function isolatedPreviewDocument(code: string): string {
  return `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-src 'none'">${code}`;
}
