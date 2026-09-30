import * as React from "react";

import { useNavigate, useParams } from "react-router";

import {
  ConversationQuickJump,
  getConversationMessageAnchorId,
} from "~/components/conversation-quick-jump";
import { ConversationSidebar } from "~/components/conversation-sidebar";
import {
  Conversation,
  ConversationContent,
  ConversationEmptyState,
  ConversationScrollButton,
} from "~/components/extended/conversation";
import { ChatInput } from "~/components/input/chat-input";
import { ChatMessage } from "~/components/message/chat-message";
import { Button } from "~/components/ui/button";
import { Drawer, DrawerContent } from "~/components/ui/drawer";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "~/components/ui/resizable";
import { TypingIndicator } from "~/components/ui/typing-indicator";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "~/components/ui/sidebar";
import { useIsMobile } from "~/hooks/use-mobile";
import { toConversationSummaryUpdate, useConversationList } from "~/hooks/use-conversation-list";
import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useFolders } from "~/hooks/use-folders";
import { useCurrentModel } from "~/hooks/use-current-model";
import { getAssistantDisplayName, getModelDisplayName } from "~/lib/display";
import { convertConversationToMarkdown, downloadMarkdown } from "~/lib/export-markdown";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import { subscribeLiveStream } from "~/services/live-stream";
import { applyConversationNodeUpdate } from "~/lib/conversation-stream";
import { useChatInputStore, useAppStore } from "~/stores";
import { WorkbenchHost } from "~/components/workbench/workbench-host";
import {
  useWorkbench,
  useWorkbenchController,
  WorkbenchProvider,
} from "~/components/workbench/workbench-context";
import {
  type ConversationDto,
  type MessageNodeDto,
  type MessageDto,
  type ConversationNodeUpdateEventDto,
  type ConversationErrorEventDto,
  type ConversationSnapshotEventDto,
  type ProviderModel,
  type Settings,
  type UIMessagePart,
} from "~/types";
import { MessageSquare } from "lucide-react";
import Logo from "~/components/logo";
import { OrbisWebActions } from "~/components/orbis-web-actions";
import { welcomeName } from "~/lib/orbis-appearance";
import { historyWindow, olderHistoryEnd, newerHistoryEnd } from "~/lib/history-window";
import { useStickToBottomContext } from "use-stick-to-bottom";
import type { PanelImperativeHandle } from "react-resizable-panels";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { v4 as uuidv4 } from "uuid";
import i18n from "~/i18n";

type ConversationStreamEvent =
  | ConversationSnapshotEventDto
  | ConversationNodeUpdateEventDto
  | ConversationErrorEventDto;

interface SelectedNodeMessage {
  node: MessageNodeDto;
  message: MessageNodeDto["messages"][number];
}
type ConversationSummaryUpdater = (update: ReturnType<typeof toConversationSummaryUpdate>) => void;

const EDIT_DRAFT_ATTACHMENT_MARK = "__from_message_attachment";
const EDIT_DRAFT_SOURCE_INDEX = "__from_message_source_index";
const EMPTY_INPUT_ATTACHMENTS: UIMessagePart[] = [];
const EMPTY_SUGGESTIONS: string[] = [];

interface EditDraft {
  text: string;
  attachments: UIMessagePart[];
  sourceParts: UIMessagePart[];
  textPartIndex: number | null;
}

interface EditingSession {
  messageId: string;
  sourceParts: UIMessagePart[];
  textPartIndex: number | null;
}

function createHomeDraftId() {
  return `home-${uuidv4()}`;
}

function truncatePreviewText(value: string, maxLength = 48): string {
  if (value.length <= maxLength) {
    return value;
  }

  return `${value.slice(0, maxLength)}...`;
}

function getQuickJumpPreview(
  message: MessageDto,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const textPreview = message.parts
    .filter((part): part is Extract<UIMessagePart, { type: "text" }> => part.type === "text")
    .map((part) => part.text.trim())
    .find((text) => text.length > 0);

  if (textPreview) {
    return truncatePreviewText(textPreview.replace(/\s+/g, " "));
  }

  const fallbackPart = message.parts.find(Boolean);
  if (!fallbackPart) return t("conversations.preview.empty_message");

  switch (fallbackPart.type) {
    case "image":
      return t("conversations.preview.image");
    case "video":
      return t("conversations.preview.video");
    case "audio":
      return t("conversations.preview.audio");
    case "document":
      return fallbackPart.fileName.trim().length > 0
        ? t("conversations.preview.document_with_name", {
            name: truncatePreviewText(fallbackPart.fileName.trim(), 32),
          })
        : t("conversations.preview.document");
    case "reasoning":
      return fallbackPart.reasoning.trim().length > 0
        ? truncatePreviewText(fallbackPart.reasoning.trim().replace(/\s+/g, " "))
        : t("conversations.preview.thinking");
    case "tool":
      return fallbackPart.toolName.trim().length > 0
        ? t("conversations.preview.tool_with_name", {
            name: truncatePreviewText(fallbackPart.toolName.trim(), 32),
          })
        : t("conversations.preview.tool_call");
    case "text":
      return t("conversations.preview.empty_message");
  }
}

