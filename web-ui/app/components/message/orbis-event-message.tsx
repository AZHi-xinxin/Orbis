import * as React from "react";
import { ChevronDown } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { OrbisEventMetadata } from "~/types";
import { orbisEventSourceLabel, orbisEventTimePresentation } from "~/lib/orbis-event-presentation";

interface OrbisEventMessageProps {
  event: OrbisEventMetadata;
  originalText: string;
  children?: React.ReactNode;
}

/** Browser-local disclosure only: never edits history, marks read, or starts a request. */
export function OrbisEventMessage({ event, originalText, children }: OrbisEventMessageProps) {
  const { t, i18n } = useTranslation("message");
  const [collapsed, setCollapsed] = React.useState(event.collapsed ?? true);
  const detailId = React.useId();
  React.useEffect(() => setCollapsed(event.collapsed ?? true), [event.recordId, event.collapsed]);
  const source = orbisEventSourceLabel(event.source, i18n.resolvedLanguage ?? i18n.language);
  const time = orbisEventTimePresentation(event, new Date(), i18n.resolvedLanguage ?? i18n.language);
  const action = t(collapsed ? "orbis_event.expand" : "orbis_event.collapse");

  return (
    <div data-orbis-event-card className="w-full min-w-0">
      <button
        type="button"
        data-orbis-event-toggle
        className="mx-auto flex min-h-11 max-w-full items-center justify-center gap-1.5 rounded-lg px-3 text-xs text-muted-foreground transition-colors hover:bg-muted/30 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        aria-expanded={!collapsed}
        aria-controls={detailId}
        aria-label={`${source} · ${time.detailLabel} · ${action}`}
        title={`${source} · ${time.detailLabel} · ${action}`}
        onClick={() => setCollapsed((value) => !value)}
      >
        <time data-orbis-event-time dateTime={time.dateTime}>{time.compactLabel}</time>
        <ChevronDown aria-hidden="true" className={`size-3.5 shrink-0 transition-transform ${collapsed ? "" : "rotate-180"}`} />
        <span className="sr-only">{action}</span>
      </button>
      <div id={detailId} hidden={collapsed}>
        {!collapsed && (
          <section data-orbis-event-details className="mt-1 rounded-xl border border-border/50 bg-muted/20 px-3 py-3 text-sm">
            <div className="mb-2 flex flex-wrap items-baseline gap-x-2 gap-y-1 text-xs text-muted-foreground">
              <span>{source}</span>
              <span>{time.detailLabel}</span>
            </div>
            {/* External-event text is data. Do not interpret links, Markdown or HTML. */}
            <div data-orbis-event-original className="whitespace-pre-wrap break-words [overflow-wrap:anywhere]">{originalText}</div>
            {children}
          </section>
        )}
      </div>
    </div>
  );
}
