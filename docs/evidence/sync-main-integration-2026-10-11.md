# 同步分支合入主干：本地集成验证

## 基线与范围

- 主干基线：`8556c05f6f`；用户已验收的同步分支：`039b820264`。
- 在独立的 `codex/sync-main-integration-20261010` worktree 集成，未修改原主干工作区的未提交文件。
- 由两位实施代理分别处理共享数据/交互和双端接线，主代理独立审查迁移、账号边界及整合结果。涉及大量文件是两条既有功能分支的语义合并，不拆散共享协议、双端 UI 和生产接线。
- 保留主干的可选密码、创建上下文及凭据版本保护、历史选择章节和阅读起点、关闭时等待数据库使用者退出；保留同步分支的仓库优先准备、进度/暂停计时、权限预检、恢复路径及正式安装身份。

## 真实问题与处理

1. 两条已发布分支复用了迁移 40：保留主干 40 和同步 41/42 原文，增加 schema 44 升级检查点，由生产原子迁移入口收敛合法对象族。Android 升级回调不再重复调用生成迁移。部分对象族和后续失败均回滚，不清空数据库。设计与边界见[兼容升级说明](../architecture/database-schema-fork-convergence.md)。
2. 断开的旧格式绑定被再次强制解码，阻断新设置与实际设备授权：切换查询保留原始解码事实；授权仅对已经物理断开的、不支持的旧绑定跳过旧身份读取。仍验证新 token 的账号，保留旧记录/队列；启用的不支持绑定、读取失败及已知账号不符继续拒绝。
3. 普通设置错误的重新检查失败反馈未消费：恢复既有反馈；仓库/安装指导页继续使用具体主行动，隐藏重复的泛化反馈。
4. 现代详情按钮遗漏只读观察接线：两处按钮接入共享观察 modifier，Desktop 白名单纳入该标签。测试改为使用真实 Registry，保留 Tab 焦点、布局、卸载、关闭回焦及 HTTP 只读断言。
5. 旧测试按原来的同时展开按钮和密码提交入口操作：按已验收的仓库优先流程、折叠替代操作及主干密码创建入口更新，保留真实 HTTP、权限范围、密码/风险、上下文和无额外写入断言。

## 验证证据

重型 Gradle 命令均由同一 worktree 的协调器串行执行。日志为 `.gradle-coordinator/<key>.log`；命令和终态为同名 JSON。以下是基线加受影响补验的组合证据，不表示最终代码重跑了整个模块或仓库。

| 范围 | 实际结果与补验 | 协调器 key |
| --- | --- | --- |
| JVM 已发布迁移、兼容、暂停与运行存储 | 44 项基线中 43 通过；旧夹具改用真实原子入口后，剩余 1 项通过 | `sync-main-shared-focused-green`、`sync-main-shared-legacy-fixture-green` |
| Android 共享恢复/存储及迁移 | 165 项基线中 162 通过；3 项失败的共享契约在 JVM/Android 共 6 项补验通过；授权最终 6 项通过，并复用前轮未受等待条件调整影响的 4 项 Android 结果，覆盖双端正反例 | `sync-main-android-native-green`、`sync-main-data-inactive-binding-green`、`sync-main-data-device-auth-green`、`sync-main-data-device-auth-focused-final` |
| Android 原生接线与生产升级回调 | 68 个用例最终通过；3 个真实 FileProvider 用例因 Windows 路径条件跳过，应由 Linux Android CI 执行。一次 JDBC 驱动发现失败由既有自动重试通过；7 个升级回调用例无跳过 | `sync-main-android-app-green` |
| 共享设置 UI | 181 项基线中 180 通过；最后一个反馈边界及普通重试反馈共 2 项补验通过 | `sync-main-ui-repair-focused`、`sync-main-ui-retry-boundary-green` |
| 进度与计时 | 13 项通过，覆盖统计等待、固定总数、暂停冻结、恢复与 ETA | `sync-main-desktop-final-focused` 中的限定 presentation 测试；该命令后续因格式失败结束，不能将整个命令记为通过 |
| Desktop 原生、阅读、恢复及元数据 | 116 项基线中 114 通过；剩余焦点接线及固定来源锚点关闭，相关 11 项补验全部通过 | `sync-main-desktop-native-green`、`sync-main-observer-green` |
| 原型浏览器 | 26 项有效通过；旧默认密码夹具按主干可选密码/风险方案更新后单项通过，保留双端响应式与现代同步交互 | 实施代理的本地浏览器验证回执 |
| 格式 | 全仓 `spotlessCheck` 通过；最后两处共享观察 modifier 的 `presentation-sync:spotlessCheck` 通过；`git diff --check` 通过 | `sync-main-native-format-valid`、`sync-main-observer-green` |

迁移和真实设备授权的新行为均有正确原因的失败测试，再完成最小实现及补验；观察接线强化红测 2 项正确失败，绿测 11 项通过。复用未受后续改动影响的通过结果，没有反复运行全量测试。

## CI 环境补验

PR #1 的首次 Android CI 在依赖审查阶段因仓库没有启用 Dependency graph 而停止。用户按推荐仅启用依赖图后，依赖差异 API 可用；漏洞提醒仍保持关闭。第二次 Android CI 的依赖审查、格式检查和 Android 构建入口 23 项测试通过（其中 2 项为平台条件跳过），随后构建协调器的 Linux 启动故障测试因 PID 文件不存在失败。

该测试与生产协调器在整合前后均未改变。未修改的单例在 Ubuntu 24.04 / Python 3.12.3 真实复现同一失败，Windows 单例通过。原因是测试在协调器取得进程身份后立即注入状态写入异常，而 Linux 子进程尚未执行到 PID 文件写入；生产清理及时结束了它。补丁仅修正测试夹具：子进程原子发布就绪 PID，注入器在 5 秒有界条件等待内核对其 PID 和身份后，再抛出原来的写入异常。保留真实进程身份消失断言，增加 `FAILED`、退出码和原异常的核验；生产协调器保持原样。

Windows 和现有 WSL Ubuntu 24.04 均运行以下 5 个 focused 用例，各 5/5 通过且无跳过：

```text
python scripts/tests/gradle-coordinator-test.py
  GradleCoordinatorTest.test_foreground_startup_failure_reaps_started_child
  GradleCoordinatorTest.test_foreground_owns_direct_child_without_background_worker
  GradleCoordinatorTest.test_foreground_preserves_fast_success_and_nonzero_exit
  GradleCoordinatorTest.test_foreground_stop_does_not_kill_unrelated_process
  GradleCoordinatorTest.test_foreground_timeout_cleans_up_managed_process
```

上面是同一命令的参数列表。未重跑本地完整协调器或应用测试，原应用通过证据不受测试夹具影响。新提交的最终云端门禁结果仍以 PR 检查记录为准。

## 交付边界

本次是已验收同步分支的集成与回归收口，没有重新构建或安装已验收 APK。Android 正式身份、证书连续性及较高版本号保持；后续正式候选必须走统一构建入口递增版本并包含 schema 44。主干的 Android 与 Desktop 必需 CI 检查通过后，按 PR 流程合入；远端合并状态以 PR 为准。