function isAttachmentPart(
  part: UIMessagePart,
): part is Extract<UIMessagePart, { type: "image" | "video" | "audio" | "document" }> {
  return (
    part.type === "image" ||
    part.type === "video" ||
    part.type === "audio" ||
    part.type === "document"
  );
}

function getLastTextPartIndex(parts: UIMessagePart[]): number | null {
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    if (parts[index]?.type === "text") {
      return index;
    }
  }

  return null;
}

function getDraftSourceIndex(part: UIMessagePart): number | null {
  const value = part.metadata?.[EDIT_DRAFT_SOURCE_INDEX];
  return typeof value === "number" ? value : null;
}

function toEditDraft(message: MessageDto): EditDraft | null {
  const textPartIndex = getLastTextPartIndex(message.parts);
  const text =
    textPartIndex !== null && message.parts[textPartIndex]?.type === "text"
      ? message.parts[textPartIndex].text
      : "";

  const attachments = message.parts.flatMap((part, index) => {
    if (!isAttachmentPart(part)) return [];

    return [
      {
        ...part,
        metadata: {
          ...part.metadata,
          [EDIT_DRAFT_ATTACHMENT_MARK]: true,
          [EDIT_DRAFT_SOURCE_INDEX]: index,
        },
      },
    ];
  });

  if (text.trim().length === 0 && attachments.length === 0) {
    return null;
  }

  return {
    text,
    attachments,
    sourceParts: message.parts,
    textPartIndex,
  };
}

function shouldDeleteAttachmentFileOnRemove(part: UIMessagePart): boolean {
  if (!part.metadata) return true;

  return part.metadata[EDIT_DRAFT_ATTACHMENT_MARK] !== true;
}

function stripEditDraftMetadata(parts: UIMessagePart[]): UIMessagePart[] {
  return parts.map((part) => {
    if (!part.metadata) {
      return part;
    }

    const hasEditMark =
      EDIT_DRAFT_ATTACHMENT_MARK in part.metadata || EDIT_DRAFT_SOURCE_INDEX in part.metadata;
    if (!hasEditMark) {
      return part;
    }

    const nextMetadata = { ...part.metadata };
    delete nextMetadata[EDIT_DRAFT_ATTACHMENT_MARK];
    delete nextMetadata[EDIT_DRAFT_SOURCE_INDEX];

    return {
      ...part,
      metadata: Object.keys(nextMetadata).length > 0 ? nextMetadata : undefined,
    };
  });
}

function buildEditedParts(session: EditingSession, draftParts: UIMessagePart[]): UIMessagePart[] {
  const textPart = draftParts.find(
    (part): part is Extract<UIMessagePart, { type: "text" }> => part.type === "text",
  );
  const editedText = textPart?.text ?? "";

  const retainedAttachmentIndexes = new Set<number>();
  const appendedAttachments: UIMessagePart[] = [];

  draftParts.forEach((part) => {
    if (!isAttachmentPart(part)) return;

    if (part.metadata?.[EDIT_DRAFT_ATTACHMENT_MARK] === true) {
      const sourceIndex = getDraftSourceIndex(part);
      if (sourceIndex !== null) {
        retainedAttachmentIndexes.add(sourceIndex);
      }
      return;
    }

    appendedAttachments.push(part);
  });

  const preservedParts: UIMessagePart[] = [];

  session.sourceParts.forEach((part, index) => {
    if (session.textPartIndex !== null && index === session.textPartIndex && part.type === "text") {
      preservedParts.push({ ...part, text: editedText });
      return;
    }

    if (isAttachmentPart(part)) {
      if (retainedAttachmentIndexes.has(index)) {
        preservedParts.push(part);
      }
      return;
    }

    preservedParts.push(part);
  });

  if (session.textPartIndex === null && textPart && textPart.text.trim().length > 0) {
    return [textPart, ...preservedParts, ...appendedAttachments];
  }

  return [...preservedParts, ...appendedAttachments];
}

