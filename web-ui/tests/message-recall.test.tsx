import test from "node:test";
import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { RecallNotice } from "../app/components/message/recall-notice";

test("recall notices distinguish sender without rendering message or action controls", () => {
  const ai = renderToStaticMarkup(createElement(RecallNotice, { role: "ASSISTANT" }));
  const human = renderToStaticMarkup(createElement(RecallNotice, { role: "USER" }));
  assert.match(ai, /此条消息已被对方撤回/);
  assert.match(human, /该条消息已撤回/);
  assert.doesNotMatch(ai + human, /button|textarea|img|audio|video/);
});
