"""Bounded Mac display recovery and exact-target native-input guards."""
import ctypes
import pathlib
import re
import subprocess
import time


class SessionFailure(RuntimeError):
    def __init__(self, status, reason):
        super().__init__(reason)
        self.status = status


def verify_identity(pid, command, app, profile, http_port, jmx_port=None):
    launcher = str(pathlib.Path(app) / 'Contents/MacOS/Mihon Desktop')
    expected = {'test-profile': str(profile), 'test-http-port': str(http_port)}
    if jmx_port is not None:
        expected['test-jmx-port'] = str(jmx_port)
    valid = isinstance(pid, int) and pid > 0 and command.startswith(launcher + ' ')
    valid = valid and re.search(r'(?:^|\s)--test-mode(?:\s|$)', command) and '--headless' not in command
    for name, value in expected.items():
        valid = valid and len(re.findall(r'(?:^|\s)--' + name + '=', command)) == 1
        valid = valid and re.search(r'(?:^|\s)--' + name + '=' + re.escape(value) + r'(?:\s|$)', command)
    valid = valid and re.search(r'(?:^|\s)--test-jmx-port=\d+(?:\s|$)', command)
    if not valid:
        raise SessionFailure('ENV_BLOCKED', 'Observed PID/app/profile/ports do not identify the requested visible instance')


def frontmost_application():
    ctypes.CDLL('/System/Library/Frameworks/AppKit.framework/AppKit')
    # NSWorkspace notifications are delivered on the calling process's runloop.
    # Drain a bounded public default-mode slice before each observation, including
    # in CLI/SSH processes that do not otherwise run an AppKit event loop.
    cf = ctypes.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    cf.CFRunLoopRunInMode.argtypes = [ctypes.c_void_p, ctypes.c_double, ctypes.c_bool]
    cf.CFRunLoopRunInMode.restype = ctypes.c_int32
    mode = ctypes.c_void_p.in_dll(cf, 'kCFRunLoopDefaultMode')
    objc = ctypes.CDLL('/usr/lib/libobjc.A.dylib')
    objc.objc_getClass.argtypes = [ctypes.c_char_p]
    objc.objc_getClass.restype = ctypes.c_void_p
    objc.sel_registerName.argtypes = [ctypes.c_char_p]
    objc.sel_registerName.restype = ctypes.c_void_p
    send = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)(('objc_msgSend', objc))
    send_pid = ctypes.CFUNCTYPE(ctypes.c_int32, ctypes.c_void_p, ctypes.c_void_p)(('objc_msgSend', objc))
    send_text = ctypes.CFUNCTYPE(ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p)(('objc_msgSend', objc))
    workspace = send(objc.objc_getClass(b'NSWorkspace'), objc.sel_registerName(b'sharedWorkspace'))
    cf.CFRunLoopRunInMode(mode, 0.02, False)
    app = send(workspace, objc.sel_registerName(b'frontmostApplication')) if workspace else None
    if not app:
        return None, None
    bundle = send(app, objc.sel_registerName(b'bundleIdentifier'))
    text = send_text(bundle, objc.sel_registerName(b'UTF8String')) if bundle else None
    return send_pid(app, objc.sel_registerName(b'processIdentifier')), text.decode('utf-8') if text else None


def observe_session(include_foreground=True):
    cg = ctypes.CDLL('/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics')
    cf = ctypes.CDLL('/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation')
    cg.CGMainDisplayID.restype = ctypes.c_uint32
    for name in ('CGDisplayIsOnline', 'CGDisplayIsAsleep'):
        getattr(cg, name).argtypes = [ctypes.c_uint32]
        getattr(cg, name).restype = ctypes.c_uint32
    display = cg.CGMainDisplayID()
    online = bool(display) and bool(cg.CGDisplayIsOnline(display))
    state = dict(online=online, asleep=bool(cg.CGDisplayIsAsleep(display)) if online else None,
                 sessionPresent=False, reportedLockFlag=None, onConsole=None)
    cg.CGSessionCopyCurrentDictionary.restype = ctypes.c_void_p
    cf.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_uint32]
    cf.CFStringCreateWithCString.restype = ctypes.c_void_p
    cf.CFDictionaryGetValue.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    cf.CFDictionaryGetValue.restype = ctypes.c_void_p
    cf.CFBooleanGetValue.argtypes = [ctypes.c_void_p]
    cf.CFBooleanGetValue.restype = ctypes.c_bool
    cf.CFGetTypeID.argtypes = [ctypes.c_void_p]
    cf.CFGetTypeID.restype = ctypes.c_ulong
    cf.CFBooleanGetTypeID.restype = ctypes.c_ulong
    cf.CFRelease.argtypes = [ctypes.c_void_p]
    session = cg.CGSessionCopyCurrentDictionary()
    if session:
        state['sessionPresent'] = True
        try:
            for name, field in ((b'CGSSessionScreenIsLocked', 'reportedLockFlag'), (b'kCGSessionOnConsoleKey', 'onConsole')):
                key = cf.CFStringCreateWithCString(None, name, 0x08000100)
                if not key:
                    raise SessionFailure('TOOL_FAIL', 'Cannot allocate graphical-session metadata key')
                try:
                    value = cf.CFDictionaryGetValue(session, key)
                    if value:
                        if cf.CFGetTypeID(value) != cf.CFBooleanGetTypeID():
                            raise SessionFailure('TOOL_FAIL', 'Unsupported graphical-session boolean metadata')
                        state[field] = bool(cf.CFBooleanGetValue(value))
                finally:
                    cf.CFRelease(key)
        finally:
            cf.CFRelease(session)
    cg.CGPreflightPostEventAccess.restype = ctypes.c_bool
    state['nativePermission'] = bool(cg.CGPreflightPostEventAccess())
    if include_foreground:
        state['frontmostPid'], state['frontmostBundleId'] = frontmost_application()
    return state


