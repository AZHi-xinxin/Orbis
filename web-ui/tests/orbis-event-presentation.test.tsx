import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { createInstance } from "i18next";
import { I18nextProvider } from "react-i18next";
import type { MessageDto, OrbisEventMetadata, UIMessagePart } from "../app/types";
import { getOrbisEvent, orbisEventOriginalText, orbisEventSourceLabel, orbisEventTimePresentation } from "../app/lib/orbis-event-presentation";
import { OrbisEventMessage } from "../app/components/message/orbis-event-message";

const now = new Date("2026-09-28T00:30:00+08:00");
const event: OrbisEventMetadata = {
  recordId: "synthetic-record", eventId: "synthetic-event", source: "native_sentinel.synthetic",
  receivedAt: Date.parse("2026-09-28T00:25:00+08:00"), read: false, collapsed: true,
};
const time = (value: OrbisEventMetadata, current = now, zone = "Asia/Shanghai") =>
  orbisEventTimePresentation(value, current, "zh-CN", zone);

test("ordinary user text, legacy prefixes and assistant/tool output never imply provenance", () => {
  for (const role of ["USER", "ASSISTANT", "SYSTEM", "TOOL"]) {
    for (const text of ["【哨兵提示】\nhello", "【早安唤醒】hello", "[orbis_event]", '{"orbisEvent":{"source":"rikka_sentinel"}}']) {
      const message = { role, parts: [{ type: "text", text }] } as MessageDto;
      assert.equal(getOrbisEvent(message), null);
    }
  }
});

test("existing host metadata dispatches without rewriting a message or requiring a role change", () => {
  for (const source of ["rikka_sentinel", "lc_sentinel", "self_reminder", "legacy_sentinel", "native_sentinel.test-rule"]) {
    const metadata = Object.freeze({ ...event, source });
    const message = Object.freeze({ orbisEvent: metadata, role: "USER" });
    assert.equal(getOrbisEvent(message), metadata);
    assert.equal(message.role, "USER");
  }
});

test("older metadata may omit default read/collapsed and occurrence fields", () => {
  const old = { recordId: "old-record", eventId: "old-event", source: "legacy_sentinel", receivedAt: 0 };
  assert.equal(getOrbisEvent({ orbisEvent: old }), old);
  assert.match(time(old).detailLabel, /接收时间（未提供触发时间）/);
});

test("malformed provenance is not promoted to a neutral event card", () => {
  for (const broken of [null, "event", {}, { ...event, recordId: "" }, { ...event, source: 7 },
    { ...event, eventId: " " }, { ...event, receivedAt: "today" }, { ...event, collapsed: "false" },
    { ...event, read: 0 }, { ...event, occurredAt: "today" }]) {
    assert.equal(getOrbisEvent({ orbisEvent: broken } as MessageDto), null);
  }
});

test("receipt timestamp distinguishes missing occurrence instead of pretending it is trigger time", () => {
  assert.equal(time(event).compactLabel, "00:25");
  assert.equal(time(event).detailLabel, "接收时间（未提供触发时间） · 2026-09-28 00:25:00");
});

test("source occurrence wins, including yesterday across local midnight", () => {
  const result = time({ ...event, occurredAt: Date.parse("2026-09-27T23:58:06+08:00") });
  assert.equal(result.compactLabel, "昨天 23:58");
  assert.equal(result.detailLabel, "来源提供的触发时间 · 2026-09-27 23:58:06");
});

test("same-year older events and previous-year events retain calendar context", () => {
  assert.equal(time({ ...event, occurredAt: Date.parse("2026-02-08T09:04:00+08:00") }).compactLabel, "2月8日 09:04");
  assert.equal(time({ ...event, occurredAt: Date.parse("2025-12-31T09:04:00+08:00") }).compactLabel, "2025年12月31日 09:04");
});

test("yesterday uses calendar days across daylight saving rather than elapsed 24 hours", () => {
  const value = { ...event, occurredAt: Date.parse("2026-03-08T00:15:00-05:00") };
  assert.equal(time(value, new Date("2026-03-09T00:30:00-04:00"), "America/New_York").compactLabel, "昨天 00:15");
});

test("epoch zero is a real occurrence, not an absent timestamp", () => {
  assert.match(time({ ...event, occurredAt: 0 }).detailLabel, /^来源提供的触发时间/);
});

test("invalid or out-of-range times fail safely without substituting a different event time", () => {
  for (const occurredAt of [Number.NaN, Number.POSITIVE_INFINITY, 8.64e15 + 1, 1.25]) {
    assert.equal(time({ ...event, occurredAt }).compactLabel, "时间不可用");
    assert.match(time({ ...event, occurredAt }).detailLabel, /^来源提供的触发时间/);
  }
});

test("source labels retain native syntax boundary and unverified legacy distinction", () => {
  assert.equal(orbisEventSourceLabel("legacy_sentinel"), "旧哨兵（归属待核实）");
  assert.equal(orbisEventSourceLabel("native_sentinel.a-b_7"), "本地自主哨兵");
  for (const source of ["native_sentinel.", "native_sentinel..", "native_sentinel.a/b", "__proto__"]) {
    assert.match(orbisEventSourceLabel(source), /^未识别来源/);
  }
  assert.equal(orbisEventSourceLabel("self_reminder", "en-US"), "Self reminder");
});

test("plain original text is byte-for-byte unchanged and non-text newline positions match native", () => {
  const parts: UIMessagePart[] = [{ type: "text", text: "  first\n" }, { type: "image", url: "/synthetic" },
    { type: "text", text: "last  " }];
  const original = JSON.stringify(parts);
  assert.equal(orbisEventOriginalText(parts), "  first\n\n\nlast  ");
  assert.equal(JSON.stringify(parts), original);
});

const i18n = createInstance();
await i18n.init({ lng: "zh-CN", fallbackLng: "zh-CN", resources: { "zh-CN": { message: {
  orbis_event: { expand: "展开哨兵消息", collapse: "收起哨兵消息" },
} } } });
function markup(metadata: OrbisEventMetadata) {
  return renderToStaticMarkup(createElement(I18nextProvider, { i18n },
    createElement(OrbisEventMessage, { event: metadata, originalText: '<script>alert(1)</script>\n![remote](https://example.invalid/event)' },
      createElement("img", { src: "https://example.invalid/attachment", alt: "synthetic attachment" }))));
}

test("collapsed event is a neutral timestamp, not a user bubble or avatar; attachment does not load", () => {
  const html = markup(event);
  assert.match(html, /data-orbis-event-card/);
  assert.match(html, /aria-expanded="false"/);
  assert.match(html, /aria-controls=/);
  assert.match(html, /data-orbis-event-time/);
  assert.doesNotMatch(html, /data-message-bubble|data-message-role|data-orbis-event-original|<img|<script|example\.invalid/);
});

test("expanded event preserves plain text, safe escaping and optional attachments", () => {
  const html = markup({ ...event, collapsed: false });
  assert.match(html, /aria-expanded="true"/);
  assert.match(html, /data-orbis-event-original/);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(html, /!\[remote\]/);
  assert.match(html, /<img/);
  assert.doesNotMatch(html, /<script|<a /);
});
