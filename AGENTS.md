# AGENTS.md

本文件说明 Codex 在本仓库工作时必须遵守的规则。

## 语言

所有面向用户的交流使用中文。确认 bug 已修复时，必须用中文明确说明。

## TDD 强制要求

**所有改变产品行为的功能变化（新增、修改、修复）必须严格执行红绿重构流程。**

1. **红**：先写失败测试，并确认它因正确原因失败。
2. **绿**：写最小实现让当前 focused 测试范围全部通过。
3. **重构**：清理代码，再次确认同一 focused 测试范围全部通过。

**没有对应测试的功能代码不允许提交。**

纯文档、纯文案或不改变产品行为的机械配置调整可以直接执行。

---

## 按动作加载详情

以下是强制动作路由。准备执行对应动作前，先读适用指南的相关章节；按即将执行的动作判断，不因任务提到某个名词而整套预载，也不依赖子目录 `AGENTS.md` 自动命中。每次换工具、进入新阶段或改变动作范围时重查本表；已读且未变化的章节可复用。多个动作同时适用时读取各自规则，不运行未授权动作。指南缺失或引用失效时停止依赖该规则的动作，记录缺口并继续独立可完成项。项目规则不依赖本机用户级文件。

| 即将执行的动作 | 必须读取 |
|---|---|
| 规划、实现或审查 capability、业务/数据/共享逻辑、UI/导航；核对模块、DI、schema 或构建依赖 | [架构与 UI](docs/agent-guides/architecture-and-ui.md)；Desktop 页面/导航/弹层/列表及对应原型先读其链接的现有 UI 规范 |
| 设计/修改行为测试、确定验证范围、执行 Gradle/产品验证或分析测试失败 | [验证与接线](docs/agent-guides/verification.md)；完整模块/全量不因文档列出命令而获准 |
| 编写/推进 roadmap、维护 manifest/checkoff、启动/等待代理、准备提交或 change 完成报告 | [进度、交接与报告](docs/agent-guides/governance-and-reporting.md) |
| 准备 Desktop 体验/正式构建、版本分配或 Windows/macOS 产物交付 | [Desktop 构建](docs/agent-guides/desktop-build.md)，并按本表加载验证/验收规则 |
| 准备 Android 编译/JVM 测试、SDK/签名诊断、候选校验、安装或验收 | [Android 构建与验收](docs/agent-guides/android-build.md)，并按本表加载验证/原生操作规则 |
| 确认公共说明更新时机、修改 README/website/双语 changelog、部署 Pages，或用户明确验收具体功能后 | [公共说明](docs/agent-guides/public-documentation.md)，沿用其链接的既有维护流程 |
| 新增、修改、打开或验证双端 HTML 原型 | [HTML 原型](docs/agent-guides/html-prototypes.md)；只读目标原型的现有 README 与适用规则 |
| 准备 Desktop E2E/Test Mode、跨会话流程移交、macOS 验收，或会占用桌面的原生/Computer Use 操作与故障诊断 | [自动化与原生验收](docs/agent-guides/desktop-acceptance.md)，再读对应平台/工具的既有经验入口 |

## 上游对齐原则

Mihon Desktop 源自 Android Mihon。除平台 API 或技术栈差异确实无法复用外，功能语义、数据模型、状态转换、错误处理和持久化行为应与 Android Mihon 保持一致，不得仅因实现更省事而保留 Desktop 独立重写。

确需平台独立实现时，必须说明不可复用的技术原因，并将差异限制在平台 adapter 内。上游对齐不得删除、降级或改变 Desktop 独有功能；共享逻辑与独有能力冲突时，应抽取共享核心并通过平台扩展保留独有行为。

## 有效验证原则

测试必须执行真实 production 实现及其 wiring。不得使用仅扫描源码文本、检查符号字符串存在或在测试中复制实现逻辑的方式代替行为验证。

Android 与 Desktop 预期一致的行为必须使用共享契约测试覆盖；平台特有行为应使用独立集成测试覆盖。如果 production wiring 损坏后测试仍能通过，则该测试不能作为完成证据。

**规划任何用户可见 capability 时都必须同时考虑用户界面。**

每项用户可见 capability 必须有入口和反馈，并评估危险操作确认；内部基础设施不要求独立 UI，但必须被真实产品链路使用并有集成测试。

**用户可见 capability 没有入口或反馈 = 功能未完成；内部基础设施没有 production wiring 或集成测试 = 功能未完成。**

**能复用却另起一套实现，默认不允许。**

新增功能先核对已有 Use Case、Manager、Repository、Service、界面、导航、数据与测试工具；优先接入既有链路，独立实现须说明不能复用的技术和体验原因。

- **数据库**：SQLDelight + 协程。schema 与 migration 的唯一权威位于 `data/src/commonMain/sqldelight/`，生成查询在 `tachiyomi.data.*.db`；不得重建 `data/src/main/sqldelight/` 镜像。

仅有 domain 单元测试不够。任何涉及导航、DI wiring、Screen / Tab、HTTP / API 的变更，必须加入对应集成级测试。影响集成点却只有 domain 测试的改动不得合并。

- 默认按日常体验交付：对本次变更选择最小充分验证，覆盖真实 production 行为、接线和受影响平台的一条用户路径。roadmap、阶段、提交、交接或会话结束本身都不自动触发完整模块测试、全量测试、`finalParityAudit` 或正式发布构建。

- 只有用户明确指定稳定发布/完整验收里程碑后，才按该里程碑计划执行一次完整发布矩阵、Test Mode、正式构建和适用平台运行验收。日常流程仍须当次完成行为 TDD、真实集成接线，以及数据、安全、协议、发布身份等高风险专项验证；显式发布里程碑不豁免这些门槛。

完整模块测试是未以测试类/用例限定的模块测试；命令经脚本或协调器包装不改变范围。日常扩大验证、里程碑中的完整/全量重跑，均须先证明 focused 不足，说明具体变化、剩余风险、最小范围及成本，取得用户明确批准；模块范围足够时不得扩大至仓库全量。一次批准不授权后续重复执行。

只有用户明确指定稳定发布/完整验收里程碑并进入最终收口，才执行桌面正式构建、版本分配与发布；必须使用项目构建脚本，不直接调用 Gradle 构建部署。日常体验用 `preview`，不隐含完整测试、正式版本或原生交互验收通过。

重型 Gradle 同一 worktree 由一个协调者经 `scripts/gradle-coordinator.py` 串行执行；外层超时先查状态，不重复启动，不全局清理 Java/Gradle 进程。

功能公共说明只能在用户明确验收本次具体功能/候选后更新；测试、构建、审查、提交、合并、roadmap 勾选不等于用户验收。纯翻译、勘误、链接或视觉修正不增加能力承诺时可直接维护，已知错误的可用性说明及时撤回。
