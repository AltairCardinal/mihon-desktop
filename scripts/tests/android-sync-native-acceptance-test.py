import importlib.util
import json
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import Mock
from xml.sax.saxutils import quoteattr

SPEC = importlib.util.spec_from_file_location("android_sync_native", Path(__file__).resolve().parents[1] / "android-sync-native-acceptance.py")
TOOL = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = TOOL
SPEC.loader.exec_module(TOOL)
PACKAGE = TOOL.PACKAGES[0]


def node(text="", desc="", bounds="[0,0][100,60]", clickable=False, enabled=True, children="", package=PACKAGE, **extra):
    values = {"text": text, "content-desc": desc, "bounds": bounds, "clickable": str(clickable).lower(),
              "enabled": str(enabled).lower(), "package": package, "class": "android.view.View", "password": "false"}
    values.update(extra)
    attrs = " ".join(f"{key}={quoteattr(value)}" for key, value in values.items())
    return f"<node {attrs}>{children}</node>"


def button(desc="", text="", bounds="[700,0][800,80]", **kwargs):
    return node(desc=desc, clickable=True, bounds=bounds,
                children=node(text=text, bounds=bounds) if text else "", **kwargs)


def hierarchy(scene="ENTRY", extra="", disabled=False):
    close = button(desc="关闭同步面板", bounds="[900,80][1000,160]")
    back = button(desc="返回", bounds="[0,80][100,160]")
    connect = button(text="连接 GitHub", bounds="[700,200][950,280]", enabled=not disabled)
    if scene == "ENTRY":
        body = button(desc="同步")
    elif scene == "MAIN":
        body = node(text="同步") + close + button(desc="同步设置", bounds="[600,80][700,160]")
        body += node(text="连接 GitHub", bounds="[0,180][400,220]") + connect
    elif scene == "SETTINGS":
        body = node(text="同步设置") + close + back + node(text="GitHub 账号与空间") + node(text="启动时同步")
    elif scene == "SIGN_IN":
        body = node(text="同步") + close + back + connect
        body += node(text="请在系统浏览器中授权，本应用不会要求输入 GitHub 密码。")
    else:
        body = node(text="立即同步") + close
    body += node(text="PRIVATE-TITLE-TOKEN-PASSWORD") + extra
    return '<hierarchy rotation="0">' + node(bounds="[0,0][1000,1600]", children=body) + '</hierarchy>'


UNLOCKED = "KeyguardServiceDelegate:\n showing=false\n inputRestricted=false\n screenState=SCREEN_STATE_ON\n interactiveState=INTERACTIVE_STATE_AWAKE\n"


class FakeAdb:
    def __init__(self):
        self.scene = "ENTRY"
        self.files = {}
        self.calls = []
        self.dump_error = False
        self.locked = False
        self.foreground = PACKAGE
        self.focus_reads = 0
        self.change_window_at = None

    def __call__(self, command, **kwargs):
        assert command[:3] == ["adb", "-s", "TEST-PRIVATE-SERIAL"]
        assert kwargs["encoding"] == "utf-8" and kwargs["capture_output"]
        args = command[3:]
        self.calls.append(args)
        output = ""
        status = 0
        if args == ["get-state"]:
            output = "device\n"
        elif args == ["shell", "pm", "path", PACKAGE]:
            output = "package:/synthetic/base.apk\n"
        elif args == ["shell", "dumpsys", "window", "policy"]:
            output = UNLOCKED.replace("showing=false", "showing=true") if self.locked else UNLOCKED
        elif args == ["shell", "dumpsys", "window"]:
            self.focus_reads += 1
            token = "def" if self.change_window_at and self.focus_reads >= self.change_window_at else "abc"
            output = f"mCurrentFocus=Window{{{token} u0 {self.foreground}/Main}}\n"
        elif args == ["shell", "wm", "size"]:
            output = "Physical size: 1000x1600\n"
        elif args[:4] == ["shell", "test", "!", "-e"]:
            status = int(args[4] in self.files)
        elif args[:4] == ["shell", "uiautomator", "dump", "--compressed"]:
            if self.dump_error:
                output = "ERROR: null root node returned by UiTestAutomationBridge\n"
            else:
                self.files[args[4]] = hierarchy(self.scene)
                output = f"UI hierchary dumped to: {args[4]}\n"
        elif args[:3] == ["shell", "chmod", "600"]:
            assert args[3] in self.files
        elif args[:4] == ["shell", "stat", "-c", "%s"]:
            output = str(len(self.files[args[4]].encode("utf-8")))
        elif args[:2] == ["exec-out", "cat"]:
            output = self.files[args[2]]
        elif args[:3] == ["shell", "rm", "-f"]:
            self.files.pop(args[3], None)
        elif args[:3] == ["shell", "input", "tap"]:
            point = tuple(map(int, args[3:]))
            self.scene = {("ENTRY", (750, 40)): "MAIN", ("MAIN", (650, 120)): "SETTINGS",
                          ("MAIN", (825, 240)): "SIGN_IN", ("MAIN", (950, 120)): "ENTRY"}[(self.scene, point)]
        elif args == ["shell", "input", "keyevent", "KEYCODE_BACK"]:
            assert self.scene in ("SETTINGS", "SIGN_IN")
            self.scene = "MAIN"
        else:
            raise AssertionError("Unexpected command in production navigation transport")
        return subprocess.CompletedProcess(command, status, output, "")


