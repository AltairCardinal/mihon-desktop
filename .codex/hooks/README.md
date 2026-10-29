# Windows 原生构建检查与日志

Windows 通过 hooks.json 的 commandWindows 调用 native_gate.ps1，再以 .NET CreateNoWindow 运行 native_gate.py；不经过 Bash/WSL，避免 WSL 图形辅助进程激活窗口。非 Windows 保留 enforce-build-script.sh。PowerShell 从当前目录向上寻找 .git（支持 worktree 文件），因此子目录启动也使用同一检查。

## 行为与维护

策略沿用原检查：禁止常见 Gradle createDistributable/packageMsi 直调，提示使用 scripts/build-desktop.sh；这只是工作流门禁，不是安全隔离。正常允许返回0，策略拒绝返回2；脚本缺失、启动错误和超时明确记录故障放行。Python包装器内部42/43/44分别区分策略拒绝、无效输入和内部错误，避免Python自身退出2被误当政策拒绝。

本机Python路径由native_gate.ps1的PythonPath默认值指定，迁移机器或升级Python后须核实路径。无需修改全局终端设置。修改hook定义后须重新审核并信任，检查真实日志确认运行；不能把文件写入成功当作现有会话已刷新。

## 日志

默认文件：%LOCALAPPDATA%/Codex/hooks/native-gate.jsonl；可用CODEX_NATIVE_HOOK_LOG或-LogPath覆盖。每次通常有check_started与check_allowed/check_denied/check_failed_open，按runId关联。拒绝属于info，无效输入为warn，执行故障为error。字段包含UTC时间、阶段、耗时、退出码与固定错误分类；不保存完整命令、输入或stdout/stderr。原错误输出仍回到执行通道。

同一Windows会话以路径哈希命名互斥锁保护写入和轮转，每次等待最多200ms。默认活动文件及.1/.2共3份，每份256KiB；旧事件会淘汰。日志失败只向原stderr告警，不改变策略结果。磁盘IO不具有硬截止保证；外部写者、跨会话与路径别名不在互斥协议保证内。

检查默认5秒预算（不含PowerShell启动与日志等待），超时仅清理本次持有句柄的Python子进程，最多另等500ms。父进程被强杀、未获信任、外层未启动或系统拒绝清理时，不能保证日志与清理；不能靠本日志直接捕获外部闪窗。不得按进程名称批量关闭终端。

## 验证与回退

定向回归：python -X utf8 scripts/tests/native-hook-test.py。Windows用内置PowerShell，并可通过CODEX_HOOK_POWERSHELL指定实际Codex pwsh以同时验证。测试强制日志写临时目录，不执行Gradle。

已验收候选完成双引擎日志故障、并发、轮转、阻塞输入、超时清理与秘密哨兵验证；部署时补验真实生产脚本和hooks.json接线。持续检查应同时核对定义信任状态、默认日志是否出现新记录、故障类别和进程身份；没有新日志只能说明无运行证据，不能直接归因为故障。

回退：仅移除对应处理器新增的commandWindows，保留原command，并重新核验信任和加载状态；可能恢复原Bash/WSL闪窗。不得覆盖其他处理器、已有配置或用户改动。
