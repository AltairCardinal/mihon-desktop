#!/usr/bin/env python3
"""Launch and verify one isolated Mac sync-main scenario through LaunchServices."""
import argparse
import contextlib
import importlib.util
import io
import json
import pathlib
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from mac_acceptance_session import SessionFailure, SessionGuard, observe_session, verify_identity


def launch_command(args):
    return ['open', '-n', '-W', '-a', str(args.app), '--args', '--test-mode',
            '--test-profile=' + str(args.profile), '--test-http-port=' + str(args.http_port),
            '--test-jmx-port=' + str(args.jmx_port)]


class MacBackend:
    platform = sys.platform

    def __init__(self):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.session_report = {}

    def session_guard(self):
        return SessionGuard(None, observe_session, lambda: None)

    def port_free(self, port):
        with socket.socket() as listener:
            try:
                listener.bind(('127.0.0.1', port))
                return True
            except OSError:
                return False

    def running_commands(self):
        return subprocess.check_output(['ps', '-axo', 'command='], text=True, encoding='utf-8').splitlines()

    def command(self, pid):
        return subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True, encoding='utf-8').strip()

    def port_owner(self, port):
        result = subprocess.run(['lsof', '-nP', '-a', '-iTCP:' + str(port), '-sTCP:LISTEN', '-t'],
                                capture_output=True, text=True, encoding='utf-8')
        if result.returncode not in (0, 1):
            raise SessionFailure('TOOL_FAIL', 'Cannot observe listening-port ownership')
        return sorted(set(int(pid) for pid in result.stdout.split()))

    def spawn(self, command):
        return subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def request(self, port, path, method='GET'):
        request = urllib.request.Request('http://127.0.0.1:' + str(port) + path, method=method)
        with self.opener.open(request, timeout=3) as response:
            return json.loads(response.read().decode('utf-8'))

    def ui(self, port):
        return self.request(port, '/test/sync/ui')

    def health(self, port):
        return self.request(port, '/test/health')

    def shutdown(self, port):
        return self.request(port, '/test/shutdown', 'POST')

    def alive(self, pid):
        return subprocess.run(['ps', '-p', str(pid), '-o', 'pid='], stdout=subprocess.DEVNULL).returncode == 0

    sleep = staticmethod(time.sleep)

    def scene(self, args, pid, session):
        path = pathlib.Path(__file__).with_name('mac-sync-native-acceptance.py')
        spec = importlib.util.spec_from_file_location('mac_sync_native_acceptance', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        acceptance = module.Acceptance('http://127.0.0.1:' + str(args.http_port), args.app, args.profile, args.jmx_port, session)
        if acceptance.pid != pid:
            raise SessionFailure('ENV_BLOCKED', 'Native scene observed a different application PID')
        transcript = io.StringIO()
        try:
            with contextlib.redirect_stdout(transcript):
                acceptance.run()
            return dict(readiness='target-ready', transcript=transcript.getvalue().splitlines())
        finally:
            self.session_report = dict(recovery=acceptance.events.session.recovery, lastObservation=acceptance.events.session.last)


def owned_identity(args, backend):
    owners = backend.port_owner(args.http_port)
    if not owners:
        raise ConnectionError('Candidate HTTP listener is not ready')
    if len(owners) != 1:
        raise SessionFailure('ENV_BLOCKED', 'HTTP listening port has ambiguous ownership')
    pid = owners[0]
    verify_identity(pid, backend.command(pid), args.app, args.profile, args.http_port, args.jmx_port)
    return pid


def run(args, backend=None):
    backend = backend or MacBackend()
    report = dict(scope='Mac sync-main native acceptance', status='NOT_RUN', scenario=args.scenario,
                  app=str(args.app), profile=str(args.profile), native=dict(status='NOT_RUN'),
                  launch=dict(status='NOT_RUN'), shutdown=dict(status='NOT_RUN', reason='No verified instance has been bound'))
    owned_pid = None
    hold = None
    wrapper = None
    session = None
    native_started = False
    try:
        app, profile = pathlib.Path(args.app), pathlib.Path(args.profile)
        if backend.platform != 'darwin' or not app.is_absolute() or app.suffix != '.app' or not (app / 'Contents/MacOS/Mihon Desktop').is_file():
            raise SessionFailure('ENV_BLOCKED', 'Run on the Mac with the exact existing packaged application')
        args.app = str(app.resolve(strict=True))
        if not profile.is_absolute() or profile.exists() or profile.is_symlink():
            raise SessionFailure('ENV_BLOCKED', 'A new absolute profile directory is required; existing profiles are untouched')
        args.profile = str(profile.resolve())
        if args.http_port == args.jmx_port or any(not 1 <= port <= 65535 or not backend.port_free(port) for port in (args.http_port, args.jmx_port)):
            raise SessionFailure('ENV_BLOCKED', 'Distinct unused HTTP/JMX ports are required')
        launcher = str(pathlib.Path(args.app) / 'Contents/MacOS/Mihon Desktop')
        if any(command.startswith(launcher + ' ') or '--test-profile=' + args.profile in command for command in backend.running_commands()):
            raise SessionFailure('ENV_BLOCKED', 'The requested app/profile already has an instance; no duplicate launch was issued')
        session = backend.session_guard()
        session.prepare()
        profile.mkdir(parents=True, exist_ok=False)
        wrapper = backend.spawn(launch_command(args))
        report['launch'] = dict(status='PASS', wrapperPid=wrapper.pid, method='LaunchServices')
        deadline = time.monotonic() + 30
        while True:
            try:
                owned_pid = owned_identity(args, backend)
                health = backend.health(args.http_port)
                if health.get('status') != 'ok':
                    raise SessionFailure('ENV_BLOCKED', 'Candidate does not expose a healthy Test Mode service')
                break
            except urllib.error.HTTPError as error:
                report['capability'] = dict(status='ENV_BLOCKED', reasonCode='CANDIDATE_HEALTH_UNAVAILABLE', httpStatus=error.code)
                error.close()
                raise SessionFailure('ENV_BLOCKED', 'Candidate Test Mode health endpoint returned HTTP ' + str(error.code)) from error
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                if time.monotonic() >= deadline:
                    raise SessionFailure('ENV_BLOCKED', 'Candidate Test Mode identity did not become observable within 30 seconds')
                backend.sleep(0.2)
        report['identity'] = dict(status='PASS', pid=owned_pid, httpPort=args.http_port, jmxArgument=args.jmx_port, jmxListenerRequired=False)
        try:
            ui = backend.ui(args.http_port)
        except urllib.error.HTTPError as error:
            report['capability'] = dict(status='ENV_BLOCKED', reasonCode='CANDIDATE_CAPABILITY_MISSING' if error.code == 404 else 'CANDIDATE_CAPABILITY_HTTP_ERROR', endpoint='/test/sync/ui', httpStatus=error.code)
            error.close()
            raise SessionFailure('ENV_BLOCKED', 'Candidate sync-main observation endpoint returned HTTP ' + str(error.code) + '; no native input was sent') from error
        if ui.get('pid') != owned_pid:
            report['capability'] = dict(status='ENV_BLOCKED', reasonCode='CANDIDATE_CAPABILITY_INCOMPATIBLE', endpoint='/test/sync/ui')
            raise SessionFailure('ENV_BLOCKED', 'Scene observation PID does not match the owned HTTP listener')
        report['capability'] = dict(status='PASS', endpoint='/test/sync/ui')
        hold = backend.spawn(['caffeinate', '-di', '-w', str(owned_pid)])
        native_started = True
        native = backend.scene(args, owned_pid, session)
        report['native'] = dict(native, status='PASS')
        report['status'] = 'PASS'
    except SessionFailure as error:
        report.update(status=error.status, reason=str(error))
        if native_started:
            report['native'] = dict(status=error.status, reason=str(error))
    except RuntimeError as error:
        report.update(status='PRODUCT_FAIL', reason=str(error))
        report['native'] = dict(status='PRODUCT_FAIL')
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        report.update(status='TOOL_FAIL', reason=type(error).__name__ + ': ' + str(error))
    finally:
        report['session'] = dict(recovery=session.recovery, lastObservation=session.last) if session else {}
        if owned_pid is not None:
            try:
                if owned_identity(args, backend) != owned_pid:
                    raise SessionFailure('ENV_BLOCKED', 'Instance identity changed before shutdown; no shutdown request issued')
                backend.shutdown(args.http_port)
                deadline = time.monotonic() + 20
                while (backend.alive(owned_pid) or wrapper.poll() is None) and time.monotonic() < deadline:
                    backend.sleep(0.2)
                report['shutdown'] = dict(status='ENV_BLOCKED' if backend.alive(owned_pid) or wrapper.poll() is None else 'PASS', pid=owned_pid,
                                          reason='Only one shutdown request; application is never force-killed')
                if report['status'] == 'PASS' and report['shutdown']['status'] != 'PASS':
                    report['status'] = 'ENV_BLOCKED'
            except (SessionFailure, OSError, ValueError, KeyError, urllib.error.URLError, subprocess.SubprocessError) as error:
                report['shutdown'] = dict(status='ENV_BLOCKED', reason=str(error))
                if report['status'] == 'PASS':
                    report['status'] = 'ENV_BLOCKED'
        try:
            if hold is not None and hold.poll() is None:
                hold.terminate()
                hold.wait(timeout=2)
            if session is not None:
                session.close()
            report['helperCleanup'] = dict(status='PASS')
        except (OSError, subprocess.SubprocessError) as error:
            report['helperCleanup'] = dict(status='TOOL_FAIL', reason='Owned caffeinate cleanup failed: ' + type(error).__name__)
            if report['status'] == 'PASS':
                report['status'] = 'TOOL_FAIL'
        if wrapper is not None:
            report['launch']['wrapperExited'] = wrapper.poll() is not None
            if owned_pid is None:
                report['shutdown'] = dict(status='NOT_RUN', reason='No verified PID was bound; no application shutdown or kill was attempted')
                if report['status'] == 'PASS':
                    report['status'] = 'ENV_BLOCKED'
        output = pathlib.Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return 0 if report['status'] == 'PASS' else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', required=True)
    parser.add_argument('--profile', required=True)
    parser.add_argument('--http-port', type=int, required=True)
    parser.add_argument('--jmx-port', type=int, required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--scenario', choices=('sync-main',), default='sync-main')
    args = parser.parse_args()
    return run(args)


if __name__ == '__main__':
    raise SystemExit(main())
