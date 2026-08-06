# Windows CI 打包测试故障交接

## 交接目标

本文记录独立仓库迁移后 `Desktop CI` 中 Windows 分发包测试失败的背景、已确认根因、现有证据、已完成工作和后续修复步骤。接手者应以恢复 GitHub Actions 的 `Desktop JVM Tests` 为目标，同时保持 ZIP 内容、校验文件格式和本地发布脚本行为不变。

## 当前结论

仓库迁移没有损坏 Git 对象或 Windows 打包产物。故障是在 Desktop CI 从 Ubuntu 改为 `windows-2022` 后首次暴露的 Windows PowerShell 兼容性缺陷。

测试类 `WindowsDistributablePackagingTest` 使用 `@EnabledOnOs(OS.WINDOWS)`。此前工作流运行在 Ubuntu，该测试会被 JUnit 跳过；改用 Windows Runner 后，测试开始执行，并发现 `package-windows-distributable.ps1` 隐式依赖 `Microsoft.PowerShell.Utility` 模块提供的 `Get-FileHash`。

GitHub Runner 中由 Gradle 测试启动的 `powershell.exe` 无法解析该命令，脚本以退出码 `1` 结束：

```text
The term 'Get-FileHash' is not recognized as the name of a cmdlet,
function, script file, or operable program.
```

本机 Windows PowerShell 能加载该模块，因此相同 focused test 在本机通过。问题边界是“打包脚本对 PowerShell 模块自动加载的环境依赖”，不是 ZIP 结构、Git 历史或 Linux 支持问题。

## 事件时间线

1. 仓库迁移到公开独立仓库 `AltairCardinal/mihon-desktop`。
2. 按产品范围要求停止 Linux Desktop 验证，提交 `0c799c399` 将 Desktop CI Runner 改为 `windows-2022`。
3. Windows CI 首次执行 `WindowsDistributablePackagingTest`，同时还暴露了迁移后固定 Git 对象不可达问题。
4. 提交 `96f8ed39f` 增加溯源引用拉取；固定 Git 对象相关失败全部消失，2474 项测试仅剩 Windows 打包测试 1 项失败、3 项跳过。
5. 原工作流使用 `if: steps.desktop_tests.outcome == 'failure'` 上传报告。该条件受隐式 `success()` 约束，测试失败后上传步骤反而被跳过。
6. 提交 `bcc8eba06` 将条件修正为 `failure() && steps.desktop_tests.outcome == 'failure'`，失败报告成功上传，由此取得上述 `Get-FileHash` 原始异常。

## 证据索引

