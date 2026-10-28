# Desktop 体验包与正式交付

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

准备体验/正式构建、版本分配或产物交付前使用；测试范围另遵循[验证规则](verification.md)，运行与原生验收另读[Desktop 验收规则](desktop-acceptance.md)。

## 桌面端构建与部署

只有用户明确指定稳定发布/完整验收里程碑并进入最终收口后，
必须使用项目构建脚本完成桌面端正式构建与运行验收，
**不得直接调用 Gradle 构建部署**。

roadmap 实施任务全部完成、单个 Task/批次/阶段完成，以及会话结束、交接或提交，
均不自动触发完整测试、正式构建、版本递增或产物发布。

普通构建入口包含的完整测试计入最终全量验证预算。
执行前须核对脚本实际执行范围，不能以“构建验收”为由提前运行完整测试。

```bash
./scripts/build-desktop.sh           # BUILD +1，构建并验收未打包应用
./scripts/build-desktop.sh feature   # FEATURE +1，BUILD 重置为 1
./scripts/build-desktop.sh stage     # STAGE +1，FEATURE 重置为 0，BUILD 重置为 1
./scripts/build-desktop.sh msi       # 显式生成 MSI，最后重新生成并验收未打包应用
./scripts/build-desktop.sh build-only # 完整测试基线及必要的局部补验证据有效时，仅构建与运行验收
./scripts/build-desktop.sh preview    # 日常体验包：不跑测试、不分配正式版本、使用隔离产物目录
./scripts/build-desktop.sh main-preview --expected-head <完整SHA> --require-commit <功能完整SHA>
```

日常体验使用 `preview`：复用 production 打包链，但不运行产品测试、不修改 AppVersion，也不覆盖正式或既有体验产物。Windows 和 macOS 分别写入 `app-desktop/artifacts/preview/<platform>/<id>/`，附带 `preview-manifest.json` 记录源码指纹、实际产物和分层状态。Windows 的 production runtime PASS 仅代表既有扩展运行时检查；Windows 原生交互仍为 `NOT_RUN`。macOS 仅确认应用包构建，production runtime 与原生交互均为 `NOT_RUN`，需单独用 LaunchServices 和外部原生工具验收。体验包生成不等于发布矩阵或发布就绪。

## 主干候选包的源码边界

声称“主干包包含本次功能”时使用 `main-preview`，提供已经核对的本地 main 完整 SHA 和至少一个本次必须包含的功能提交；多个功能重复 `--require-commit`。工具在构建前拒绝已移动的 main、模糊 SHA 或缺少的功能提交，再创建本次独占的 detached worktree，调用同一个 production `preview` 链。原工作区里的未提交改动、stash、其他会话的编辑或分支推进不会成为它的构建输入；不会操作原工作区的暂存区、stash 或已有 worktree。

输出仍在原仓库 `app-desktop/artifacts/preview/<platform>/main-<id>/`；先复制到本次自有隐藏 staging，核对完整应用内容摘要并写好清单后，原子重命名为候选目录。失败清理自有 staging 和临时 worktree，完成清理后才报告最终路径，原工作区、他人 worktree 与正式产物不动。清单记录预期 main、必需提交、实际源码、完整应用内容摘要、分层验证状态及 `remoteMain: NOT_VERIFIED`；本地候选不冒充远端交付。SDK 等原有 `local.properties` 若存在，仅复制到自有 worktree 并记录文件摘要，不输出其内容；工具链、外部依赖与机器环境不因此变成可复现构建保证。

普通 `preview` 继续支持未提交/未跟踪输入，清单明确 `dirty`，只能称本地开发预览。两种预览均不分配版本、不自动运行产品测试或原生验收，源码提交和祖先关系也不能代替对应功能的真实回归测试。内容摘要复用现有 provenance 工具，覆盖相对路径、文件内容及链接目标，不覆盖权限等元数据；复制沿用 `copytree` 的元数据保留语义，但 macOS 权限、签名和可运行性仍须本机原生验收。不能只用 EXE 启动器 hash 推断 JAR 或运行时内容。

定向回归：`python scripts/tests/desktop-preview-test.py`，通过真实 Bash/PowerShell 构建入口与轻量 Gradle/runtime fixture 验证接线；Windows 不把跳过的 macOS 原生项报告为通过。

## 正式构建与证据复用

`build-only` 仅用于用户明确指定的最终发布收口，避免重复执行已有有效证据的完整 Desktop JVM 测试。
有效证据可以是当前代码的完整测试通过结果，也可以是已经完整执行的测试基线，
加上后续局部修复的相关回归、集成测试及影响范围说明；基线中的相关失败须全部关闭，
未执行范围不得视为通过。组合证据按[“修复收口与扩大验证门槛”](verification.md#修复收口与扩大验证门槛)记录，不冒充最终代码全量通过。
涉及依赖、构建配置、公共运行机制等变化时，须重新判断基线适用性；
不能证明适用时，先确定缺失的最小验证范围，不自动重跑或申请全量。

该模式不得省略任务／批次要求的相关验证，不免除计划要求的首次完整测试，
也不得绕过另有明确要求最终代码重新全量通过的发布门禁。
该模式仍须完成版本分配、正式产物构建、production runtime 验收与最终产物发布。

正式 Windows 构建在运行验收后，会把完整未打包应用发布到
`app-desktop/artifacts/windows/Mihon-Desktop-<完整版本>-unpacked/`；`preview` 会发布到独立的
`app-desktop/artifacts/preview/windows/<id>/`。本轮构建日志中的 `Final unpacked EXE:` 是对应报告应链接的实际未打包 EXE；`app-desktop/tmp/` 仅为内部构建和 Test Mode 输入。
