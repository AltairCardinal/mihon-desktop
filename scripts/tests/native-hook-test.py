"""Focused regression tests for the production native hook and Windows wiring."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / '.codex/hooks/native_gate.py'
WRAPPER = ROOT / '.codex/hooks/native_gate.ps1'
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

def run(args, payload, cwd, env):
    return subprocess.run(args, input=payload, text=True, encoding='utf-8',
                          capture_output=True, cwd=cwd, env=env, timeout=12,
                          creationflags=FLAGS)

class NativePolicyTest(unittest.TestCase):
    def test_public_policy(self):
        cases = [('echo hello', 0), ('./scripts/build-desktop.sh', 0),
                 ('./gradlew :app-desktop:jvmTest', 0),
                 ('./gradlew :app-desktop:createDistributable', 2),
                 ('env A=1 ./gradlew.bat packageMsi', 2)]
        for command, expected in cases:
            with self.subTest(command=command):
                p = run([sys.executable, '-X', 'utf8', str(GATE)],
                        json.dumps({'tool_input': {'command': command}}), ROOT, os.environ.copy())
                self.assertEqual(p.returncode, expected, p.stderr)
                self.assertEqual(p.stdout, '')
                if expected == 2:
                    self.assertIn('禁止直接调用', p.stderr)

@unittest.skipUnless(os.name == 'nt', 'Windows integration')
class WindowsWiringTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='native-hook-regression-')
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        builtin = str(Path(os.environ.get('SystemRoot', 'C:/Windows')) /
                      'System32/WindowsPowerShell/v1.0/powershell.exe')
        self.shells = list(dict.fromkeys(filter(None, [os.environ.get('CODEX_HOOK_POWERSHELL'), builtin])))
        self.command = json.loads((ROOT / '.codex/hooks.json').read_text(encoding='utf-8'))['hooks']['PreToolUse'][0]['hooks'][0]['commandWindows']

    def configured(self, shell, payload, log):
        env = os.environ.copy()
        env['CODEX_NATIVE_HOOK_LOG'] = str(log)
        return run([shell, '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', self.command],
                   payload, ROOT / 'scripts', env)

    def test_configured_policy_and_events(self):
        for index, shell in enumerate(self.shells):
            for case, payload, code, event in [
                ('allow', json.dumps({'tool_input': {'command': './scripts/build-desktop.sh --PRIVATE_SENTINEL'}}), 0, 'check_allowed'),
                ('deny', json.dumps({'tool_input': {'command': './gradlew packageMsi', 'private': 'PRIVATE_SENTINEL'}}, ensure_ascii=False), 2, 'check_denied'),
                ('invalid', '{PRIVATE_SENTINEL', 0, 'check_failed_open')]:
                with self.subTest(shell=shell, case=case):
                    log = self.folder / f'{index}-{case}.jsonl'
                    p = self.configured(shell, payload, log)
                    self.assertEqual(p.returncode, code, p.stderr)
                    self.assertEqual(p.stdout, '')
                    raw = log.read_text(encoding='utf-8')
                    entries = [json.loads(line) for line in raw.splitlines()]
                    self.assertEqual([e['event'] for e in entries], ['check_started', event])
                    self.assertEqual(entries[0]['runId'], entries[1]['runId'])
                    self.assertNotIn('PRIVATE_SENTINEL', raw)

    def test_log_failure_preserves_denial(self):
        blocked = self.folder / 'blocked'
        blocked.write_text('not a directory', encoding='utf-8')
        for shell in self.shells:
            p = self.configured(shell, json.dumps({'tool_input': {'command': './gradlew packageMsi'}}), blocked / 'gate.jsonl')
            self.assertEqual(p.returncode, 2, p.stderr)
            self.assertIn('Hook log unavailable', p.stderr)
            self.assertIn('禁止直接调用', p.stderr)

    def test_missing_python_is_logged_and_allowed(self):
        for index, shell in enumerate(self.shells):
            log = self.folder / f'{index}-missing.jsonl'
            p = run([shell, '-NoProfile', '-NonInteractive', '-File', str(WRAPPER),
                     '-PythonPath', str(self.folder / 'missing.exe'), '-LogPath', str(log)],
                    '{}', ROOT, os.environ.copy())
            self.assertEqual(p.returncode, 0, p.stderr)
            outcome = json.loads(log.read_text(encoding='utf-8').splitlines()[-1])
            self.assertEqual((outcome['event'], outcome['category']), ('check_failed_open', 'python_missing'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
