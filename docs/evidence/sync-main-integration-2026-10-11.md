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

## 完整 CI 首轮失败与 focused 关闭证据

首次 Desktop CI `38069938724` 执行 3769 项，19 项失败、4 项跳过。日志与报告保留在
`.gradle-coordinator/github-desktop-head78-failed.log` 和 `github-desktop-head78-report/`。
用户批准继续修复 CI 阻塞；仍使用原两位代理，本地不重复完整测试。该批超过 8 文件/400 行，
是 canonical 目录、详情生产接线及同轮门禁的内聚修复；数据库提交边界由主代理独立审查，
发布依赖和构建分派由未实施该区域的代理独立审查，不按文件拆散可验收能力。

| 原失败范围 | 修复与最终 focused 证据 |
| --- | --- |
| 目录 9、历史 HTTP 1、详情准备 1、详情交互 1 | 复用 canonical 目录提交，恢复完整作者归档观察与事务回滚、详情准备和错误反馈；共享 JVM/Android 12 项及 Desktop 31 项组合通过。新 SQL 契约先正确失败再通过；`sync-ci-canonical-contract-red`、`sync-ci-catalog-data-refactor`、`sync-ci-catalog-optional-green`、`sync-ci-catalog-preparation-final` |
| 排序、删除准备、扩展取消、焦点各 1 | 夹具等待真实异步状态后保留原操作与结果断言，不改变正常产品行为；`sync-ci-native-four-focused` 4/4，强化焦点等待后 `sync-ci-native-focus-synchronized` 1/1 |
| action inventory 1 | 恢复固定 revision 的 55 个来源行号，保留原 locator/contextHash、决策及状态；`sync-ci-root-metadata-build-contracts` 的该用例通过。此项是来源文档守卫，不是行为证据 |
| Windows 发布配置 2 | 旧字符串顺序断言改为真实统一 Bash/PowerShell 分派，覆盖默认测试、build-only、显式 preview 和强制重新生成正式产物；`sync-ci-root-final-contracts-green` 3/3（包含最终 manifest 来源守卫），无跳过 |

最后的 Windows 分派补验暴露 PowerShell 被 Java 启动时输出使用系统 GBK；发布脚本显式以 UTF-8 输出，
保留 Python 严格解码，不忽略错误。`scripts/tests/desktop-preview-test.py` 两个对应方法独立执行通过。
以上关闭原 19 项失败，是基线加相关补验的组合证据，不能描述为最终代码本地重跑 3769 项全绿。

Android `38071888540` 的候选构建底层日志未打印；隔离诊断 run `38073842908` 复现
`:app:mergeReleaseNativeLibs` 因原 FlexibleAdapter `c8013533` 的 JitPack AAR/JAR 均 HTTP 404 而失败。
主工作流现在在候选失败时打印协调器日志，仍完整执行原单元测试；诊断分支的跳过仅用于诊断，不合入。

保留原 gav 和原 AAR 字节，在 `gradle/pinned-maven/` 携带固定 publication、上游 Apache-2.0 许可证和来源说明；
POM 根据相同上游 commit 重建，settings 仅将该模块路由到本地仓库并核对 AAR/POM SHA-256。
`sync-ci-pinned-adapter-production-green` 通过真实 production settings 验证坐标和仓库文件路径，并执行原失败的
`:app:mergeReleaseNativeLibs --offline` 通过；其他依赖复用缓存，不能称为冷缓存完整构建。
版本、应用 RecyclerView 选择和消费声明未变，来源及 hash 见[固定 publication 说明](../../gradle/pinned-maven/README.md)。

独立发布审查批准：核对上游原 commit 的声明与 LICENSE、缓存原 AAR 与镜像逐字节一致、模块路由及 hash
失败边界、真实 artifact 路径和 Windows 分派夹具。主代理核对 canonical 事务和详情真实接线，没有新增导航。
最终 `sync-ci-final-format` 的全仓 `spotlessCheck --offline` 通过，`git diff --check` 通过。
最终云端完整门禁以该修复提交对应的 PR 检查为准，尚未在本地重复全量。

## 修复提交完整门禁的后续回归

提交 `1f075c3d686e` 对应 PR 合成测试提交 `c705ab645aca`，后者父提交为远端 main 与该 head。
两者 tree 均为 `4154a287fff5b4385de1e3afd132a6c466017b0b`，实际 Git diff 为空。
候选 artifact 的 sourceRevision 记录合成提交，不把它写成 head commit；测试源码树等价。

Desktop run `38078741145` 完整执行 3769 项，10 项失败、4 项跳过；Android run `38078741144`
候选构建成功，data 552 项中 3 失败；app 767 次执行中 12 失败、7 跳过（包含既有重试的重复执行）。
保留各自 `github-*-head1f-failed.log` 与报告，不把日志中重复失败当作不同用户能力。

该轮失败的相关关闭证据（仍是组合补验，不是本地完整重跑）：

