# 仓库治理与上游同步

Mihon Desktop 是独立维护的下游产品，保留 Mihon 的 Git 历史并遵守其许可证，但不在官方仓库中长期维护产品分支。

## 远程约定

| remote | 用途 | 写入规则 |
| --- | --- | --- |
| `origin` | `AltairCardinal/mihon-desktop`，本项目的开发、CI 与发布仓库 | 日常分支和 `main` 仅推送到这里 |
| `upstream` | `mihonapp/mihon`，官方 Mihon | 只读；本地配置必须拒绝 push |
| `legacy` | 迁移前个人仓库 | 只读追溯；本地配置必须拒绝 push |

迁移基准由 tag `migration/pre-independent-repository` 固定。它用于回溯迁移前状态，不是日常发布 tag。

## 分支策略

`main` 是下游稳定主线，跟踪 `origin/main`。用户可见功能和发布变更在短期 `feature/*` 或 `codex/*` 分支完成，通过 PR 合入 `main`。不要把长期下游产品工作继续放在官方仓库的功能分支中。

## 上游同步

1. 获取官方更新：`git fetch upstream`。
2. 从当前下游主线创建同步分支：`git switch -c sync/mihon-YYYY-MM-DD main`。
3. 将官方变更合并到同步分支：`git merge upstream/main`。
4. 按模块处理冲突，并将每项差异分类为直接采用、Desktop adapter、下游产品增强或待偿还技术债。
5. 运行受影响的共享契约、Android、Desktop 与格式检查后，通过 PR 合入 `main`。

禁止用 rebase 或 force push 重写已经发布的 `main` 历史。上游同步失败时，放弃同步分支即可，`main` 不受影响。

## 本地测试优先

自 2026-10-14 起，产品验证以本地测试为日常入口。`Build & Test`（`.github/workflows/build.yml`）与 `Desktop CI`（`.github/workflows/desktop-ci.yml`）仅保留 `workflow_dispatch`，不再由 `push` 或 `pull_request` 自动执行旧的完整产品测试流程。

测试源码、本地测试命令及原失败证据继续保留。维护者按 [AGENTS.md](../../AGENTS.md) 选择覆盖本次行为与 production 接线的 focused 范围，完成必要的格式、单元、集成和实际运行验证；GitHub 没有自动运行不代表产品已通过验收。重型 Gradle 继续通过仓库协调器串行执行，完整模块或发布矩阵仍按原有明确批准与里程碑要求执行。

确需对照 GitHub Runner 时，可在 Actions 中手动运行这两个工作流。手动入口仍使用各自原有构建与测试步骤，不隐式替代本地定向验证，也不把历史失败改成通过。后续恢复自动触发须有用户明确要求。

## CI 溯源引用

独立仓库保留两个只读 Git 引用，使 Desktop 的权威契约测试能够解析固定的上游历史对象：

| 引用 | 用途 | 维护规则 |
| --- | --- | --- |
| `upstream/main` | 上游主线的可追溯镜像 | 仅在同步上游时快进更新，不合并到下游 `main`。 |
| `provenance/fixed-main` | 迁移前固定主线对象的锚点 | 迁移证据，视为不可变；不得将其合并或重写。 |

手动运行的 Desktop CI 在测试前显式拉取这两个引用。它们只保存 Git 对象可达性，不会触发发布、改变默认分支或替代 `upstream` 远程。

## 上游回馈

可独立于 Desktop 产品的通用修复，应从干净分支整理为最小提交并向 `mihonapp/mihon` 提交 PR。不得将 Desktop 构建、品牌、图标、发布配置或平台 adapter 一并推送到官方仓库。

## 发布边界

`Build & Test` 与 `Desktop CI` 是按需手动检查，触发边界见上文“本地测试优先”。当前 Android `Release` 工作流仅允许官方仓库运行，不能视为 Mihon Desktop 的发布机制。新的签名密钥、Windows/macOS 产物上传目标和发布审批策略确定前，不得启用下游自动发布。
