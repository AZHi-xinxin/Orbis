"""Owned OmbreBrain supervisor. Only state/ports cross the host pipe, never configuration.

Runs an already installed instance. EOF stops its process group; no installation or model
calls are performed by this wrapper. Health uses authenticated MCP initialize/tools-list.
"""
import json
import os
from pathlib import Path
import signal
import socket
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


def rpc(opener, url, token, method, params, session=None, request_id=1):
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
               "Authorization": "Bearer " + token, "MCP-Protocol-Version": "2025-03-26"}
    if session:
        headers["Mcp-Session-Id"] = session
    data = {"jsonrpc": "2.0", "method": method, "params": params}
    if request_id is not None:
        data["id"] = request_id
    with opener.open(Request(url, json.dumps(data).encode(), headers), timeout=4) as response:
        sid = response.headers.get("Mcp-Session-Id") or session
        if request_id is None:
            return {}, sid
        if "text/event-stream" in response.headers.get("Content-Type", ""):
            total = 0
            while total < 1024 * 1024:
                line = response.readline(1024 * 1024)
                total += len(line)
                if not line:
                    break
                if line.startswith(b"data:"):
                    value = json.loads(line[5:])
                    if value.get("id") == request_id:
                        return value, sid
            raise ValueError("mcp_response_missing")
        return json.loads(response.read(1024 * 1024)), sid


def health(port, token):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    url = f"http://127.0.0.1:{port}/mcp"
    initialized, session = rpc(opener, url, token, "initialize", {
        "protocolVersion": "2025-03-26", "capabilities": {},
        "clientInfo": {"name": "orbis-ombre-health", "version": "1"}})
    if "result" not in initialized:
        return False
    try:
        rpc(opener, url, token, "notifications/initialized", {}, session, None)
        result, _ = rpc(opener, url, token, "tools/list", {}, session, 2)
        names = {tool["name"] for tool in result.get("result", {}).get("tools", [])}
        return {"breath", "hold", "trace"}.issubset(names)
    finally:
        if session:
            try:
                opener.open(Request(url, headers={"Authorization": "Bearer " + token,
                    "Mcp-Session-Id": session, "MCP-Protocol-Version": "2025-03-26"}, method="DELETE"), timeout=2).close()
            except Exception:
                pass


def main(source, config):
    import yaml
    source, config = Path(source).resolve(), Path(config).resolve()
    if not (source / "src/server.py").is_file() or not config.is_file():
        raise ValueError("instance_missing")
    settings = yaml.safe_load(config.read_text(encoding="utf-8"))
    port = int(settings.get("host_port", 28811))
    if not 1024 <= port <= 65535:
        raise ValueError("port_invalid")
    token = settings.get("mcp_token", "")
    if not isinstance(token, str) or not token or settings.get("mcp_auth_mode") not in ("token", "hybrid"):
        raise ValueError("static_token_setup_required")
    # Claim the selected port before launch: an unrelated instance must never count as ours.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", port))
    data = Path(settings.get("buckets_dir") or "buckets")
    data = (data if data.is_absolute() else config.parent / data).resolve()
    data.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update(OMBRE_TRANSPORT="streamable-http", OMBRE_BIND_HOST="127.0.0.1",
               OMBRE_PORT=str(port), OMBRE_CONFIG_PATH=str(config), OMBRE_BUCKETS_DIR=str(data),
               OMBRE_MCP_TOKEN=token, OMBRE_MCP_AUTH_MODE="token", OMBRE_MCP_REQUIRE_AUTH="true")
    stopped = threading.Event()
    def eof():
        try:
            while sys.stdin.buffer.read(1):
                pass
        finally:
            stopped.set()
    threading.Thread(target=eof, daemon=True).start()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    child = subprocess.Popen([sys.executable, "-B", str(source / "src/server.py")], cwd=source,
                             env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
    emit("starting")
    deadline, ready = time.monotonic() + 90, False
    try:
        while not stopped.is_set():
            if child.poll() is not None:
                emit("failed", code="ombre_stack_exited")
                break
            try:
                healthy = health(port, token)
            except Exception:
                healthy = False
            if healthy:
                ready = True
                emit("ready", dashboard_port=port, mcp_port=port)
            elif ready:
                emit("unhealthy", code="ombre_health_unavailable")
            elif time.monotonic() >= deadline:
                emit("failed", code="ombre_startup_timeout")
                break
            stopped.wait(10 if ready else 1)
    finally:
        if child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
            try:
                child.wait(timeout=25)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait()


if __name__ == "__main__":
    try:
        main(*sys.argv[1:])
    except Exception as failure:
        known = {"instance_missing", "port_invalid", "static_token_setup_required"}
        code = "ombre_" + str(failure) if isinstance(failure, ValueError) and str(failure) in known else (
            "ombre_port_in_use" if isinstance(failure, OSError) and failure.errno == 98 else "ombre_start_failed")
        emit("failed", code=code)
        raise SystemExit(1)
