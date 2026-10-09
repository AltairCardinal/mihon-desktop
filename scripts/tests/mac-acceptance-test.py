import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
import urllib.error
from types import SimpleNamespace
from unittest.mock import Mock

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), SCRIPTS / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def observed(asleep=False, lock=None, session=True, foreground=42):
    return dict(online=True, asleep=asleep, sessionPresent=session, reportedLockFlag=lock,
                nativePermission=True, frontmostPid=foreground, frontmostBundleId='mihon.desktop')


class SessionGuardTest(unittest.TestCase):
    def setUp(self):
        self.module = load('mac_acceptance_session')
        self.target = Mock(return_value=dict(pid=42, focused=True, onScreen=True, hitTarget=True))

    def guard(self, states):
        iterator = iter(states)
        last = states[-1]
        probe = lambda: next(iterator, last)
        wake = Mock()
        clock = Mock(side_effect=range(100))
        return self.module.SessionGuard(42, probe, self.target, wake=wake, sleep=lambda _: None,
                                        clock=clock, timeout=4), wake

    def test_display_sleep_lock_flag_can_recover_to_target_ready_without_lock_flag(self):
        guard, wake = self.guard([observed(asleep=True, lock=True, foreground=7), observed()])
        result = guard.check()
        self.assertEqual('target-ready', result['readiness'])
        self.assertIsNone(result['reportedLockFlag'])
        wake.assert_called_once()
        self.assertTrue(guard.recovery['before']['asleep'])
        self.assertFalse(guard.recovery['after']['asleep'])

    def test_awake_still_locked_sends_no_native_input(self):
        guard, wake = self.guard([observed(asleep=True, lock=True), observed(lock=True)])
        native = load('mac-sync-native-acceptance')
        events = native.NativeEvents.__new__(native.NativeEvents)
        events.pid, events.session = 42, guard
        events.cg, events.cf = Mock(), Mock()
        with self.assertRaises(self.module.SessionFailure):
            events.key(48)
        events.cg.CGEventCreateKeyboardEvent.assert_not_called()
        wake.assert_called_once()

    def test_missing_session_or_target_and_wrong_pid_never_send_input(self):
        for state, target in ((observed(session=False), None), (observed(), None),
                              (observed(), dict(pid=7, focused=True, onScreen=True, hitTarget=True))):
            with self.subTest(state=state, target=target):
                guard, _ = self.guard([state])
                self.target.return_value = target
                with self.assertRaises(self.module.SessionFailure):
                    guard.check()

    def test_activation_failure_is_tool_failure_not_ssh_gui_diagnosis(self):
        guard, _ = self.guard([observed(foreground=7)])
        with self.assertRaises(self.module.SessionFailure) as caught:
            guard.activate(lambda: False)
        self.assertEqual('TOOL_FAIL', caught.exception.status)
        self.assertNotIn('SSH', str(caught.exception))

    def test_wake_timeout_has_one_attempt_even_if_check_called_again(self):
        guard, wake = self.guard([observed(asleep=True, lock=True)])
        for _ in range(2):
            with self.assertRaises(self.module.SessionFailure):
                guard.check()
        wake.assert_called_once()

    def test_wake_waits_for_transient_lock_signal_to_clear(self):
        guard, wake = self.guard([observed(asleep=True, lock=True), observed(lock=True), observed()])
        self.assertEqual('target-ready', guard.check()['readiness'])
        wake.assert_called_once()

    def test_awake_lock_absence_allows_input_free_launch_despite_loginwindow_metadata(self):
        pending = dict(observed(), frontmostPid=176, frontmostBundleId='com.apple.loginwindow')
        guard, wake = self.guard([observed(asleep=True, lock=True), pending, pending, observed()])
        result = guard.prepare()
        self.assertFalse(result['asleep'])
        self.assertIsNone(result['reportedLockFlag'])
        wake.assert_called_once()
        with self.assertRaises(self.module.SessionFailure):
            guard.check()
        self.assertEqual('target-ready', guard.activate(lambda: True)['readiness'])
        wake.assert_called_once()

    def test_frontmost_observation_refreshes_public_runloop_on_each_call(self):
        import ctypes
        from unittest.mock import patch
        fresh = []
        cf = SimpleNamespace(CFRunLoopRunInMode=Mock(side_effect=lambda *args: fresh.append(True)))
        objc = SimpleNamespace(objc_getClass=Mock(return_value=1), sel_registerName=Mock(side_effect=lambda value: value))
        def factory(result_type, *types):
            if result_type is ctypes.c_int32:
                return lambda address: lambda *args: 42 if fresh else 176
            if result_type is ctypes.c_char_p:
                return lambda address: lambda *args: b'mihon.desktop'
            return lambda address: lambda *args: 1
        with patch.object(self.module.ctypes, 'CDLL', side_effect=lambda path: cf if 'CoreFoundation' in path else objc), \
                patch.object(self.module.ctypes, 'CFUNCTYPE', side_effect=factory), \
                patch.object(self.module.ctypes.c_void_p, 'in_dll', return_value=ctypes.c_void_p(1)):
            self.assertEqual(42, self.module.frontmost_application()[0])
            self.assertEqual(42, self.module.frontmost_application()[0])
        self.assertEqual(2, cf.CFRunLoopRunInMode.call_count)

    def test_activation_waits_for_target_focus_within_same_bound(self):
        guard, _ = self.guard([observed()])
        self.target.side_effect = [dict(pid=42, focused=False, onScreen=True, hitTarget=True),
                                   dict(pid=42, focused=True, onScreen=True, hitTarget=True),
                                   dict(pid=42, focused=True, onScreen=True, hitTarget=True)]
        self.assertEqual('target-ready', guard.activate(lambda: True)['readiness'])

    def test_exact_command_identity_rejects_wrong_profile_port_or_headless(self):
        good = str(pathlib.Path('/tmp/isolated.app') / 'Contents/MacOS/Mihon Desktop') + ' --test-mode --test-profile=/tmp/profile --test-http-port=59463 --test-jmx-port=59464'
        self.module.verify_identity(42, good, '/tmp/isolated.app', '/tmp/profile', 59463, 59464)
        for bad in (good.replace('/tmp/profile', '/tmp/other'), good.replace('59463', '59465'), good + ' --headless'):
            with self.subTest(bad=bad), self.assertRaises(self.module.SessionFailure):
                self.module.verify_identity(42, bad, '/tmp/isolated.app', '/tmp/profile', 59463, 59464)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.module = load('mac-acceptance')
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name).resolve()
        self.app = self.root / 'Isolated.app'
        launcher = self.app / 'Contents/MacOS/Mihon Desktop'
        launcher.parent.mkdir(parents=True)
        launcher.write_bytes(b'fixture')
        self.args = SimpleNamespace(app=str(self.app), profile=str(self.root / 'new-profile'),
                                    http_port=59463, jmx_port=59464, output=str(self.root / 'result.json'), scenario='sync-main')
        self.calls = []
        owner = self

        class Backend:
            platform = 'darwin'
            pid = 42
            alive_value = True
            scene_failure = False
            wrong_port = False
            wrapper_stuck = False
            helper_fail = False
            missing_ui = False
            jmx_listening = True

            def port_free(self, port): return True
            def running_commands(self): return []
            def spawn(self, command):
                owner.calls.append(command)
                def wait(timeout):
                    if self.helper_fail: raise OSError('helper cleanup failed')
                    return 0
                return SimpleNamespace(pid=101 + len(owner.calls), poll=lambda: 0 if command[0] == 'open' and not self.alive_value and not self.wrapper_stuck else None,
                                       terminate=lambda: owner.calls.append(['terminate-helper']), wait=wait)
            def health(self, port): return {'status': 'ok'}
            def ui(self, port):
                if self.missing_ui: raise urllib.error.HTTPError('http://127.0.0.1/test/sync/ui', 404, 'not found', {}, io.BytesIO(b''))
                return {'pid': self.pid}
            def command(self, pid):
                return str(launcher) + ' --test-mode --test-profile=' + owner.args.profile + ' --test-http-port=' + str(59465 if self.wrong_port else 59463) + ' --test-jmx-port=59464'
            def port_owner(self, port): return [] if port == 59464 and not self.jmx_listening else [self.pid]
            def session_guard(self):
                return owner.module.SessionGuard(None, lambda: observed(), lambda: None)
            def scene(self, args, pid, session):
                self.received_session = session
                owner.calls.append(['native-scene', str(pid)])
                if self.scene_failure: raise RuntimeError('native assertion failed')
                return dict(readiness='target-ready', reportedLockFlag=None)
            def shutdown(self, port):
                owner.calls.append(['shutdown-owned', str(port)])
                self.alive_value = False
            def alive(self, pid): return self.alive_value
            def sleep(self, seconds): pass

        self.backend = Backend()

    def tearDown(self): self.temporary.cleanup()

    def result(self):
        return json.loads(pathlib.Path(self.args.output).read_text(encoding='utf-8'))

    def test_launchservices_native_scene_and_only_owned_helper_cleanup(self):
        self.assertEqual(0, self.module.run(self.args, self.backend))
        launch = self.calls[0]
        self.assertEqual(['open', '-n', '-W', '-a', str(self.app), '--args'], launch[:6])
        self.assertNotIn('--headless', launch)
        self.assertIn('--test-profile=' + self.args.profile, launch)
        self.assertIn(['caffeinate', '-di', '-w', '42'], self.calls)
        self.assertIn(['native-scene', '42'], self.calls)
        self.assertIn(['shutdown-owned', '59463'], self.calls)
        self.assertEqual(1, self.calls.count(['terminate-helper']))
        self.assertEqual('PASS', self.result()['status'])

    def test_failure_still_writes_json_and_naturally_shuts_down_exact_instance(self):
        self.backend.scene_failure = True
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual('PRODUCT_FAIL', self.result()['status'])
        self.assertEqual('PASS', self.result()['shutdown']['status'])

    def test_wrong_process_port_blocks_native_actions_and_shutdown(self):
        self.backend.wrong_port = True
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual('ENV_BLOCKED', self.result()['status'])
        self.assertFalse(any(c[0] in ('native-scene', 'shutdown-owned') for c in self.calls))

    def test_http_owned_candidate_does_not_require_unused_jmx_listener(self):
        self.backend.jmx_listening = False
        self.assertEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual('PASS', self.result()['identity']['status'])

    def test_missing_ui_is_candidate_capability_failure_without_retry_and_still_shutdown(self):
        self.backend.missing_ui = True
        self.backend.sleep = Mock(side_effect=AssertionError('404 must not be retried'))
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        report = self.result()
        self.assertEqual('ENV_BLOCKED', report['status'])
        self.assertEqual('CANDIDATE_CAPABILITY_MISSING', report['capability']['reasonCode'])
        self.assertEqual(404, report['capability']['httpStatus'])
        self.assertEqual('PASS', report['shutdown']['status'])
        self.assertFalse(any(c[0] == 'native-scene' for c in self.calls))

    def test_existing_profile_or_busy_port_refuses_launch(self):
        pathlib.Path(self.args.profile).mkdir()
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual([], self.calls)

        pathlib.Path(self.args.profile).rmdir()
        self.backend.port_free = lambda _: False
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual([], self.calls)

    def test_prelaunch_recovery_guard_is_reused_by_native_scene(self):
        states = iter([observed(asleep=True, lock=True), observed()])
        session = self.module.SessionGuard(None, lambda: next(states, observed()), lambda: None,
                                          wake=lambda: self.backend.spawn(['caffeinate', '-u', '-t', '8']), sleep=lambda _: None)
        self.backend.session_guard = lambda: session
        self.assertEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual(['caffeinate', '-u', '-t', '8'], self.calls[0])
        self.assertEqual('open', self.calls[1][0])
        self.assertIs(session, self.backend.received_session)
        self.assertTrue(self.result()['session']['recovery']['before']['asleep'])

    def test_helper_cleanup_failure_cannot_coexist_with_pass(self):
        self.backend.helper_fail = True
        self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertEqual('TOOL_FAIL', self.result()['helperCleanup']['status'])

    def test_wrapper_still_alive_after_same_shutdown_bound_cannot_pass(self):
        from unittest.mock import patch
        self.backend.wrapper_stuck = True
        with patch.object(self.module.time, 'monotonic', side_effect=[0, 0, 30]):
            self.assertNotEqual(0, self.module.run(self.args, self.backend))
        self.assertFalse(self.result()['launch']['wrapperExited'])
        self.assertEqual('ENV_BLOCKED', self.result()['shutdown']['status'])


if __name__ == '__main__':
    unittest.main()
