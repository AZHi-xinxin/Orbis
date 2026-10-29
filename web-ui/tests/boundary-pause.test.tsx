import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { BoundaryPauseCard } from "../app/components/boundary-pause-card";
import type { ConversationDto } from "../app/types/dto";

const conversation = { id: "synthetic-chat", assistantId: "synthetic-owner", boundary: {
  paused: true, reason: "先休息一下", until: null, allowRequest: false, requestUsed: false, revision: 7,
} } as ConversationDto;

test("paused Web view shows the AI's reason without a send control", () => {
  const html = renderToStaticMarkup(createElement(BoundaryPauseCard, { conversation }));
  assert.match(html, /此对话已由对方暂停/);
  assert.match(html, /先休息一下/);
  assert.match(html, /聊天记录和草稿保留/);
  assert.doesNotMatch(html, /textarea|<form|<button/);
});
test("missing model cannot present an actionable or pending continuation request", () => {
  const html = renderToStaticMarkup(createElement(BoundaryPauseCard, {
    conversation: { ...conversation, boundary: { ...conversation.boundary!, allowRequest: true } },
  }));
  assert.match(html, /暂无可用模型处理继续申请/);
  assert.doesNotMatch(html, /<form|<button|处理中/);
});
test("consumed request and deadline remain visible, an unpaused view has no card", () => {
  const html = renderToStaticMarkup(createElement(BoundaryPauseCard, {
    conversation: { ...conversation, boundary: { ...conversation.boundary!, requestUsed: true, until: 2000000000000 } },
  }));
  assert.match(html, /继续申请正在处理/); assert.match(html, /约定恢复时间/);
  assert.doesNotMatch(html, /<form|<button/);
  assert.equal(renderToStaticMarkup(createElement(BoundaryPauseCard, { conversation: { ...conversation, boundary: undefined } })), "");
});


test("assistant-wide pause explains that new windows are covered", () => {
  const html = renderToStaticMarkup(createElement(BoundaryPauseCard, {
    conversation: { ...conversation, boundary: { ...conversation.boundary!, scope: "assistant" } },
  }));
  assert.match(html, /此助手的所有对话已由对方暂停/);
  assert.match(html, /已有和新建的窗口都适用/);
  assert.match(html, /其他助手照常使用/);
});

test("bounded pause displays remaining time and automatic restoration", () => {
  const originalNow = Date.now;
  Date.now = () => 1_000_000;
  try {
    const html = renderToStaticMarkup(createElement(BoundaryPauseCard, {
      conversation: { ...conversation, boundary: { ...conversation.boundary!, until: 1_061_000 } },
    }));
    assert.match(html, /还剩 0 小时 1 分 1 秒/);
    assert.match(html, /到期自动恢复/);
    const expired = renderToStaticMarkup(createElement(BoundaryPauseCard, {
      conversation: { ...conversation, boundary: { ...conversation.boundary!, until: 999_000 } },
    }));
    assert.match(expired, /已到约定恢复时间/);
    assert.doesNotMatch(expired, /还剩 -/);
  } finally { Date.now = originalNow; }
});

test("three requests exhaust this pause without an actionable form", () => {
  const html = renderToStaticMarkup(createElement(BoundaryPauseCard, {
    conversation: { ...conversation, boundary: { ...conversation.boundary!, allowRequest: true, requestCount: 3 } },
  }));
  assert.match(html, /剩余申请次数 0\/3/);
  assert.match(html, /次数已用完/);
  assert.doesNotMatch(html, /<form|<button|textarea/);
});
