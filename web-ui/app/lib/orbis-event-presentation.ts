import type { MessageDto, OrbisEventMetadata, UIMessagePart } from "../types";

/** Match the phone's metadata-only dispatch. Never guess provenance from user text. */
export function getOrbisEvent(message: Pick<MessageDto, "orbisEvent">): OrbisEventMetadata | null {
  const event = message.orbisEvent;
  if (!event || typeof event !== "object") return null;
  if (![event.recordId, event.source, event.eventId].every((value) => typeof value === "string" && value.trim().length > 0)) return null;
  if (typeof event.receivedAt !== "number") return null;
  if (event.occurredAt != null && typeof event.occurredAt !== "number") return null;
  if (event.collapsed != null && typeof event.collapsed !== "boolean") return null;
  if (event.read != null && typeof event.read !== "boolean") return null;
  return event;
}

/** Same as native UIMessage.toText(): non-text parts keep their newline positions. */
export function orbisEventOriginalText(parts: readonly UIMessagePart[]): string {
  return parts.map((part) => part.type === "text" ? part.text : "").join("\n");
}

export function orbisEventSourceLabel(source: string, language = "zh-CN"): string {
  const chinese = language.toLowerCase().startsWith("zh");
  const labels: Record<string, [string, string]> = {
    rikka_sentinel: ["聊天哨兵", "Chat sentinel"],
    lc_sentinel: ["手机陪伴哨兵", "Phone companion sentinel"],
    self_reminder: ["自主提醒", "Self reminder"],
    legacy_sentinel: ["旧哨兵（归属待核实）", "Legacy sentinel (source unverified)"],
  };
  if (Object.hasOwn(labels, source)) return labels[source][chinese ? 0 : 1];
  if (/^native_sentinel\.[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(source)) {
    return chinese ? "本地自主哨兵" : "Local autonomous sentinel";
  }
  return `${chinese ? "未识别来源" : "Unrecognized source"}: ${source}`;
}

/** Occurrence and receipt are distinct, just as in the native timestamp card. */
export function orbisEventTimePresentation(
  event: OrbisEventMetadata,
  now = new Date(),
  language = "zh-CN",
  timeZone?: string,
): { compactLabel: string; detailLabel: string; dateTime?: string } {
  const chinese = language.toLowerCase().startsWith("zh");
  const source = event.occurredAt != null
    ? (chinese ? "来源提供的触发时间" : "Source-reported trigger time")
    : (chinese ? "接收时间（未提供触发时间）" : "Received time (trigger time unavailable)");
  try {
    const timestamp = event.occurredAt ?? event.receivedAt;
    if (!Number.isSafeInteger(timestamp)) throw new RangeError("invalid_event_time");
    const date = new Date(timestamp);
    const format = new Intl.DateTimeFormat("en-GB", {
      timeZone, year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23",
    });
    const parts = (value: Date) => Object.fromEntries(format.formatToParts(value).map((part) => [part.type, part.value]));
    const then = parts(date);
    const today = parts(now);
    const calendarDay = (value: Record<string, string>) => Date.UTC(+value.year, +value.month - 1, +value.day);
    const difference = calendarDay(today) - calendarDay(then);
    const clock = `${then.hour}:${then.minute}`;
    let compactLabel: string;
    if (difference === 0) compactLabel = clock;
    else if (difference === 86_400_000) compactLabel = `${chinese ? "昨天" : "Yesterday"} ${clock}`;
    else if (chinese) compactLabel = `${then.year === today.year ? "" : `${then.year}年`}${+then.month}月${+then.day}日 ${clock}`;
    else compactLabel = `${then.year === today.year ? "" : `${then.year}-`}${then.month}-${then.day} ${clock}`;
    return {
      compactLabel,
      detailLabel: `${source} · ${then.year}-${then.month}-${then.day} ${clock}:${then.second}`,
      dateTime: date.toISOString(),
    };
  } catch {
    const unavailable = chinese ? "时间不可用" : "Time unavailable";
    return { compactLabel: unavailable, detailLabel: `${source} · ${unavailable}` };
  }
}
