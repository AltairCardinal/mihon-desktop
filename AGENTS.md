# AGENTS.md

本文件是本仓库各 Agent 的共同入口；`CLAUDE.md`、`GEMINI.md` 只引用此处，不维护另一套规则。根文件保留常驻约束，操作细节按下表加载。

## 常驻约束

- 所有面向用户的交流使用中文；确认 bug 已修复时，明确用中文说明。
- 所有改变产品行为的新增、修改、修复必须先写真实行为失败测试并确认原因，再最小实现、重构并复验同一 focused 范围。没有对应测试的功能代码不允许提交；纯文档、纯文案和不改变行为的机械配置调整除外。
- 测试执行真实 production 实现和 wiring，不以源码文本扫描、符号存在或复制实现逻辑代替。导航、DI、Screen/Tab、HTTP/API 的变化必须覆盖对应集成点。
- 优先复用既有链路。用户可见 capability 必须有入口、反馈并考虑危险操作确认；内部设施必须有 production wiring 和集成测试。
- 保护已有未提交内容，只暂存本任务改动；不自动 stash、回滚、强推或修改分支保护。

## 主干开发与推送完成条件

- 无并发写入时默认在本地 `main` 开发、验证、提交；不因历史 PR、通用习惯或远端 PR 提示自行转建分支。并发写入、共享构建争用或必须切换历史时可使用独立 worktree；同一目录换分支和目录别名均不是隔离。
- `AltairCardinal` 已获授权使用现有管理员豁免直推 `origin/main`；账号、权限与推送授权仍须核对，不重复请求已授予的许可。授权直推不等于授权发布其他任务提交。
- “验收通过，可以推送”要求本次改动进入本地和实际远端主干；临时分支、PR、自动合并等待均不是完成。只创建 PR 等明确限定或真实阻塞须如实报告。
- 提交、整合、推送或打包前读取[Git 交付细则](docs/agent-guides/governance-and-reporting.md#git-交付细则)。推送必须核对完整提交集合并固定 SHA；主干包必须从包含所需功能提交的独立快照构建。

## 上游对齐原则

- 除平台 API/技术栈确实不能复用外，功能语义、数据、状态、错误处理和持久化与 Android Mihon 对齐；平台差异限定在 adapter，说明技术原因，同时保留 Desktop 独有能力，必要时抽取共享核心和平台扩展。
- 双端一致行为使用共享契约测试；平台特有行为用独立集成测试。SQLDelight schema/migration 唯一权威是 `data/src/commonMain/sqldelight/`，不得创建 `data/src/main/sqldelight/` 镜像；生成查询位于 `tachiyomi.data.*.db`。

## 验证与交付边界

- 日常只执行覆盖真实行为、接线和受影响平台用户路径的最小充分验证；提交、阶段/roadmap 完成或会话结束均不触发完整模块/全量、`finalParityAudit` 或正式发布。
- 扩大验证须先说明 focused 无法覆盖的具体风险、最小范围及成本，并取得用户明确批准；一次批准不授权以后重复执行。完整发布矩阵、正式构建和版本分配只在用户指定的稳定发布/完整验收里程碑最终收口执行。
- 日常体验使用项目脚本的 `preview`/`main-preview`；构建成功不等于功能验收或发布就绪。重型 Gradle 经 `scripts/gradle-coordinator.py` 在同一 worktree 串行执行；超时先查状态，不重复启动或全局清理 Java 进程。
- 功能公共说明仅在用户明确验收本次具体功能/候选后更新；测试、提交、合并或 checkoff 不能替代用户验收。纯勘误等不增加能力承诺的维护及撤回已知错误说明除外。

## 按动作加载详情

执行前按当前动作读取对应指南；多个动作同时命中则均适用。新阶段或范围变化时重查，已读且未变的内容不重复加载。入口工具不能自动展开链接时，须主动读取；指南缺失时停止依赖操作，继续独立项。项目规则不依赖本机用户级文件。

| 即将执行的动作 | 必须读取 |
|---|---|
| 规划、实现或审查业务/共享逻辑、UI/导航、模块、DI、schema 或构建依赖 | [架构与 UI](docs/agent-guides/architecture-and-ui.md)；Desktop 页面/导航/弹层/列表及原型先读其链接的 UI 规范 |
| 设计行为测试、确定验证范围、执行 Gradle/产品验证或分析失败 | [验证与接线](docs/agent-guides/verification.md)，文档中的完整测试命令不是执行授权 |
| 规划 roadmap、维护 manifest/checkoff、代理交接、提交/整合/推送或完成报告 | [进度、交接与报告](docs/agent-guides/governance-and-reporting.md) |
| Desktop 体验/正式构建、版本分配或 Windows/macOS 产物交付 | [Desktop 构建](docs/agent-guides/desktop-build.md)，同时遵循验证规则 |
| Android 编译/JVM 测试、SDK/签名诊断、安装或验收 | [Android 构建与验收](docs/agent-guides/android-build.md)，同时遵循验证/原生操作规则 |
| 判断公共说明更新时机、编辑 README/website/双语 changelog、部署 Pages，或用户明确验收具体功能后 | [公共说明](docs/agent-guides/public-documentation.md) |
| 新增、修改、打开或验证双端 HTML 原型 | [HTML 原型](docs/agent-guides/html-prototypes.md)，只读目标原型 README 与适用规则 |
| Desktop E2E/Test Mode、跨会话移交、macOS 验收或占用桌面的原生操作/诊断 | [自动化与原生验收](docs/agent-guides/desktop-acceptance.md)，再读平台/工具经验入口 |