function useConversationDetail(activeId: string | null, updateSummary: ConversationSummaryUpdater) {
  const { t } = useTranslation("page");
  const [detail, setDetail] = React.useState<ConversationDto | null>(null);
  const [detailLoading, setDetailLoading] = React.useState(false);
  const [detailError, setDetailError] = React.useState<string | null>(null);
  const [revision, setRevision] = React.useState(0);
  const refreshDetail = React.useCallback(() => setRevision((value) => value + 1), []);

  const resetDetail = React.useCallback(() => {
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
  }, []);

  React.useEffect(() => {
    if (!activeId) {
      resetDetail();
      return;
    }

    let mounted = true;
    let receivedSnapshot = false;
    let streamDetail: ConversationDto | null = null;
    let sequence = 0;
    setDetailLoading(true);
    setDetailError(null);

    const abortController = new AbortController();

    api
      .get<ConversationDto>(`conversations/${activeId}`, { signal: abortController.signal })
      .then((data) => {
        if (!mounted || receivedSnapshot) return;
        setDetail(data);
        updateSummary(toConversationSummaryUpdate(data));
      })
      .catch((err: Error) => {
        if (!mounted || receivedSnapshot) return;
        setDetailError(err.message || t("conversations.errors.load_detail_failed"));
        setDetail(null);
      })
      .finally(() => {
        if (!mounted) return;
        setDetailLoading(false);
      });

    const subscription = subscribeLiveStream<ConversationStreamEvent>(
      `conversations/${activeId}/stream`,
      {
        // Every new connection starts with an authoritative snapshot. Keep the existing
        // display/reading position until it arrives; never clear history on reconnect.
        onOpen: () => { streamDetail = null; sequence = 0; },
        onMessage: ({ event, data }) => {
          if (!mounted) return;

          if (event === "error" && data.type === "error") {
            toast.error(data.message);
            return;
          }

          if (event === "snapshot" && data.type === "snapshot") {
            if (data.conversation.id !== activeId || !Number.isSafeInteger(data.seq) || data.seq <= sequence) return;
            receivedSnapshot = true;
            streamDetail = data.conversation;
            sequence = data.seq;
            useAppStore.getState().setClockOffset(data.serverTime);
            setDetail(data.conversation);
            updateSummary(toConversationSummaryUpdate(data.conversation));
            setDetailError(null);
            setDetailLoading(false);
            return;
          }

          if (event !== "node_update" || data.type !== "node_update") return;
          if (data.conversationId !== activeId) return;
          if (data.seq <= sequence) return; // A duplicate must not roll newer text back.
          const next = streamDetail && data.seq === sequence + 1
            ? applyConversationNodeUpdate(streamDetail, data) : null;
          if (!next) {
            subscription.reconnect(); // Gap recovery is another GET stream, never a send/retry.
            return;
          }
          const generationChanged = streamDetail!.isGenerating !== next.isGenerating;
          streamDetail = next;
          sequence = data.seq;
          useAppStore.getState().setClockOffset(data.serverTime);
          setDetail(next);
          if (generationChanged) updateSummary(toConversationSummaryUpdate(next));
          setDetailError(null);
          setDetailLoading(false);
        },
        onError: (streamError) => {
          if (!mounted) return;
          console.error("Conversation detail SSE error:", streamError);
          if (!receivedSnapshot) {
            setDetailError(streamError.message || t("conversations.errors.load_detail_failed"));
            setDetailLoading(false);
          }
        },
      },
    );

    return () => {
      mounted = false;
      abortController.abort();
      subscription.close();
    };
  }, [activeId, resetDetail, t, updateSummary, revision]);

  return {
    detail,
    detailLoading,
    detailError,
    messageNodes: detail?.messages ?? EMPTY_MESSAGE_NODES,
    resetDetail,
    refreshDetail,
  };
}

function useDraftInputController({
  activeId,
  isHomeRoute,
  homeDraftId,
  setHomeDraftId,
  useConversationPromptInjection,
  navigate,
  refreshList,
}: {
  activeId: string | null;
  isHomeRoute: boolean;
  homeDraftId: string;
  setHomeDraftId: React.Dispatch<React.SetStateAction<string>>;
  useConversationPromptInjection: boolean;
  navigate: ReturnType<typeof useNavigate>;
  refreshList: () => void;
}) {
  const draftKey = activeId ?? (isHomeRoute ? homeDraftId : null);
  const draft = useChatInputStore(
    React.useCallback((state) => (draftKey ? state.drafts[draftKey] : undefined), [draftKey]),
  );

  const setDraftText = useChatInputStore((state) => state.setText);
  const addDraftParts = useChatInputStore((state) => state.addParts);
  const removeDraftPart = useChatInputStore((state) => state.removePartAt);
  const getSubmitParts = useChatInputStore((state) => state.getSubmitParts);
  const getPromptInjectionIds = useChatInputStore((state) => state.getPromptInjectionIds);
  const clearDraft = useChatInputStore((state) => state.clearDraft);

  const inputText = draft?.text ?? "";
  const inputAttachments = draft?.parts ?? EMPTY_INPUT_ATTACHMENTS;

  const handleInputTextChange = React.useCallback(
    (text: string) => {
      if (!draftKey) return;
      setDraftText(draftKey, text);
    },
    [draftKey, setDraftText],
  );

  const handleAddInputParts = React.useCallback(
    (parts: UIMessagePart[]) => {
      if (!draftKey || parts.length === 0) return;
      addDraftParts(draftKey, parts);
    },
    [addDraftParts, draftKey],
  );

  const handleRemoveInputPart = React.useCallback(
    (index: number) => {
      if (!draftKey) return;
      removeDraftPart(draftKey, index);
    },
    [draftKey, removeDraftPart],
  );

  const handleSubmit = React.useCallback(async () => {
    if (!draftKey) return;

    const parts = getSubmitParts(draftKey);
    if (parts.length === 0) return;

    if (activeId) {
      await api.post<{ status: string }>(`conversations/${activeId}/messages`, { parts });
      clearDraft(draftKey);
      return;
    }

    const conversationId = uuidv4();
    setHomeDraftId(createHomeDraftId());
    const promptInjectionIds = getPromptInjectionIds(draftKey);

    await api.post<{ status: string }>(`conversations/${conversationId}/messages`, {
      parts,
      ...(useConversationPromptInjection
        ? {
            modeInjectionIds: promptInjectionIds.modeInjectionIds,
            lorebookIds: promptInjectionIds.lorebookIds,
          }
        : {}),
    });
    clearDraft(draftKey);

    navigate(`/c/${conversationId}`);
    refreshList();
  }, [
    activeId,
    clearDraft,
    draftKey,
    getPromptInjectionIds,
    getSubmitParts,
    navigate,
    refreshList,
    setHomeDraftId,
    useConversationPromptInjection,
  ]);

  const replaceDraft = React.useCallback(
    (text: string, parts: UIMessagePart[]) => {
      if (!draftKey) return;
      clearDraft(draftKey);
      setDraftText(draftKey, text);
      addDraftParts(draftKey, parts);
    },
    [addDraftParts, clearDraft, draftKey, setDraftText],
  );

  const clearCurrentDraft = React.useCallback(() => {
    if (!draftKey) return;
    clearDraft(draftKey);
  }, [clearDraft, draftKey]);

  const getCurrentSubmitParts = React.useCallback(() => {
    if (!draftKey) return [];
    return getSubmitParts(draftKey);
  }, [draftKey, getSubmitParts]);

  return {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
    replaceDraft,
    clearCurrentDraft,
    getCurrentSubmitParts,
  };
}

