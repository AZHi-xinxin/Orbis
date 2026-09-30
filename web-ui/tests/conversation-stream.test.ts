import test from "node:test";
import assert from "node:assert/strict";
import { applyConversationNodeUpdate } from "../app/lib/conversation-stream";
import type { ConversationDto, ConversationNodeUpdateEventDto } from "../app/types/dto";

const base: ConversationDto = { id: "chat-a", assistantId: "assistant", title: "synthetic", messages: [
  { id: "node-a", selectIndex: 0, messages: [{ id: "message-a", role: "USER", createdAt: "2026-01-01", parts: [{ type: "text", text: "synthetic" }] }] },
], chatSuggestions: [], isPinned: false, isGenerating: false, createAt: 1, updateAt: 2 };
function event(overrides: Partial<ConversationNodeUpdateEventDto> = {}): ConversationNodeUpdateEventDto {
  return { type: "node_update", seq: 2, conversationId: "chat-a", nodeIndex: 1, nodeId: "node-b",
    node: { id: "node-b", selectIndex: 0, messages: [] }, updateAt: 3, isGenerating: true, serverTime: 3, ...overrides };
}
test("phone append is a single-node immutable update, no full-history fetch", () => {
  const result = applyConversationNodeUpdate(base, event())!;
  assert.equal(result.messages.length, 2); assert.equal(result.messages[0], base.messages[0]);
  assert.equal(result.isGenerating, true); assert.equal(base.messages.length, 1);
});
test("streaming replaces only the existing target node", () => {
  const first = applyConversationNodeUpdate(base, event())!;
  const delta = event({ isGenerating: false });
  const second = applyConversationNodeUpdate(first, delta)!;
  assert.equal(second.messages[1], delta.node); assert.equal(second.messages[0], first.messages[0]);
  assert.equal(second.isGenerating, false);
});
test("foreign conversations and inconsistent node identity are rejected", () => {
  assert.equal(applyConversationNodeUpdate(base, event({ conversationId: "chat-b" })), null);
  assert.equal(applyConversationNodeUpdate(base, event({ nodeId: "mismatch" })), null);
});
test("gaps and invalid indexes require resnapshot instead of losing data silently", () => {
  for (const nodeIndex of [-1, 3, 0.5, NaN]) assert.equal(applyConversationNodeUpdate(base, event({ nodeIndex })), null);
});
test("a stale/reordered delta never overwrites a different existing node", () => {
  assert.equal(applyConversationNodeUpdate(base, event({ nodeIndex: 0 })), null);
});
test("an append cannot duplicate a node already in history", () => {
  assert.equal(applyConversationNodeUpdate(base, event({ nodeId: "node-a", node: base.messages[0] })), null);
});