class SessionGuard:
    def __init__(self, pid, observe, target, wake=None, sleep=time.sleep, clock=time.monotonic, timeout=8):
        self.pid, self.observe, self.target = pid, observe, target
        self.wake = wake or (lambda: subprocess.Popen(['caffeinate', '-u', '-t', '8']))
        self.sleep, self.clock, self.timeout = sleep, clock, timeout
        self.attempted = False
        self.recovery = None
        self.helper = None
        self.last = None

    def prepare(self):
        state = self.observe()
        self.last = state
        if not state.get('sessionPresent') or not state.get('online'):
            raise SessionFailure('ENV_BLOCKED', 'Graphical session or online display is unavailable')
        if state.get('onConsole') is False:
            raise SessionFailure('ENV_BLOCKED', 'Observed session is not on the console; target readiness is unconfirmed')
        conflict = state.get('asleep') is not False or state.get('reportedLockFlag') is True or state.get('frontmostBundleId') == 'com.apple.loginwindow'
        if conflict and not self.attempted:
            self.attempted = True
            self.recovery = dict(before=state, reason='Display/session signals require one user-activity wake and re-observation')
            self.helper = self.wake()
            deadline = self.clock() + self.timeout
            while self.clock() < deadline:
                self.sleep(0.25)
                state = self.observe()
                self.recovery['after'] = state
                if (state.get('online') and state.get('asleep') is False and state.get('sessionPresent')
                        and state.get('onConsole') is not False and state.get('reportedLockFlag') is not True):
                    break
        self.last = state
        if not state.get('sessionPresent') or state.get('onConsole') is False or not state.get('online') or state.get('asleep') is not False:
            raise SessionFailure('ENV_BLOCKED', 'Display/session did not become observable and awake after one bounded recovery')
        if state.get('reportedLockFlag') is True:
            raise SessionFailure('ENV_BLOCKED', 'Recovery still reports a lock signal/session conflict; native input stopped')
        if not state.get('nativePermission'):
            raise SessionFailure('ENV_BLOCKED', 'Native event permission is unavailable; no permission request was made')
        return state

    def read_target(self):
        try:
            return self.target()
        except SessionFailure:
            raise
        except RuntimeError as error:
            raise SessionFailure('TOOL_FAIL', 'Native target observation failed: ' + str(error)) from error

    def check(self):
        state = self.prepare()
        target = self.read_target()
        if state.get('frontmostPid') != self.pid or not target or target.get('pid') != self.pid or not all(target.get(key) for key in ('focused', 'onScreen', 'hitTarget')):
            raise SessionFailure('ENV_BLOCKED', 'Exact target is not foreground, focused, on-screen, or the native hit owner')
        self.last = dict(state, readiness='target-ready', target=target)
        return self.last

    def activate(self, activate_pid):
        self.prepare()
        if not activate_pid():
            raise SessionFailure('TOOL_FAIL', 'Activation of the verified application PID failed')
        deadline = self.clock() + 3
        while self.clock() < deadline:
            state = self.prepare()
            if state.get('frontmostPid') == self.pid:
                target = self.read_target()
                if target and target.get('pid') == self.pid and all(target.get(key) for key in ('focused', 'onScreen', 'hitTarget')):
                    return self.check()
            self.sleep(0.1)
        raise SessionFailure('TOOL_FAIL', 'Activation did not establish exact foreground/focused/on-screen/native-hit target within three seconds')

    def close(self):
        if self.helper is not None and self.helper.poll() is None:
            self.helper.terminate()
            self.helper.wait(timeout=2)