const EMPTY_MESSAGE_NODES: MessageNodeDto[] = [];

function HistoryReadGuard({ onEscape, followingLatest }: { onEscape: () => void; followingLatest: boolean }) {
  const { escapedFromLock, scrollRef, scrollToBottom } = useStickToBottomContext();
  const userReading = React.useRef(false);
  React.useEffect(() => {
    const viewport = scrollRef.current;
    if (!viewport) return;
    let pointerDown = false;
    let previousTop = viewport.scrollTop;
    let touchY: number | null = null;
    const readHistory = () => {
      if (viewport.scrollHeight <= viewport.clientHeight) return;
      userReading.current = true;
      onEscape();
    };
    const wheel = (event: WheelEvent) => { if (event.deltaY < 0) readHistory(); };
    const pointerStart = () => { pointerDown = true; previousTop = viewport.scrollTop; };
    const pointerEnd = () => { pointerDown = false; };
    const scroll = () => {
      if (pointerDown && viewport.scrollTop < previousTop - 1) readHistory();
      previousTop = viewport.scrollTop;
    };
    const touchStart = (event: TouchEvent) => { touchY = event.touches[0]?.clientY ?? null; };
    const touchMove = (event: TouchEvent) => {
      const y = event.touches[0]?.clientY;
      if (touchY !== null && y !== undefined && y > touchY + 2) readHistory();
      if (y !== undefined) touchY = y;
    };
    const key = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.closest("input, textarea, [contenteditable=true]")) return;
      if (target !== document.body && target && !viewport.contains(target)) return;
      if (["ArrowUp", "PageUp", "Home"].includes(event.key) || (event.key === " " && event.shiftKey)) readHistory();
    };
    viewport.addEventListener("wheel", wheel, { passive: true });
    viewport.addEventListener("pointerdown", pointerStart);
    viewport.addEventListener("scroll", scroll, { passive: true });
    viewport.addEventListener("touchstart", touchStart, { passive: true });
    viewport.addEventListener("touchmove", touchMove, { passive: true });
    window.addEventListener("pointerup", pointerEnd);
    window.addEventListener("pointercancel", pointerEnd);
    window.addEventListener("keydown", key);
    return () => {
      viewport.removeEventListener("wheel", wheel);
      viewport.removeEventListener("pointerdown", pointerStart);
      viewport.removeEventListener("scroll", scroll);
      viewport.removeEventListener("touchstart", touchStart);
      viewport.removeEventListener("touchmove", touchMove);
      window.removeEventListener("pointerup", pointerEnd);
      window.removeEventListener("pointercancel", pointerEnd);
      window.removeEventListener("keydown", key);
    };
  }, [onEscape, scrollRef]);
  React.useEffect(() => {
    if (followingLatest) userReading.current = false;
  }, [followingLatest]);
  React.useEffect(() => {
    // Replacing the oldest node in the bounded window can look like an upward scroll to
    // the scroll library. Only genuine user input may freeze the history window.
    if (escapedFromLock && followingLatest && !userReading.current) {
      void scrollToBottom({ animation: "instant" });
    }
  }, [escapedFromLock, followingLatest, scrollToBottom]);
  return null;
}

