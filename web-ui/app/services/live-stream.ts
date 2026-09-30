import { sse, type SSECallbacks } from "./api";

type Timer = ReturnType<typeof setTimeout>;
export interface LiveStreamEnvironment {
  now: () => number;
  setTimer: (callback: () => void, delay: number) => Timer;
  clearTimer: (timer: Timer) => void;
  isVisible: () => boolean;
  onWake: (callback: () => void) => () => void;
}

const browserEnvironment: LiveStreamEnvironment = {
  now: () => Date.now(),
  setTimer: (callback, delay) => setTimeout(callback, delay),
  clearTimer: (timer) => clearTimeout(timer),
  isVisible: () => typeof document === "undefined" || document.visibilityState !== "hidden",
  onWake: (callback) => {
    if (typeof window === "undefined") return () => {};
    window.addEventListener("online", callback);
    window.addEventListener("pageshow", callback);
    document.addEventListener("visibilitychange", callback);
    return () => {
      window.removeEventListener("online", callback);
      window.removeEventListener("pageshow", callback);
      document.removeEventListener("visibilitychange", callback);
    };
  },
};

/** Read-only GET subscription. Reconnects never send messages, approve tools or resume queues. */
export function subscribeLiveStream<T>(
  url: string,
  callbacks: SSECallbacks<T>,
  options: { connect?: typeof sse; environment?: LiveStreamEnvironment; idleTimeoutMs?: number } = {},
) {
  const connect = options.connect ?? sse;
  const env = options.environment ?? browserEnvironment;
  // The shared stream sends a heartbeat every 15 s; leave room for scheduling/network jitter.
  const idleTimeout = options.idleTimeoutMs ?? 45_000;
  let stopped = false;
  let blocked = false;
  let controller: AbortController | null = null;
  let retryTimer: Timer | null = null;
  let idleTimer: Timer | null = null;
  let lastActivity = env.now();
  let retryDelay = 1_000;

  function clearTimers() {
    if (retryTimer !== null) env.clearTimer(retryTimer);
    if (idleTimer !== null) env.clearTimer(idleTimer);
    retryTimer = idleTimer = null;
  }

  function scheduleReconnect() {
    if (stopped || blocked || retryTimer !== null) return;
    retryTimer = env.setTimer(() => {
      retryTimer = null;
      start();
    }, retryDelay);
    retryDelay = Math.min(retryDelay * 2, 15_000);
  }

  function reconnect() {
    if (stopped || blocked) return;
    clearTimers();
    const previous = controller;
    controller = null; // Fence late data/errors/close from the previous transport before aborting it.
    previous?.abort();
    scheduleReconnect();
  }

  function watchIdle(delay = idleTimeout) {
    idleTimer = env.setTimer(() => {
      idleTimer = null;
      if (stopped || blocked || !controller) return;
      if (env.isVisible() && env.now() - lastActivity >= idleTimeout) reconnect();
      else watchIdle(env.isVisible() ? Math.max(1, idleTimeout - (env.now() - lastActivity)) : idleTimeout);
    }, delay);
  }

  function start() {
    if (stopped || blocked || controller) return;
    const attempt = new AbortController();
    controller = attempt;
    lastActivity = env.now();
    const current = () => !stopped && controller === attempt && !attempt.signal.aborted;
    const handleError = (error: Error) => {
      if (!current()) return;
      // Wait for explicit authentication/navigation, not an endless unauthorized reconnect loop.
      const code = (error as Error & { code?: number }).code;
      blocked = code === 401 || code === 403 || code === 404;
      callbacks.onError?.(error);
    };
    watchIdle();
    void connect<T>(url, {
      onOpen: () => { if (current()) callbacks.onOpen?.(); },
      onActivity: () => {
        if (!current()) return;
        lastActivity = env.now();
        retryDelay = 1_000;
        callbacks.onActivity?.();
      },
      onMessage: (event) => {
        if (!current()) return;
        lastActivity = env.now();
        retryDelay = 1_000;
        callbacks.onMessage(event);
      },
      onError: handleError,
    }, { signal: attempt.signal, retry: 0, cache: "no-store" }).catch((error: unknown) => {
      handleError(error instanceof Error ? error : new Error(String(error)));
    }).finally(() => {
      if (!current()) return;
      controller = null;
      if (idleTimer !== null) env.clearTimer(idleTimer);
      idleTimer = null;
      callbacks.onClose?.();
      scheduleReconnect(); // Handles graceful EOF as well as network errors.
    });
  }

  const removeWakeListener = env.onWake(() => {
    if (!env.isVisible() || stopped || blocked) return;
    if (env.now() - lastActivity >= idleTimeout) reconnect();
    else if (!controller && retryTimer === null) start();
  });
  start();
  return {
    reconnect,
    close: () => {
      stopped = true;
      clearTimers();
      removeWakeListener();
      controller?.abort();
      controller = null;
    },
  };
}
