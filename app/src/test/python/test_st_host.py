"""Process-lifetime tests with a synthetic ST stack, never a user's installed instance."""
import importlib.util
import json
from pathlib import Path
import os
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest

HOST = Path(__file__).resolve().parents[2] / 'main/assets/orbis-st-host.py'
FAKE_OPS = '''import json, os, signal, sys, time
from pathlib import Path
def load_private_config(path, root=None): return json.loads(path.read_text())
def check_ports_stopped(config):
    if config.get("occupied"): raise ValueError("synthetic-private-secret")
def child_environment(config): return dict(os.environ)
if __name__ == "__main__":
    config = load_private_config(Path(sys.argv[-1]))
    Path(config["marker"]).write_text(str(os.getpid()))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    while True: time.sleep(.05)
'''

class HostTests(unittest.TestCase):
    def start(self, *, healthy=True, occupied=False):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        root = Path(temp.name); source = root/'source'; (source/'scripts').mkdir(parents=True)
        (source/'scripts/stiller_ops.py').write_text(FAKE_OPS)
        marker = root/'pid'
        config = root/'config.json'; config.write_text(json.dumps(dict(marker=str(marker), occupied=occupied,
            STBRAIN_MCP_TOKEN='synthetic-private-secret', STBRAIN_GATEWAY_PORT='18861', STBRAIN_MCP_PORT='18862')))
        driver = root/'driver.py'; driver.write_text('''import importlib.util, sys
s = importlib.util.spec_from_file_location("host", sys.argv[1]); m = importlib.util.module_from_spec(s); s.loader.exec_module(m)
raise SystemExit(m.supervise(sys.argv[2], sys.argv[3], health_check=lambda _: sys.argv[4]=="yes", startup_seconds=.3, interval=.05))
''')
        child = subprocess.Popen([sys.executable,'-B',str(driver),str(HOST),str(source),str(config),'yes' if healthy else 'no'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: self.cleanup(child))
        return child, marker
    @staticmethod
    def cleanup(child):
        if child.poll() is None:
            child.stdin.close()
            try: child.wait(timeout=5)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        for stream in (child.stdin, child.stdout, child.stderr): stream.close()
    def read_to(self, child, state):
        lines=[]; end=time.monotonic()+6
        while time.monotonic()<end:
            if select.select([child.stdout],[],[],.2)[0]:
                line=child.stdout.readline()
                if not line: break
                lines.append(line)
                if json.loads(line)['state']==state: return b''.join(lines)
        self.fail('no '+state+' frame')
    def assert_child_gone(self, marker):
        pid=int(marker.read_text())
        with self.assertRaises(ProcessLookupError): os.kill(pid,0)
    def test_survives_return_then_eof_stops_only_owned_stack(self):
        child,marker=self.start(); output=self.read_to(child,'ready')
        time.sleep(.15); self.assertIsNone(child.poll())
        # Another process is never signalled by the host.
        unrelated=subprocess.Popen([sys.executable,'-c','import time; time.sleep(20)'])
        try:
            child.stdin.close(); child.wait(timeout=6)
            output+=child.stdout.read(); self.assertNotIn(b'synthetic-private-secret', output+child.stderr.read())
            self.assert_child_gone(marker); self.assertIsNone(unrelated.poll())
        finally: unrelated.terminate(); unrelated.wait()
    def test_signal_stops_stack_and_reports_no_credentials(self):
        child,marker=self.start(); self.read_to(child,'ready'); child.send_signal(signal.SIGTERM)
        child.wait(timeout=6); self.assert_child_gone(marker)
    def test_startup_failure_never_reports_ready_and_cleans_children(self):
        child,marker=self.start(healthy=False); child.wait(timeout=6)
        output=child.stdout.read(); self.assertNotIn(b'"ready"',output); self.assertIn(b'st_startup_timeout',output)
        self.assert_child_gone(marker)
    def test_occupied_port_fails_before_start(self):
        child,marker=self.start(occupied=True); child.wait(timeout=6)
        output=child.stdout.read()+child.stderr.read(); self.assertFalse(marker.exists())
        self.assertIn(b'st_start_failed',output); self.assertNotIn(b'synthetic-private-secret',output)

if __name__ == '__main__': unittest.main()