const ConversationTimeline = React.memo(
  ({
    activeId,
    isHomeRoute,
    detailLoading,
    detailError,
    messageNodes,
    isGenerating,
    settings,
    conversationAssistantId,
    contentClassName,
    onEdit,
    onDelete,
    onFork,
    onRegenerate,
    onSelectBranch,
    onToolApproval,
  }: {
    activeId: string | null;
    isHomeRoute: boolean;
    detailLoading: boolean;
    detailError: string | null;
    messageNodes: MessageNodeDto[];
    isGenerating: boolean;
    settings: Settings | null;
    conversationAssistantId: string | null;
    contentClassName?: string;
    onEdit: (message: MessageDto) => void | Promise<void>;
    onDelete: (messageId: string) => Promise<void>;
    onFork: (messageId: string) => Promise<void>;
    onRegenerate: (messageId: string) => Promise<void>;
    onSelectBranch: (nodeId: string, selectIndex: number) => Promise<void>;
    onToolApproval: (
      toolCallId: string,
      approved: boolean,
      reason: string,
      answer?: string,
    ) => Promise<void>;
  }) => {
    const { t } = useTranslation("page");
    const [pinnedEnd, setPinnedEnd] = React.useState<number | null>(null);
    const [pageRevision, setPageRevision] = React.useState(0);
    const window = historyWindow(messageNodes.length, pinnedEnd);
    const selectedNodeMessages = React.useMemo<SelectedNodeMessage[]>(() => messageNodes
      .slice(window.start, window.end).map((node) => ({ node, message: node.messages[node.selectIndex] ?? node.messages[0] }))
      .filter((item) => Boolean(item.message)), [messageNodes, window.start, window.end]);
    const freezeReading = React.useCallback(() => {
      setPinnedEnd((old) => old === null ? messageNodes.length : old);
    }, [messageNodes.length]);
    const navigateWindow = (end: number | null) => { setPinnedEnd(end); setPageRevision((value) => value + 1); };
    const canQuickJump =
      Boolean(activeId) && !detailLoading && !detailError && selectedNodeMessages.length > 1;
    const assistant = React.useMemo(() => {
      if (!settings || !conversationAssistantId) return null;
      return settings.assistants.find((item) => item.id === conversationAssistantId) ?? null;
    }, [conversationAssistantId, settings]);
    const modelById = React.useMemo(() => {
      const map = new Map<string, ProviderModel>();
      if (!settings) return map;

      for (const provider of settings.providers) {
        for (const model of provider.models) {
          if (!map.has(model.id)) {
            map.set(model.id, model);
          }
        }
      }

      return map;
    }, [settings]);

    return (
      <Conversation key={pageRevision} className="flex-1 min-h-0" initial={pinnedEnd === null ? "instant" : false}>
        <HistoryReadGuard onEscape={freezeReading} followingLatest={pinnedEnd === null} />
        <ConversationContent
          className={cn("mx-auto w-full max-w-3xl gap-4 px-4 py-6", contentClassName)}
        >
          {!activeId && !isHomeRoute && (
            <ConversationEmptyState
              icon={<MessageSquare className="size-10" />}
              title={t("conversations.empty_state.select_title")}
              description={t("conversations.empty_state.select_description")}
            />
          )}
          {activeId && detailLoading && (
            <ConversationEmptyState
              title={t("conversations.empty_state.loading_title")}
              description={t("conversations.empty_state.loading_description")}
            />
          )}
          {activeId && detailError && (
            <ConversationEmptyState
              title={t("conversations.empty_state.error_title")}
              description={detailError}
            />
          )}
          {!detailLoading && !detailError && activeId && messageNodes.length === 0 && (
            <ConversationEmptyState
              icon={<MessageSquare className="size-10" />}
              title={t("conversations.empty_state.no_message_title")}
              description={t("conversations.empty_state.no_message_description")}
            />
          )}
          {!detailLoading && !detailError && activeId && messageNodes.length > 0 && (
            <div className="sticky top-0 z-10 flex flex-wrap items-center gap-2 rounded-xl border bg-background/95 p-2 text-xs backdrop-blur-sm">
              <span className="mr-auto text-muted-foreground">显示 {window.start + 1}–{window.end} / {messageNodes.length} 条 · 原记录完整保留</span>
              {window.start > 0 && <Button size="sm" variant="outline" onClick={() => navigateWindow(olderHistoryEnd(window.start))}>更早记录</Button>}
              {window.newer > 0 && <Button size="sm" variant="outline" onClick={() => navigateWindow(newerHistoryEnd(window.end, messageNodes.length))}>较新记录</Button>}
              {pinnedEnd !== null && <Button size="sm" variant="secondary" onClick={() => navigateWindow(null)}>回到最新{window.newer > 0 ? ` (${window.newer})` : ""}</Button>}
            </div>
          )}
          {!detailLoading &&
            !detailError &&
            activeId &&
            selectedNodeMessages.map(({ node, message }) => {
              const model = message.modelId ? (modelById.get(message.modelId) ?? null) : null;

              return (
                <div
                  key={message.id}
                  id={getConversationMessageAnchorId(message.id)}
                  className="scroll-mt-24"
                >
                  <ChatMessage
                    node={node}
                    message={message}
                    loading={isGenerating && node.id === messageNodes.at(-1)?.id}
                    isLastMessage={node.id === messageNodes.at(-1)?.id}
                    assistant={assistant}
                    model={model}
                    onEdit={onEdit}
                    onDelete={onDelete}
                    onFork={onFork}
                    onRegenerate={onRegenerate}
                    onSelectBranch={onSelectBranch}
                    onToolApproval={onToolApproval}
                  />
                </div>
              );
            })}
          {!detailLoading && !detailError && activeId && isGenerating && window.end === messageNodes.length && (
            <div className="flex items-start py-2">
              <TypingIndicator className="px-1 py-2" />
            </div>
          )}
        </ConversationContent>

        {canQuickJump ? (
          <ConversationQuickJump
            items={selectedNodeMessages.map(({ message }) => ({
              id: message.id,
              role: message.role,
              preview: getQuickJumpPreview(message, t),
            }))}
          />
        ) : null}

        <ConversationScrollButton />
      </Conversation>
    );
  },
);