- 首次只剩打包失败的运行：[Desktop CI 31107339908](https://github.com/AltairCardinal/mihon-desktop/actions/runs/31107339908)
- 报告上传已修复的运行：[Desktop CI 31109537086](https://github.com/AltairCardinal/mihon-desktop/actions/runs/31109537086)
- 失败测试：`app-desktop/src/test/kotlin/mihon/desktop/release/WindowsDistributablePackagingTest.kt`
- 生产脚本：`scripts/package-windows-distributable.ps1`
- 失败位置：生产脚本调用 `Get-FileHash -LiteralPath $archive -Algorithm SHA256`
- 本机 focused test 证据：`WindowsDistributablePackagingTest` 通过，说明故障依赖 Runner 的 PowerShell 环境。

第二次运行上传的 artifact 名为：

```text
desktop-test-report-bcc8eba06cc0887b2903505e3df366d1f27d81b2
```

报告中的失败类页面为：

```text
classes/mihon.desktop.release.WindowsDistributablePackagingTest.html
```

## 已完成工作

- Desktop CI 已固定使用 `windows-2022`，不再执行 Linux Desktop 测试。
- CI 已能获取权威契约测试需要的保留 Git 引用。
- Desktop 测试失败时会可靠上传 HTML 测试报告。
- 已确认 2474 项 Desktop JVM 测试中仅 Windows 打包成功路径失败；不完整 runtime 的拒绝测试通过。
- 已确认本机 focused test 通过，且历史完整 Desktop 打包构建成功。

## 未完成工作

- `scripts/package-windows-distributable.ps1` 仍调用 `Get-FileHash`。
- 尚未提交跨 PowerShell 环境的 SHA-256 实现。
- 尚未取得修复后的 GitHub Windows CI 全绿证据。

工作区可能保留一项未提交的测试实验：它尝试用同名 PowerShell 函数遮蔽 `Get-FileHash`，但本机测试仍然通过，没有形成有效红测试。不要把该实验原样提交；继续前先检查 `git status` 和对应 diff，并用 `apply_patch` 清理或替换。

## 推荐修复设计

将校验计算改为直接使用 .NET API，不依赖 PowerShell 模块自动加载：

```powershell
$stream = [IO.File]::OpenRead($archive)
try {
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
} finally {
    $stream.Dispose()
}
```

继续沿用现有校验文件格式：

```text
<小写 SHA-256>  <ZIP 文件名>\n
```

不要改变压缩包根目录、launcher 路径、`app/`、`runtime/` 或发布产物命名。

## TDD 执行建议

这是发布脚本行为修复，必须执行红绿重构：

1. **红**：构造一个关闭 PowerShell 模块自动加载且卸载 `Microsoft.PowerShell.Utility` 的子进程，再运行真实生产脚本；确认当前实现因找不到 `Get-FileHash` 失败。先单独验证测试夹具确实让 `Get-Command Get-FileHash` 失败，避免产生假红或假绿。
2. **绿**：仅把哈希计算替换为上面的 .NET 实现，确认同一测试通过。
3. **重构**：保持资源释放和错误传播清晰，再运行 focused test。
4. 校验 `.sha256` 文件中的值等于对实际 ZIP 字节计算出的 SHA-256，而不只是检查文件存在。

PowerShell 测试进程建议同时设置：

```powershell
$PSModuleAutoLoadingPreference = 'None'
Remove-Module Microsoft.PowerShell.Utility -Force -ErrorAction SilentlyContinue
```

如果 Windows PowerShell 仍预加载该命令，应先找到可重复的隔离方式；不要使用源码字符串扫描代替行为测试，也不要为了让测试通过而给生产脚本增加仅测试使用的开关。

## 验证命令

Windows 上通过项目协调器运行 focused test：

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key windows-packaging-fix -- .\gradlew.bat :app-desktop:jvmTest --tests "mihon.desktop.release.WindowsDistributablePackagingTest"
```

批次完成后运行：

```powershell
python scripts/gradle-coordinator.py run --key desktop-ci-fix -- .\gradlew.bat spotlessCheck :app-desktop:jvmTest
```

最后推送提交并等待 `Desktop CI`。不要用本机测试替代 GitHub `windows-2022` Runner 验收。

## 验收标准

- [ ] 红测试能稳定复现 `Get-FileHash` 不可用，而不是由路径、引用或测试夹具错误导致。
- [ ] 生产脚本不再调用或依赖 `Get-FileHash`。
- [ ] 生成的 ZIP 包含 `Mihon Desktop.exe`、`app/` 和 `runtime/`。
- [ ] `.sha256` 内容是 ZIP 的真实小写 SHA-256，保留两个空格和文件名格式。
- [ ] 不完整 runtime 仍被拒绝，且不留下输出 ZIP。
- [ ] `spotlessCheck` 通过。
- [ ] 完整 `:app-desktop:jvmTest` 通过。
- [ ] GitHub Actions 的 `Desktop JVM Tests` 在 `windows-2022` 上通过。
- [ ] `Build & Test` 与 Desktop CI 均满足 `main` 分支保护要求。

## 边界与回退

- 当前项目不验证或发布 Linux Desktop；不要为了本问题恢复 Ubuntu Desktop job。
- Android `Build & Test` 是独立检查，不应因 Windows 打包问题被禁用。
- 不应删除 Windows 打包测试或用条件跳过它；它现在是发布脚本跨环境兼容性的唯一 CI 防线。
- 若 .NET 哈希实现出现回归，可单独回退该脚本修改；保留 `bcc8eba06` 的失败报告上传修复，以免后续 CI 再次丢失证据。
