import test from "node:test";
import assert from "node:assert/strict";
import { subscribeLiveStream, type LiveStreamEnvironment } from "../app/services/live-stream";
import { ApiError, type SSECallbacks, type sse } from "../app/services/api";

function fixture() {
  let now = 0;
  let visible = true;
  let nextId = 0;
  let wake: (() => void) | null = null;
  const timers = new Map<number, { at: number; callback: () => void }>();
  const attempts: { callbacks: SSECallbacks<unknown>; signal: AbortSignal; end: () => void }[] = [];
  const messages: unknown[] = [];
  const errors: Error[] = [];
  const environment: LiveStreamEnvironment = {
    now: () => now,
    isVisible: () => visible,
    setTimer: (callback, delay) => {
      const id = ++nextId;
      timers.set(id, { at: now + delay, callback });
      return id as unknown as ReturnType<typeof setTimeout>;
    },
    clearTimer: (id) => { timers.delete(id as unknown as number); },
    onWake: (callback) => { wake = callback; return () => { wake = null; }; },
  };
  const connect = ((_url, callbacks, options) => new Promise<void>((resolve) => {
    attempts.push({ callbacks: callbacks as SSECallbacks<unknown>, signal: options!.signal!, end: resolve });
    callbacks.onOpen?.();
  })) as typeof sse;
  const start = () => subscribeLiveStream("conversations/synthetic/stream", {
    onMessage: (event) => messages.push(event.data), onError: (error) => errors.push(error),
  }, { connect, environment });
  const advance = (ms: number) => {
    const target = now + ms;
    while (true) {
      const due = [...timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
      if (!due || due[1].at > target) break;
      now = due[1].at; timers.delete(due[0]); due[1].callback();
    }
    now = target;
  };
  const finish = async (index = attempts.length - 1) => { attempts[index].end(); await Promise.resolve(); };
  return { start, attempts, messages, errors, timers, advance, finish,
    setVisible: (value: boolean) => { visible = value; wake?.(); },
    wake: () => wake?.() };
}

test("graceful EOF reconnects without requiring a page refresh", async () => {
  const f = fixture(); const stream = f.start();
  await f.finish(); f.advance(999); assert.equal(f.attempts.length, 1);
  f.advance(1); assert.equal(f.attempts.length, 2); stream.close();
});
test("network errors reconnect with bounded exponential backoff", async () => {
  const f = fixture(); const stream = f.start();
  for (const delay of [1000, 2000, 4000, 8000, 15000, 15000]) {
    const count = f.attempts.length;
    f.attempts.at(-1)!.callbacks.onError?.(new Error("synthetic disconnect"));
    await f.finish(); f.advance(delay - 1); assert.equal(f.attempts.length, count);
    f.advance(1); assert.equal(f.attempts.length, count + 1);
  }
  stream.close();
});
test("receiving activity resets retry backoff", async () => {
  const f = fixture(); const stream = f.start();
  await f.finish(); f.advance(1000);
  f.attempts[1].callbacks.onActivity?.(); await f.finish();
  f.advance(1000); assert.equal(f.attempts.length, 3); stream.close();
});
test("silent stalled streams reconnect but heartbeats keep healthy idle chats connected", () => {
  const f = fixture(); const stream = f.start();
  for (let i = 0; i < 20; i++) { f.advance(15000); f.attempts[0].callbacks.onActivity?.(); }
  assert.equal(f.attempts.length, 1);
  f.advance(90000); assert.ok(f.attempts.length >= 2);
  assert.equal(f.attempts[0].signal.aborted, true); stream.close();
});
test("hidden tabs do not churn idle connections and wake catches up after a stall", () => {
  const f = fixture(); const stream = f.start();
  f.setVisible(false); f.advance(180000); assert.equal(f.attempts.length, 1);
  f.setVisible(true); f.advance(1000); assert.equal(f.attempts.length, 2); stream.close();
});
test("ordinary tab switches do not redownload healthy history", () => {
  const f = fixture(); const stream = f.start();
  f.advance(1000); f.setVisible(false); f.setVisible(true); f.wake();
  assert.equal(f.attempts.length, 1); stream.close();
});
test("late data and late close from superseded transports cannot replace the new stream", async () => {
  const f = fixture(); const stream = f.start(); const old = f.attempts[0];
  stream.reconnect(); f.advance(1000);
  old.callbacks.onMessage({ event: "snapshot", data: "stale" });
  old.callbacks.onError?.(new ApiError("old auth error", 401));
  await f.finish(0);
  f.attempts[1].callbacks.onMessage({ event: "snapshot", data: "current" });
  assert.deepEqual(f.messages, ["current"]); assert.equal(f.errors.length, 0);
  await f.finish(1); f.advance(1000); assert.equal(f.attempts.length, 3); stream.close();
});
test("intentional cleanup cancels timers and ignores all late callbacks", async () => {
  const f = fixture(); const stream = f.start(); stream.close();
  assert.equal(f.attempts[0].signal.aborted, true);
  f.attempts[0].callbacks.onMessage({ event: "snapshot", data: "stale" });
  await f.finish(); f.advance(180000); f.wake();
  assert.equal(f.attempts.length, 1); assert.deepEqual(f.messages, []); assert.equal(f.timers.size, 0);
});
test("closing while reconnect is scheduled prevents the new connection", async () => {
  const f = fixture(); const stream = f.start(); await f.finish(); stream.close();
  f.advance(180000); assert.equal(f.attempts.length, 1);
});
for (const code of [401, 403, 404]) test(`${code} waits for explicit user action rather than retrying forever`, async () => {
  const f = fixture(); const stream = f.start();
  f.attempts[0].callbacks.onError?.(new ApiError("synthetic blocked", code)); await f.finish();
  f.advance(180000); f.wake(); stream.reconnect(); f.advance(180000);
  assert.equal(f.attempts.length, 1); assert.equal(f.errors.length, 1); stream.close();
});
