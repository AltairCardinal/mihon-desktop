# UTF-8. 保留原 hook 匹配规则和 Bash 外层退出码映射。
import sys

def main():
    import json
    import re
    import sys

    try:
        data = json.load(sys.stdin)
    except (json.JSONDecodeError, UnicodeDecodeError, TypeError, ValueError):
        raise SystemExit(43 if '--wrapped' in sys.argv[1:] else 0)

    if not isinstance(data, dict) or not isinstance(data.get("tool_input", {}), dict):
        raise SystemExit(43 if '--wrapped' in sys.argv[1:] else 0)
    tool_input = data.get("tool_input", {})
    command = tool_input.get("command", "") if isinstance(tool_input, dict) else ""
    if not isinstance(command, str):
        raise SystemExit(43 if '--wrapped' in sys.argv[1:] else 0)

    gradle_invocation = re.compile(
        r"(?m)(?:^|[;&|])\s*"
        r"(?:(?:command|exec)\s+)?"
        r"(?:env(?:\s+[A-Za-z_][A-Za-z0-9_]*=[^\s;&|]+)*\s+)?"
        r"(?:(?:[^\s;&|\"\x27]*/)?gradlew(?:\.bat)?|gradle)(?=\s|$)",
    )
    distribution_task = re.compile(
        r"(?<![A-Za-z0-9_])(?:[:A-Za-z0-9_.-]+:)?(?:createDistributable|packageMsi)(?![A-Za-z0-9_])",
    )

    for invocation in gradle_invocation.finditer(command):
        tail = command[invocation.end():]
        separator = re.search(r"[;&|\n]", tail)
        segment = tail[: separator.start()] if separator else tail
        if distribution_task.search(segment):
            print(
                "禁止直接调用 Gradle Desktop 分发任务（createDistributable/packageMsi）；"
                "请使用 ./scripts/build-desktop.sh。",
                file=sys.stderr,
            )
            raise SystemExit(2)

if __name__ == '__main__':
    try:
        main()
    except SystemExit as exc:
        # 42 是包装器专用的策略拦截标记，避免与 Python 自身错误 2 混淆。
        if '--wrapped' in sys.argv[1:]:
            raise SystemExit(42 if exc.code == 2 else (43 if exc.code == 43 else 0))
        raise SystemExit(2 if exc.code == 2 else 0)
    except Exception:
        import traceback
        traceback.print_exc()
        raise SystemExit(44 if '--wrapped' in sys.argv[1:] else 0)