class AndroidNativeNavigationTest(unittest.TestCase):
    def snapshot(self, scene="ENTRY", **kwargs):
        return TOOL.parse_hierarchy(hierarchy(scene, **kwargs), PACKAGE, (1000, 1600))

    def test_known_scenes_and_reports_discard_all_private_text(self):
        for scene in ("ENTRY", "MAIN", "SETTINGS", "SIGN_IN"):
            with self.subTest(scene=scene):
                snap = self.snapshot(scene)
                self.assertEqual(scene, snap.scene)
                report = json.dumps(snap.report(PACKAGE))
                self.assertNotIn("PRIVATE", report)
                self.assertNotIn("hierarchy", report)
                self.assertNotIn("GitHub 密码", report)

    def test_click_uses_actual_clickable_parent_not_label_bounds(self):
        root = ET.fromstring(hierarchy("MAIN"))
        parent = next(node for node in root.iter("node") if node.get("bounds") == "[700,200][950,280]" and node.get("clickable") == "true")
        parent[0].set("bounds", "[740,220][920,260]")
        xml = ET.tostring(root, encoding="unicode")
        snap = TOOL.parse_hierarchy(xml, PACKAGE, (1000, 1600))
        self.assertEqual((825, 240), TOOL.locate(snap, "local_setup", "MAIN").bounds.point())

    def test_actual_modal_container_does_not_turn_header_or_card_icon_into_buttons(self):
        close = button(desc="关闭同步面板", bounds="[1880,611][1976,707]")
        settings = button(desc="同步设置", bounds="[1784,611][1880,707]")
        connect = button(text="连接 GitHub", bounds="[1697,757][1952,853]")
        body = node(text="同步") + node(desc="同步") + node(text="连接 GitHub") + close + settings + connect
        sheet = node(bounds="[880,595][2000,1920]", clickable=True, children=body)
        xml = '<hierarchy rotation="1">' + sheet + '</hierarchy>'
        snap = TOOL.parse_hierarchy(xml, PACKAGE, (1920, 2880))
        self.assertEqual("MAIN", snap.scene)
        self.assertEqual(0, len(snap.controls["open"]))
        self.assertEqual(1, len(snap.controls["local_setup"]))
        self.assertEqual((1824, 805), TOOL.locate(snap, "local_setup", "MAIN").bounds.point())

    def test_duplicate_matches_refuse_input(self):
        snap = self.snapshot(extra=button(desc="同步", bounds="[500,0][600,80]"))
        with self.assertRaisesRegex(TOOL.Stop, "AMBIGUOUS_CONTROL"):
            TOOL.locate(snap, "open", "ENTRY")

    def test_disabled_parent_refuses_click_even_if_label_enabled(self):
        with self.assertRaisesRegex(TOOL.Stop, "DISABLED_CONTROL"):
            TOOL.locate(self.snapshot("MAIN", disabled=True), "local_setup", "MAIN")

    def test_authorization_connect_on_sign_in_is_never_a_navigation_target(self):
        with self.assertRaisesRegex(TOOL.Stop, "FORBIDDEN_CONTROL"):
            TOOL.locate(self.snapshot("SIGN_IN"), "local_setup", "SIGN_IN")

    def test_connected_unknown_scene_and_wrong_expected_scene_refuse_input(self):
        with self.assertRaisesRegex(TOOL.Stop, "CONNECTED_OR_ACTIVE_SCENE"):
            self.snapshot("CONNECTED")
        with self.assertRaisesRegex(TOOL.Stop, "UNEXPECTED_SCENE"):
            TOOL.locate(self.snapshot("SETTINGS"), "local_setup", "MAIN")

    def test_password_and_editable_hierarchies_stop_without_echoing_values(self):
        for attrs in ({"password": "true"}, {"class": "android.widget.EditText"}):
            with self.assertRaisesRegex(TOOL.Stop, "SENSITIVE_OR_LOGIN_SCENE") as caught:
                self.snapshot(extra=node(text="PRIVATE-PASSWORD", **attrs))
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_settings_device_name_editable_is_ignored_without_reading_private_content(self):
        xml = hierarchy("SETTINGS", extra=node(text="PRIVATE-DEVICE-TOKEN", **{"class": "android.widget.EditText"}))
        snap = TOOL.parse_hierarchy(xml, PACKAGE, (1000, 1600))
        self.assertEqual("SETTINGS", snap.scene)
        self.assertNotIn("PRIVATE", json.dumps(snap.report(PACKAGE)))
        root = ET.fromstring(xml)
        next(element for element in root.iter("node") if element.get("class") == "android.widget.EditText").set("password", "true")
        with self.assertRaisesRegex(TOOL.Stop, "SENSITIVE_OR_LOGIN_SCENE"):
            TOOL.parse_hierarchy(ET.tostring(root, encoding="unicode"), PACKAGE, (1000, 1600))

    def test_wrong_package_lock_and_unknown_lock_state_refuse_input(self):
        self.assertEqual("abc", TOOL.guard_foreground(f"mCurrentFocus=Window{{abc u0 {PACKAGE}/Main}}", PACKAGE))
        for text in ("mCurrentFocus=null", "mCurrentFocus=Window{abc u0 com.android.systemui/Lock}",
                     f"mCurrentFocus=Window{{abc u0 {PACKAGE}.other/Main}}"):
            with self.assertRaisesRegex(TOOL.Stop, "WRONG_FOREGROUND_PACKAGE"):
                TOOL.guard_foreground(text, PACKAGE)
        TOOL.guard_policy(UNLOCKED)
        with self.assertRaisesRegex(TOOL.Stop, "SCREEN_LOCKED"):
            TOOL.guard_policy(UNLOCKED.replace("showing=false", "showing=true"))
        with self.assertRaisesRegex(TOOL.Stop, "LOCK_STATE_UNKNOWN"):
            TOOL.guard_policy("mAwake=true")

    def test_outside_zero_and_container_sized_bounds_are_rejected(self):
        for bounds in ("[1001,0][1100,60]", "[700,0][700,60]", "[0,0][1000,1600]"):
            with self.subTest(bounds=bounds), self.assertRaisesRegex(TOOL.Stop, "INVALID_BOUNDS"):
                snap = TOOL.parse_hierarchy(
                    hierarchy().replace('[700,0][800,80]', bounds), PACKAGE, (1000, 1600))
                TOOL.locate(snap, "open", "ENTRY")

    def test_foreign_overlay_covering_target_refuses_click(self):
        snap = self.snapshot(extra=node(bounds="[700,0][800,80]", clickable=True, package="foreign.overlay"))
        with self.assertRaisesRegex(TOOL.Stop, "TARGET_OCCLUDED"):
            TOOL.locate(snap, "open", "ENTRY")

    def test_override_display_size_is_preferred(self):
        self.assertEqual((1000, 1600), TOOL.parse_size("Physical size: 1080x1920\nOverride size: 1000x1600"))

    def test_transport_failure_never_echoes_stdout_stderr_or_serial(self):
        runner = Mock(return_value=subprocess.CompletedProcess([], 1, "PRIVATE-XML", "PRIVATE-TOKEN"))
        device = TOOL.Device("adb", "TEST-PRIVATE-SERIAL", PACKAGE, runner)
        with self.assertRaises(TOOL.Stop) as caught:
            device.capture()
        self.assertNotIn("PRIVATE", str(caught.exception))

    def test_native_navigation_uses_only_real_tap_and_back_with_fresh_cleaned_xml(self):
        transport = FakeAdb()
        reports = []
        TOOL.navigate(TOOL.Device("adb", "TEST-PRIVATE-SERIAL", PACKAGE, transport), reports.append, fresh_debug=True)
        inputs = [args for args in transport.calls if args[:2] == ["shell", "input"]]
        self.assertEqual(6, len(inputs))
        self.assertEqual(2, sum(args[2] == "keyevent" for args in inputs))
        self.assertEqual("PASS", reports[-1]["status"])
        self.assertFalse(reports[-1]["passwordAndRemoteVerified"])
        self.assertEqual("ENTRY", transport.scene)
        self.assertFalse(transport.files)
        paths = [args[4] for args in transport.calls if args[:4] == ["shell", "uiautomator", "dump", "--compressed"]]
        self.assertEqual(len(paths), len(set(paths)))
        self.assertTrue(all(path.startswith("/data/local/tmp/mihon-sync-native-") for path in paths))
        self.assertNotIn("PRIVATE", json.dumps(reports))

    def test_release_navigation_is_rejected_before_any_device_command(self):
        transport = Mock()
        device = TOOL.Device("adb", "TEST-PRIVATE-SERIAL", TOOL.PACKAGES[1], transport)
        with self.assertRaisesRegex(TOOL.Stop, "FRESH_DEBUG_REQUIRED"):
            TOOL.navigate(device, lambda value: None, fresh_debug=True)
        transport.assert_not_called()

    def test_debug_navigation_requires_fresh_unauthorized_profile_confirmation(self):
        transport = Mock()
        with self.assertRaisesRegex(TOOL.Stop, "FRESH_DEBUG_REQUIRED"):
            TOOL.navigate(TOOL.Device("adb", "TEST-PRIVATE-SERIAL", PACKAGE, transport), lambda value: None)
        transport.assert_not_called()

    def test_exit_zero_null_root_never_accepts_old_xml_or_sends_input(self):
        transport = FakeAdb()
        transport.files["/sdcard/window_dump.xml"] = hierarchy("ENTRY")
        transport.dump_error = True
        with self.assertRaisesRegex(TOOL.Stop, "DUMP_FAILED"):
            TOOL.Device("adb", "TEST-PRIVATE-SERIAL", PACKAGE, transport).tap("open", "ENTRY")
        self.assertFalse(any(args[:2] in (["exec-out", "cat"], ["shell", "input"]) for args in transport.calls))
        self.assertEqual(["/sdcard/window_dump.xml"], list(transport.files))
        self.assertTrue(any(args[:3] == ["shell", "rm", "-f"] for args in transport.calls))

    def test_wrong_foreground_lock_or_changed_window_never_sends_input(self):
        for condition, reason in (("foreground", "WRONG_FOREGROUND_PACKAGE"), ("locked", "SCREEN_LOCKED"),
                                  ("change_window_at", "WINDOW_CHANGED")):
            transport = FakeAdb()
            setattr(transport, condition, {"foreground": "com.android.systemui", "locked": True, "change_window_at": 3}[condition])
            with self.subTest(condition=condition), self.assertRaisesRegex(TOOL.Stop, reason):
                TOOL.Device("adb", "TEST-PRIVATE-SERIAL", PACKAGE, transport).tap("open", "ENTRY")
            self.assertFalse(any(args[:2] == ["shell", "input"] for args in transport.calls))

    def test_rotated_physical_geometry_is_not_treated_as_portrait(self):
        xml = hierarchy().replace('rotation="0"', 'rotation="1"')
        snap = TOOL.parse_hierarchy(xml, PACKAGE, (1600, 1000))
        self.assertEqual((750, 40), TOOL.locate(snap, "open", "ENTRY").bounds.point())


if __name__ == "__main__":
    unittest.main()