| 范围 | 原因、处理与 focused 证据 |
| --- | --- |
| Desktop 迁移适配、下载过滤目录快照、详情 4 项 | 迁移使用真实 SQL 及仅委派的调用计数；稀疏目录应 needsRefresh=true；4 个事件测试用真实 writer 建立 COMPLETE 前置，保留 HTTP、拒绝、滚轮、键盘、过滤和身份断言。`sync-ci-head1f-catalog-red` 正确失败，`sync-ci-head1f-catalog-green` 5 项通过，加 `sync-ci-head1f-migration-adapter-green` 1 项通过 |
| Windows 构建路径 | 真实 8.3 TEMP 父目录复现原失败；夹具 canonicalize 临时路径，保留实际默认、build-only、preview 分派与发布隔离断言。短路径与普通路径各 2 项通过，产品构建语义未改 |
| data 迁移、面板生命周期、新同名空间 | 旧 schema 43 断言改为 44；已成功设置的面板还观察持久进度，前置应为精确 5 个 listener；新仓库准备/确认路径复用现事件，真实 HTTP 夹具补齐按 ID 读回。`sync-ci-head1f-data-red` 双端 6 项正确失败，`sync-ci-head1f-data-green` 6/6 通过，保留 stop 后 0 listener、已受理交换完成、旧记录/绑定、新 ID、sealed password 断言 |
| Desktop 原生历史、作者、窄屏分类焦点 | 作者等待双决定后的实际 archive reload；Tab 等待真实 attached/placed、完全可见目标及稳定滚动，保留 80 步/原帧数、双向闭环、背景隔离与关闭回焦断言。`sync-ci-head1f-native-red` 作者通过，`sync-ci-head1f-native-green` 原生历史及分类通过；共享新例的前置失败不计作此项通过证据 |
| 共享历史恢复焦点 | 正确场景使用实际父 coroutineContext、打开搜索、编辑、封面/详情焦点、saveable 返回，原入口明确被 `history_search_input` 抢焦点；`sync-ci-head1f-shared-history-context-red` 正确红，恢复 mount capture 后 `sync-ci-head1f-shared-history-context-green` 3/3 通过。格式化后的 `sync-ci-head1f-final-focused-formatted` 该类 3 项再次通过，但该命令整体因其他编译问题失败，不将其整体记绿 |
| Android 目录协同、profile、作者、扩展与错误反馈 | 正确类型的偏好与完整 sync 参数、真实 SQL/下载操作委派、取消并 join 后关闭存储、真实 ScreenModel job 排空及同测试 scheduler；Release profile 仅 host helper 桥接。`sync-ci-head1f-app-six-red` 正确复现持续失败，未改的 Toast 实际通过；`sync-ci-head1f-app-five-green` 4 个修改用例通过，整体仍因 Reader 的 scalar-only MemoryPrefs 缺 StringSet 失败。Reader 改用已有独立 AndroidPreferenceStore 后，`sync-ci-head1f-reader-final` 单项通过，保留目录/元数据/phase/仅 1 次网络请求断言 |
| 最终契约与格式 | `sync-ci-head1f-reader-final` 整体 PASSED：Android Reader 1 项、Desktop 来源契约 12 项及实际 Windows 分派 2 项全通过、无跳过，全仓 `spotlessCheck` 通过。此前的无效任务名、过长测试名与测试 API 泛型编译问题不作为验证通过证据；各自修正后由该有效命令收口 |

Android 候选为本次 CI 的未签名包，实际 manifest/hash 一致，4 个 ABI 均包含 `libsqlite3x.so`。
Release profile JVM 测试用限定的 host helper 桥接执行真实数据库和生产 callback，同时核对实际 Requery
factory、context、数据库名称；这不是 Android JNI 的实机验收。没有替换产品的 Release SQLite 工厂。

Root 独立审查共享历史恢复入口与 Android 夹具；原实施者未修改 Root 的 data/Windows 区域并完成该区域只读复核。
该批超过 8 文件/400 行，内聚于同轮 CI 真实前置、异步收尾与唯一共享焦点修复，未升级依赖、改变数据协议或扩展 UI。
最终合并仍以新提交的两个完整云端门禁为准，不用本地组合证据替代主干保护检查。

## 单项历史请求夹具收尾

`1afbec9f4e` 的 Desktop 完整 run `38086418993` 执行 3769 项，只剩
`HistoryScreenModelTest.reader request uses chapter and manga data` 1 项失败、4 项条件跳过；前轮 10 项未再失败。
该测试把临时历史条目交给模型，但 repository 为空；Default scope 的初始加载一旦完成，生产的“条目仍存在”
保护会正确拒绝该请求。显式加载空 repository 的 `sync-ci-head1af-history-model-red` 复现同一失败。

仅在该用例播种同一历史行、等待真实加载并核对 items，再保留原 chapter/manga/source/viewerFlags/initialPage=3
断言，结束 dispose observer。`sync-ci-head1af-history-model-green` 1/1 通过、无跳过；
`sync-ci-head1af-history-model-format` 全仓格式通过，Root 只读审查批准。生产代码与其他夹具未改，
不以“还没有加载”放松历史存续保护，也没有再次运行本地完整模块。

## 交付边界

本次是已验收同步分支的集成与回归收口，没有重新构建或安装已验收 APK。Android 正式身份、证书连续性及较高版本号保持；后续正式候选必须走统一构建入口递增版本并包含 schema 44。主干的 Android 与 Desktop 必需 CI 检查通过后，按 PR 流程合入；远端合并状态以 PR 为准。