export function meta() {
  return [
    { title: i18n.t("page:conversations.meta.title") },
    {
      name: "description",
      content: i18n.t("page:conversations.meta.description"),
    },
  ];
}

export default function ConversationsPage() {
  const workbench = useWorkbenchController();

  return (
    <WorkbenchProvider value={workbench}>
      <ConversationsPageInner />
    </WorkbenchProvider>
  );
}

function ConversationsPageInner() {
  const { t } = useTranslation("page");
  const navigate = useNavigate();
  const { id: routeId } = useParams();
  const isHomeRoute = !routeId;
  const isMobile = useIsMobile();
  const { panel, closePanel } = useWorkbench();

  const { settings, assistants, currentAssistantId, currentAssistant } = useCurrentAssistant();
  const { currentModel, currentProvider } = useCurrentModel();
  const {
    folders,
    selectedFolderId,
    setSelectedFolderId,
    createFolder,
    renameFolder,
    deleteFolder,
    moveConversationToFolder,
  } = useFolders(currentAssistantId);
  const {
    conversations,
    activeId,
    setActiveId,
    loading,
    error,
    hasMore,
    loadMore,
    refreshList,
    updateConversationSummary,
  } = useConversationList({
    currentAssistantId,
    routeId,
    autoSelectFirst: !isHomeRoute,
    folderId: selectedFolderId,
  });

  const [homeDraftId, setHomeDraftId] = React.useState(() => createHomeDraftId());
  const [appearanceOpen, setAppearanceOpen] = React.useState(false);
  const [editingSession, setEditingSession] = React.useState<EditingSession | null>(null);

  const { detail, detailLoading, detailError, messageNodes, resetDetail, refreshDetail } =
    useConversationDetail(activeId, updateConversationSummary);

  const {
    draftKey,
    inputText,
    inputAttachments,
    handleInputTextChange,
    handleAddInputParts,
    handleRemoveInputPart,
    handleSubmit,
    replaceDraft,
    clearCurrentDraft,
    getCurrentSubmitParts,
  } = useDraftInputController({
    activeId,
    isHomeRoute,
    homeDraftId,
    setHomeDraftId,
    useConversationPromptInjection: currentAssistant?.allowConversationPromptInjection === true,
    navigate,
    refreshList,
  });

  const activeConversation = conversations.find((item) => item.id === activeId);
  const chatSuggestions = detail?.chatSuggestions ?? EMPTY_SUGGESTIONS;

  React.useEffect(() => {
    const base = t("conversations.meta.title");
    document.title = activeConversation?.title ? `${activeConversation.title} - ${base}` : base;
    return () => {
      document.title = base;
    };
  }, [activeConversation?.title, t]);
  const isNewChat = isHomeRoute && !activeId;
  const showSuggestions =
    Boolean(activeId) && !detailLoading && !detailError && chatSuggestions.length > 0;
  const displaySuggestions = showSuggestions ? chatSuggestions : EMPTY_SUGGESTIONS;

  const handleSelect = React.useCallback(
    (id: string) => {
      setActiveId(id);
      if (routeId !== id) {
        navigate(`/c/${id}`);
      }
    },
    [navigate, routeId, setActiveId],
  );

  React.useEffect(() => {
    setEditingSession(null);
  }, [activeId]);

  const handleAssistantChange = React.useCallback(
    async (assistantId: string) => {
      await api.post<{ status: string }>("settings/assistant", { assistantId });
      setActiveId(null);
      resetDetail();
      if (routeId) {
        navigate("/", { replace: true });
      }
      refreshList();
    },
    [navigate, refreshList, resetDetail, routeId, setActiveId],
  );

  const handleToolApproval = React.useCallback(
    async (toolCallId: string, approved: boolean, reason: string, answer?: string) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/tool-approval`, {
        toolCallId,
        approved,
        reason,
        ...(answer != null ? { answer } : {}),
      });
    },
    [activeId],
  );

  const handleRegenerate = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/regenerate`, {
        messageId,
      });
    },
    [activeId],
  );

  const handleSelectBranch = React.useCallback(
    async (nodeId: string, selectIndex: number) => {
      if (!activeId) return;
      await api.post<{ status: string }>(`conversations/${activeId}/nodes/${nodeId}/select`, {
        selectIndex,
      });
    },
    [activeId],
  );

  const handleDeleteMessage = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      await api.delete<{ status: string }>(`conversations/${activeId}/messages/${messageId}`);
    },
    [activeId],
  );

  const handleForkMessage = React.useCallback(
    async (messageId: string) => {
      if (!activeId) return;
      const response = await api.post<{ conversationId: string }>(
        `conversations/${activeId}/fork`,
        {
          messageId,
        },
      );
      setActiveId(response.conversationId);
      navigate(`/c/${response.conversationId}`);
      refreshList();
    },
    [activeId, navigate, refreshList, setActiveId],
  );

  const handleStartEdit = React.useCallback(
    (message: MessageDto) => {
      if (!activeId || (message.role !== "USER" && message.role !== "ASSISTANT")) return;

      const draft = toEditDraft(message);
      if (!draft) return;

      setEditingSession({
        messageId: message.id,
        sourceParts: draft.sourceParts,
        textPartIndex: draft.textPartIndex,
      });
      replaceDraft(draft.text, draft.attachments);
    },
    [activeId, replaceDraft],
  );

  const handleCancelEdit = React.useCallback(() => {
    setEditingSession(null);
    clearCurrentDraft();
  }, [clearCurrentDraft]);

  const handleClickSuggestion = React.useCallback(
    (suggestion: string) => {
      if (editingSession) {
        setEditingSession(null);
      }
      handleInputTextChange(suggestion);
    },
    [editingSession, handleInputTextChange],
  );

  const handleSend = React.useCallback(async () => {
    if (!editingSession) {
      await handleSubmit();
      return;
    }

    if (!activeId) return;

    const draftParts = getCurrentSubmitParts();
    if (draftParts.length === 0) return;

    const nextParts = buildEditedParts(editingSession, draftParts);

    await api.post<{ status: string }>(
      `conversations/${activeId}/messages/${editingSession.messageId}/edit`,
      { parts: stripEditDraftMetadata(nextParts) },
    );

    setEditingSession(null);
    clearCurrentDraft();
  }, [activeId, clearCurrentDraft, editingSession, getCurrentSubmitParts, handleSubmit]);

  const handleTogglePinConversation = React.useCallback(
    async (conversationId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/pin`);
      refreshList();
    },
    [refreshList],
  );

  const handleRegenerateConversationTitle = React.useCallback(
    async (conversationId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/regenerate-title`);
      refreshList();
    },
    [refreshList],
  );

  const handleMoveConversation = React.useCallback(
    async (conversationId: string, assistantId: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/move`, { assistantId });
      if (conversationId === activeId) {
        setActiveId(null);
        resetDetail();
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, resetDetail, routeId, setActiveId],
  );

  const handleUpdateConversationTitle = React.useCallback(
    async (conversationId: string, title: string) => {
      await api.post<{ status: string }>(`conversations/${conversationId}/title`, { title });
      refreshList();
    },
    [refreshList],
  );

  const handleDeleteConversation = React.useCallback(
    async (conversationId: string) => {
      await api.delete<Record<string, never>>(`conversations/${conversationId}`, {
        parseJson: (raw) => (raw ? JSON.parse(raw) : {}),
      });
      if (conversationId === activeId) {
        setActiveId(null);
        resetDetail();
        setHomeDraftId(createHomeDraftId());
        if (routeId === conversationId) {
          navigate("/", { replace: true });
        }
      }
      refreshList();
    },
    [activeId, navigate, refreshList, resetDetail, routeId, setActiveId],
  );

  const handleSelectFolder = React.useCallback(
    (folderId: string | null) => {
      setSelectedFolderId(folderId);
    },
    [setSelectedFolderId],
  );

  const handleCreateFolder = React.useCallback(
    async (name: string) => {
      await createFolder(name);
    },
    [createFolder],
  );

  const handleRenameFolder = React.useCallback(
    async (id: string, name: string) => {
      await renameFolder(id, name);
    },
    [renameFolder],
  );

  const handleDeleteFolder = React.useCallback(
    async (id: string) => {
      await deleteFolder(id);
      refreshList();
    },
    [deleteFolder, refreshList],
  );

  const handleMoveConversationToFolder = React.useCallback(
    async (conversationId: string, folderId: string | null) => {
      await moveConversationToFolder(conversationId, folderId);
      refreshList();
    },
    [moveConversationToFolder, refreshList],
  );

  const handleCreateConversation = React.useCallback(() => {
    closePanel();
    setActiveId(null);
    resetDetail();
    setHomeDraftId(createHomeDraftId());

    if (routeId) {
      navigate("/");
    }
  }, [closePanel, navigate, resetDetail, routeId, setActiveId]);

  const handleStop = React.useCallback(async () => {
    if (!activeId) return;
    await api.post<{ status: string }>(`conversations/${activeId}/stop`);
  }, [activeId]);

  const hasWorkbenchPanel = Boolean(panel);
  const workbenchPanelRef = React.useRef<PanelImperativeHandle | null>(null);

  React.useEffect(() => {
    if (isMobile) return;

    const workbenchPanel = workbenchPanelRef.current;
    if (!workbenchPanel) return;

    if (hasWorkbenchPanel) {
      workbenchPanel.expand();
    } else {
      workbenchPanel.collapse();
    }
  }, [hasWorkbenchPanel, isMobile]);

  const chatContent = (
    <div
      className={cn("flex flex-1 flex-col min-h-0 overflow-hidden", isNewChat && "justify-center")}
    >
      {!isNewChat && (
        <div className="relative flex min-h-0 flex-1">
          <ConversationTimeline
            key={activeId ?? "home"}
            activeId={activeId}
            isHomeRoute={isHomeRoute}
            detailLoading={detailLoading}
            detailError={detailError}
            messageNodes={messageNodes}
            isGenerating={detail?.isGenerating ?? false}
            settings={settings}
            conversationAssistantId={detail?.assistantId ?? null}
            onEdit={handleStartEdit}
            onDelete={handleDeleteMessage}
            onFork={handleForkMessage}
            onRegenerate={handleRegenerate}
            onSelectBranch={handleSelectBranch}
            onToolApproval={handleToolApproval}
          />
        </div>
      )}

      <div>
        {isNewChat && (
          <div className="mb-4 text-center">
            <div className="mb-3 flex justify-center">
              <div className="[&>svg]:size-16">
                <Logo className="size-16 text-primary" />
              </div>
            </div>
            <p className="orbis-welcome text-lg text-muted-foreground break-words px-4">{t("conversations.welcome_prompt", { name: welcomeName(settings?.displaySetting.userNickname) })}</p>
          </div>
        )}
        <ChatInput
          value={inputText}
          attachments={inputAttachments}
          conversation={detail}
          draftKey={draftKey}
          ready={draftKey !== null}
          isGenerating={detail?.isGenerating ?? false}
          disabled={detailLoading || Boolean(detailError)}
          onValueChange={handleInputTextChange}
          onAddParts={handleAddInputParts}
          suggestions={displaySuggestions}
          onSuggestionClick={handleClickSuggestion}
          isEditing={Boolean(editingSession)}
          onCancelEdit={editingSession ? handleCancelEdit : undefined}
          shouldDeleteFileOnRemove={shouldDeleteAttachmentFileOnRemove}
          onRemovePart={handleRemoveInputPart}
          onSend={handleSend}
          onStop={activeId ? handleStop : undefined}
          onExportConversation={
            detail && detail.messages.length > 0
              ? (includeReasoning: boolean) => {
                  const content = convertConversationToMarkdown(detail, includeReasoning);
                  const filename = `${detail.title || "conversation"}.md`;
                  downloadMarkdown(content, filename);
                }
              : undefined
          }
        />
      </div>
    </div>
  );

  return (
    <SidebarProvider defaultOpen className="h-svh overflow-hidden">
      <ConversationSidebar
        conversations={conversations}
        activeId={activeId}
        loading={loading}
        error={error}
        hasMore={hasMore}
        loadMore={loadMore}
        userName={
          settings?.displaySetting.userNickname?.trim() || t("conversations.user.default_name")
        }
        userAvatar={settings?.displaySetting.userAvatar}
        onEditProfile={() => setAppearanceOpen(true)}
        assistants={assistants}
        assistantTags={settings?.assistantTags ?? []}
        currentAssistantId={currentAssistantId}
        onSelect={handleSelect}
        onAssistantChange={handleAssistantChange}
        onPin={handleTogglePinConversation}
        onRegenerateTitle={handleRegenerateConversationTitle}
        onMoveToAssistant={handleMoveConversation}
        onUpdateTitle={handleUpdateConversationTitle}
        onDelete={handleDeleteConversation}
        onCreateConversation={handleCreateConversation}
        webAuthEnabled={settings?.webServerJwtEnabled === true}
        folders={folders}
        selectedFolderId={selectedFolderId}
        onSelectFolder={handleSelectFolder}
        onCreateFolder={handleCreateFolder}
        onRenameFolder={handleRenameFolder}
        onDeleteFolder={handleDeleteFolder}
        onMoveToFolder={handleMoveConversationToFolder}
      />
      <SidebarInset className="flex min-h-svh flex-col overflow-hidden">
        <div className="flex items-center gap-2 border-b px-4 py-3">
          <SidebarTrigger />
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm text-muted-foreground">
              {activeConversation
                ? activeConversation.title
                : t("conversations.header.select_conversation")}
            </div>
            {currentModel && currentProvider ? (
              <div className="truncate text-xs text-muted-foreground/80">
                {`${getAssistantDisplayName(currentAssistant?.name)} / ${getModelDisplayName(currentModel.displayName, currentModel.modelId)} (${currentProvider.name})`}
              </div>
            ) : null}
          </div>
          <OrbisWebActions conversationId={activeId} appearanceOpen={appearanceOpen} onAppearanceOpenChange={setAppearanceOpen} onImported={refreshList} onContextCommitted={() => { refreshDetail(); refreshList(); }} />
        </div>

        {!isMobile ? (
          <ResizablePanelGroup orientation="horizontal" className="min-h-0 flex-1">
            <ResizablePanel
              defaultSize={hasWorkbenchPanel ? 64 : 100}
              minSize={40}
              className="flex min-h-0 flex-col"
            >
              {chatContent}
            </ResizablePanel>
            <ResizableHandle
              withHandle
              className={cn(!hasWorkbenchPanel && "pointer-events-none opacity-0")}
            />
            <ResizablePanel
              defaultSize={hasWorkbenchPanel ? 36 : 0}
              minSize={24}
              collapsible
              collapsedSize={0}
              panelRef={workbenchPanelRef}
              className="flex min-h-0 flex-col"
            >
              {panel ? (
                <WorkbenchHost panel={panel} onClose={closePanel} className="border-l-0" />
              ) : null}
            </ResizablePanel>
          </ResizablePanelGroup>
        ) : (
          chatContent
        )}

        {isMobile && panel ? (
          <Drawer
            open={hasWorkbenchPanel}
            onOpenChange={(open) => {
              if (!open) {
                closePanel();
              }
            }}
            direction="bottom"
          >
            <DrawerContent className="h-[85vh] max-h-[85vh]">
              <WorkbenchHost panel={panel} onClose={closePanel} className="border-l-0" />
            </DrawerContent>
          </Drawer>
        ) : null}
      </SidebarInset>
    </SidebarProvider>
  );
}
