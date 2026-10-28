"""Orbis owns this foreground supervisor; EOF or stop closes only this ST stack.

The protocol emits fixed states and localhost ports, never child output or config.
No installation, memory writes, model calls, retries, or detached processes here.
"""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time
from urllib.request import Request, build_opener, ProxyHandler, HTTPRedirectHandler


def emit(state, **public):
    print(json.dumps(dict(state=state, **public)), flush=True)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError("redirect_rejected")


def health(config, *, discover=True):
    opener = build_opener(ProxyHandler({}), NoRedirect())

    def request(component, path, payload=None):
        headers = {"Accept": "application/json, text/event-stream"}
        if component == "MCP":
            headers["Authorization"] = "Bearer " + config["STBRAIN_MCP_TOKEN"]
        if payload is not None:
            headers["Content-Type"] = "application/json"
        url = "http://127.0.0.1:" + config["STBRAIN_" + component + "_PORT"] + path
        req = Request(url, data=None if payload is None else json.dumps(payload).encode(), headers=headers)
        with opener.open(req, timeout=2) as response:
            limit = 2 * 1024 * 1024 if component == "MCP" else 64 * 1024
            body = response.read(limit + 1)
            if len(body) > limit:
                raise ValueError("health_too_large")
            return json.loads(body) if body else None

    if not request("CONTROL", "/health").get("ok") or not request("GATEWAY", "/health").get("ok"):
        return False
    if not discover:
        # ST is stateless HTTP. Keep later checks small instead of downloading all schemas.
        result = request("MCP", "/mcp", {"jsonrpc": "2.0", "id": 3, "method": "ping", "params": {}})
        return isinstance(result, dict) and "result" in result and "error" not in result
    result = request("MCP", "/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "orbis-st-host", "version": "1"}}})
    if "result" not in result:
        return False
    request("MCP", "/mcp", {"jsonrpc": "2.0", "method": "notifications/initialized"})
    result = request("MCP", "/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    return {"stbrain_help", "remember_memory", "revise_memory"} <= {t["name"] for t in result["result"]["tools"]}


def supervise(source, config_path, *, health_check=None, startup_seconds=75, interval=10):
    stop = threading.Event()
    child = None

    def input_loop():
        try:
            # The private pipe is intentionally kept open by Orbis. Parent death closes it.
            sys.stdin.buffer.readline(64)
        finally:
            stop.set()

    threading.Thread(target=input_loop, daemon=True).start()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    try:
        source = Path(source).resolve(strict=True)
        entry = source / "scripts" / "stiller_ops.py"
        spec = importlib.util.spec_from_file_location("orbis_st_ops", entry)
        ops = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(ops)
        config = ops.load_private_config(Path(config_path), root=source)
        ops.check_ports_stopped(config)
        if stop.is_set():
            return 0
        # Existing official stack owns its three components and checks ports before starting.
        child = subprocess.Popen([sys.executable, "-B", str(entry), "start", "--config", str(config_path)],
            cwd=source, env=ops.child_environment(config), stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        emit("starting")
        deadline = time.monotonic() + startup_seconds
        ever_ready = False
        while not stop.is_set():
            if child.poll() is not None:
                emit("failed", code="st_stack_exited")
                return 2
            try:
                ready = health(config, discover=not ever_ready) if health_check is None else health_check(config)
            except Exception:
                ready = False
            if stop.is_set():
                break
            if child.poll() is not None:
                emit("failed", code="st_stack_exited")
                return 2
            if ready:
                ever_ready = True
                emit("ready", gateway_port=int(config["STBRAIN_GATEWAY_PORT"]), mcp_port=int(config["STBRAIN_MCP_PORT"]))
            elif ever_ready:
                emit("unhealthy", code="st_health_unavailable")
            elif time.monotonic() >= deadline:
                emit("failed", code="st_startup_timeout")
                return 2
            stop.wait(interval if ever_ready else 1)
        return 0
    except Exception:
        emit("failed", code="st_start_failed")
        return 2
    finally:
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=25)
            except subprocess.TimeoutExpired:
                # Ending the PRoot root process also reaps descendants (--kill-on-exit).
                child.kill()
                child.wait(timeout=3)
        emit("exited")


if __name__ == "__main__":
    raise SystemExit(supervise(*sys.argv[1:]))
