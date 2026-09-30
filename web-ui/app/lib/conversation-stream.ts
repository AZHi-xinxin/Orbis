import type { ConversationDto, ConversationNodeUpdateEventDto } from "../types/dto";

/** A missing/reordered node means a delta was lost: resnapshot, never overwrite another node. */
export function applyConversationNodeUpdate(
  conversation: ConversationDto,
  event: ConversationNodeUpdateEventDto,
): ConversationDto | null {
  if (conversation.id !== event.conversationId || event.node.id !== event.nodeId ||
      !Number.isInteger(event.nodeIndex) || event.nodeIndex < 0 ||
      event.nodeIndex > conversation.messages.length) return null;
  const existing = conversation.messages[event.nodeIndex];
  if (existing && existing.id !== event.nodeId) return null;
  if (!existing && conversation.messages.some((node) => node.id === event.nodeId)) return null;
  const messages = [...conversation.messages];
  messages[event.nodeIndex] = event.node;
  return { ...conversation, messages, updateAt: event.updateAt, isGenerating: event.isGenerating };
}
