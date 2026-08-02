# Mihon Desktop 原版对齐重构完整总结

> 汇总日期：2026-08-02
>
> 历史主权威：`docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md`
>
> 固定原版基线：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`
>
> parity 最终运行源码：`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`
>
> 历史收口快照：`e68e7dfcb11c0224aaa56868fa996664ee7bf257`
>
> 当前机器权威：[parity-manifest.json](../../app-desktop/src/test/resources/parity/parity-manifest.json)

## 1. 文档目的与结论

本文独立总结 2026-07-12 原版对齐 roadmap、原版概念纠正、各专项 child plan、最终 parity 审计、平台验收、流程治理整改，以及紧随收口发生的直接修正。历史 Superpowers、OpenSpec 和 Comet 过程文档已在提交 `873619d0b` 中清理，因此本文同时承担可长期阅读的项目回顾职责；它不是新的施工状态源，也不恢复已经废弃的多权威计划体系。

最终结论如下：

- 原计划覆盖的 64 项 capability 已全部获得终态：**63 项 `VERIFIED`，1 项 Widget（ID 85）因桌面平台没有可靠等价系统能力而为 `EXEMPT`**，不存在未分类或未映射项。
- 重构没有把当前分支的 Android 构建版误当作原版。原版证据始终固定到 `main@6fbf6df...`；当前 `app/` 与 Desktop 都是迁移结果的消费者。
- 平台无关的业务规则、状态机、数据格式、错误模型和用例被优先抽取或复用；Android 与 Desktop 只保留必要的平台 adapter 与各自产品层。
- Desktop 的作者/作品能力、Upcoming、宽屏与键鼠交互、双页与自动滚动、普通文件系统/CBZ、FlareSolverr、Test Mode 等产品能力得到保护，没有为了“像 Android”而被删除。
- 最终同一源码、同一版本在 Windows 与 macOS 完成构建和运行验收；Android、Desktop、E2E、smoke、Spotless 与最终审计门禁通过。
- 收口后又修正了两项重要边界：删除 Mihon 内置 AWT Robot 截图链以消除 macOS 录屏权限预检；将官方签名 JAR 设为扩展主路径，把 APK→JAR 降为明确的 Legacy 兼容路径。

## 2. 为什么需要这次重构

Mihon Desktop 源自 Mihon 的 fork。早期为了尽快产出桌面功能，许多已有成熟上游实现的能力被简化重写，形成了以下技术债：

- 同一业务规则在 Android 与 Desktop 各维护一份，状态、默认值和失败语义逐渐漂移；
- Composable、Manager、数据库、网络和扩展加载器之间存在越层调用；
- Desktop 使用更省事但不兼容原版的数据格式、偏好或内存状态；
- 测试有时只证明测试副本或源码字符串存在，没有经过 production wiring；
- 平台差异与业务差异混在一起，使“必须独立实现”和“历史上碰巧重写”难以区分；
- 对齐施工可能覆盖 Desktop 已有的永久产品增强。

因此计划目标不是把 Android UI 或 Android API 原样复制到 Desktop，而是把**原版成熟的功能语义和工程边界**作为契约：共享可共享的核心，用薄 adapter 处理真正的平台差异，并在其后叠加 Desktop 产品增强。

## 3. 最关键的概念纠正

执行中曾发现“Android Mihon”一词可能同时指原版 Mihon、当前 fork 的 Android 构建版，甚至 Desktop 中为扩展提供的 Android API shim。为防止错误取证，修正版主 roadmap 固定了五种角色：

| 角色 | 唯一含义 | 可以证明什么 | 不可以证明什么 |
|---|---|---|---|
| 原版 Mihon | `main@6fbf6df...` 的不可变快照 | 原版行为、默认值、状态转换与实现边界 | 当前分支后来新增或修改的行为 |
| 当前 Android 构建版 | 当前分支的 `app/` | 迁移后 shared core 的 Android consumer 与 Android adapter wiring | 不能凭自身测试反向证明“原版就是这样” |
| Desktop JVM | `app-desktop/` 的生产实现 | Desktop consumer、平台 adapter 与产品增强 | 不能作为原版来源 |
| Desktop Android compatibility shim | `app-desktop/src/main/kotlin/android/` | 真实扩展运行所需的最小 Android API 兼容面 | 既不是原版，也不是 Android 应用实现 |
| 当前共享实现 | 当前分支 common/shared 代码 | 本次迁移的生产结果 | 必须有固定原版 fixture/provenance，不能自证正确 |

这项纠正产生了四条长期规则：

1. 所有“原版如此”的结论必须能追溯到固定 commit 的路径、符号、fixture 或真实产物。
2. 当前 Android 和 Desktop 可以共用一套契约测试，但这只证明两端消费一致；原版一致性仍由 fixed-main provenance 证明。
3. compatibility shim 只能由真实扩展调用证据决定保留范围，不得成为复制 Android 业务代码的落点。
4. 原版基线若要升级，必须显式改 ref 并重新核验受影响证据，不能让移动中的 `main` 静默改变任务语义。

概念混淆的审查、原文保留与修正版计划，最终由提交 `70b0ef56c` 引入的 main-authority 体系承接。

## 4. 权威文档与状态体系的演进

### 4.1 历史文档族

主计划曾派生出以下直接资料。它们已从当前树删除，但可在历史收口快照中读取：

```powershell
git show e68e7dfcb11c0224aaa56868fa996664ee7bf257:<历史路径>
```

| 类型 | 历史路径 | 职责 |
|---|---|---|
| 原始 roadmap | `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap.md` | 初始任务结构，后来只保留为历史原文 |
| 概念审查 | `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-concept-confusions.md` | 逐项记录原版/当前 Android/Desktop shim 混淆 |
| 主权威 | `docs/superpowers/plans/2026-07-12-mihon-desktop-upstream-parity-roadmap-main-authority.md` | 修正角色定义、父 Task 状态与 child plan 指针 |
| 总体设计 | `docs/superpowers/specs/2026-07-12-mihon-desktop-upstream-parity-design.md` | 64 项分组、迁移标签、目标架构与 Desktop 保护清单 |
| Reader 计划/设计/报告 | `2026-07-15-mihon-reader-shared-core.*`、`2026-07-15-align-reader-core-verify.md` | 阅读器共享核心与 Desktop viewer 增强保护 |
| Source/Extension 计划/设计/报告 | `2026-07-15-mihon-source-extension-shared-core.*`、`2026-07-20-align-sources-extensions-android-runtime.md` | 源、扩展、登录、挑战与真实运行时闭环 |
| 平台集成计划/设计/报告 | `2026-07-21-mihon-desktop-platform-integration.*`、`2026-07-21-align-desktop-platform-verify.md` | URI、分享、凭据、隐私、更新与 OS adapter |
| Settings 计划/报告 | `2026-07-22-mihon-desktop-settings-accessibility.md`、`2026-07-23-mihon-desktop-settings-accessibility-verify.md` | 搜索、主题、i18n、许可和无障碍 |
| 最终审计计划/报告 | `2026-07-23-mihon-desktop-final-parity-audit.md`、`2026-07-23-mihon-desktop-final-parity-verify.md` | 64 项逐批裁决、产品缺口关闭与跨平台最终验收 |
| 缺口 child plans | `2026-07-24-task-*.md`、`2026-07-27-task-153-installer-trust-wiring.md` | 对真实缺口做最小化补充施工，而不是扩大产品范围 |
| 流程治理计划 | `2026-07-25-governance-cost-reduction-execution.md`、`2026-07-25-repeated-failure-prevention-execution.md` | 修复治理膨胀、Gradle 生命周期、代理回执和 GUI 副作用 |

### 4.2 最终单一权威

多份计划同时维护 active task、checkbox、manifest、tracker 曾造成状态同步成本。整改后权威被拆成互不竞争的层级：

- 父 roadmap：只保存宏观阶段及一个 `active-child-plan`；收口时为 `none`。
- 当前执行计划：施工期唯一 `active-task` 权威；完成后不再持续存在。
- child plan：从第一个未勾选项推导进度，只保存任务 checkbox 与验证证据。
- [parity-manifest.json](../../app-desktop/src/test/resources/parity/parity-manifest.json)：64 项 capability 状态、角色证据、实现路径、保护测试和豁免证据的机器权威。
- [PARITY_TRACKER.md](../desktop-parity/PARITY_TRACKER.md)：面向维护者解释权威关系，不复制第二份状态表。
- [功能实现比较](../MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md)：面向人的最终结论和差异说明。

任务编号必须结合所属计划阅读：父 roadmap 只有 Task 0、1A、1B、2A、2B、3A、3B、4A、4B、5A、5B、6；Source/Extension child plan 自己有 Tasks 1–7；最终审计 child plan 则有 Tasks 1–20 及 14A/14B/14C、16A–16D 等子批次。历史讨论中的 Task 7、Task 8、Task 151/153 等不是凭空增加的父 roadmap 阶段，而是 child plan 或平台证据批次编号。最终状态只能回收到父 Task 6 和 64 项 manifest，不能把 child 编号误写成新的父任务。

## 5. 设计原则与目标架构

### 5.1 迁移标签

| 标签 | 含义 | 最终处置 |
|---|---|---|
| `SHARE-DIRECT` | 已有共享实现可直接复用 | 修改 Desktop wiring，删除重复业务实现 |
| `SHARE-EXTRACT` | 原版逻辑可跨平台但仍在 Android 层 | 提取共享核心，让当前 Android 与 Desktop 同时消费 |
| `PLATFORM-ADAPTER` | 用户语义相同，实现依赖 OS/API | 共享接口、状态与错误，保留薄平台 adapter |
| `DESKTOP-PRODUCT` | Desktop 永久产品增强 | 保留在共享基线之后，并加回归保护 |
| `TEMP-COMPAT` | 早期移植留下的临时兼容路径 | 用真实兼容证据审计，覆盖后删除或缩至最小 |
| `PLATFORM-EXEMPT` | Desktop 没有可靠等价能力 | 明确展示 Unsupported/边界，不伪造实现 |

### 5.2 固定依赖方向

```text
UI intent
  → shared use case / reducer / state machine
  → shared repository contract
  → common data implementation 或 platform adapter
  → typed result/error
  → ScreenModel state/event
  → loading / empty / content / error / progress feedback
```

Composable 不应直接调用数据库 query、HTTP client、下载 manager 或扩展 ClassLoader。允许独立实现的范围被限制为通知与 scheduler、窗口/隐私 API、文件选择、URI 注册、OAuth 回调、凭据存储、安装与 class loading、原生图片解码及桌面输入；这些实现仍须映射到共享契约。

### 5.3 对齐完成判据

一项 capability 只有同时满足以下条件才可标为完成：

- 固定原版的行为、默认值、错误和数据格式有 provenance；
- shared core 或平台 adapter 执行真实 production 代码；
- 当前 Android 与 Desktop consumer/wiring 有集成测试；
- 用户可见能力有入口、状态和失败反馈；
- Desktop 永久能力保护测试通过；
- 旧重复业务路径已删除，或被证明为必要的薄平台/Legacy adapter；
- 升级不要求用户清空数据库、偏好、下载、备份或扩展目录；
- 实现、独立审查、验证和提交全部完成后才更新状态。

## 6. 父 roadmap 的实际执行结果

修正版父 roadmap 最终将全部阶段勾选完成：

| 阶段 | 覆盖范围 | 完成内容 |
|---|---|---|
| Task 0 | 全部 64 项 | 建立 fixed-main provenance、manifest、Desktop 产品保护网和统一门禁 |
| Task 1A | 3、4、7、12、93、95、96 | 共享状态/错误/偏好、确定性 DI、诊断模型、模块与兼容层边界 |
| Task 1B | 8、10、11、61 | 网络错误语义、可恢复后台任务、通知事件和书库更新生命周期 |
| Task 2A | 71–74 | 原版 canonical protobuf、跨端备份、双读单写和恢复链 |
| Task 2B | 53、56、57、59、62、64 | 持久下载队列、公平并发、自动下载、更新、历史和进度事务 |
| Task 3A | 16、17、19、22、24、26、66 | 分类、筛选、批处理、收藏联动、章节动作、封面和统计共享用例 |
| Task 3B | 67–70 | 单部/批量迁移、provider-neutral tracker、Desktop OAuth 与延迟同步 |
| Task 4A | 9、43–45、47、49、51、54 | 阅读器页面模型、拆页/导航/预载/滤镜/跳过规则与 Desktop viewer 增强 |
| Task 4B | 28–40、87 | 源浏览、搜索、仓库、扩展发现/安装/加载/安全、登录、挑战与 i18n |
| Task 5A | 81–86、92 | URI、分享、锁、隐私、Widget 边界、更新器和安全设置 |
| Task 5B | 88、90、91、94 | 无障碍、设置搜索、主题和许可/构建信息 |
| Task 6 | 全部 64 项 | 删除/约束重复路径，完成 provenance、架构、Test Mode 和跨平台最终审计 |

## 7. 各功能域具体做了什么

### 7.1 共享架构、状态、偏好和诊断

- 引入共享不可变 state、reducer 和一次性 event 语义，当前 Android 与 Desktop ScreenModel 消费同一核心；平台生命周期仍由各端负责。
- 偏好从 Desktop 方便但独立的存储逻辑收敛到 typed preference key、默认值、codec 与 migration；`java.util.prefs` 只充当 backend adapter。
- DI 以领域 binding 拆分，补齐初始化、解析和 teardown 测试，避免全局单例和初始化顺序决定行为。
- 统一 typed error、部分成功、取消、进度和 task state；诊断/崩溃日志共享内容模型，窗口和文件位置保留桌面实现。
- 加入架构守卫，限制 UI 直接依赖 data/network/manager；compat shim 建立真实调用清单，未被真实扩展调用的 stub 不再因“也许有用”永久保留。

### 7.2 网络、后台任务、通知和书库更新

- 复用/抽取 OkHttp 配置、Cookie、认证/挑战、错误映射、重试与取消语义；系统代理和浏览器 Cookie 导入留在 Desktop adapter。
- 后台任务拥有持久状态、幂等键、checkpoint、恢复和失败汇总；Desktop scheduler 只负责唤醒，不复制 WorkManager API。
- 通知抽成共享事件与进度模型；OS 通知不可用时有应用内反馈，而不是静默失败。
- 书库更新消费相同策略、分类过滤、进度、部分失败和恢复生命周期。

### 7.3 备份、恢复和跨端文件兼容

- Android 与 Desktop 采用同一 canonical protobuf `.tachibk` 结构，覆盖 manga、chapter、category、history、tracking、preferences、source/repository 等数据。
- Desktop 能读取 fixed-main 原版 fixture，也能读取旧 Desktop 格式；迁移阶段采用**双读单写**：只写 canonical protobuf，不再生成新的 Desktop 私有格式。
- 增加版本、损坏、字段缺失、部分恢复、取消、磁盘错误、预览、进度和结果反馈测试；用户升级无需清数据。
- 旧 Desktop writer 被移除；旧 reader 只作为历史数据迁移边界保留，不再决定新文件格式。

### 7.4 下载、更新、历史和阅读进度

- 下载从内存管理规则改为持久队列与显式状态机，支持重启恢复、取消、重试、按源公平性、并发限制和退避。
- 自动下载、Updates、Upcoming 与 History 改为读取共享规则/数据；Desktop 的 Upcoming 仍作为产品增强保留。
- Reader progress 以事务方式提交章节进度、history/read 状态和 tracker event，避免多个入口各自写一部分数据。
- Desktop manager 最终只承担文件和 OS side effect；旧的重复业务判断被删除或由架构审计约束。

### 7.5 书库和漫画详情

- 分类排序/校验、书库 flag 与组合筛选、收藏/分类联动、章节批量动作、封面缓存失效和统计聚合进入共享用例。
- Desktop 保留宽屏筛选、键鼠/Shift 多选、作者入口、作品版本比较和自己的图表布局。
- ScreenModel/production wiring 经集成测试证明调用共享用例；UI 不再直接拼装数据库或批处理规则。
- 批处理覆盖部分失败、撤销/反馈和危险操作确认，不以“按钮存在”代替能力完成。

### 7.6 迁移和追踪

- 单部迁移对齐 fixed-main 的选项、章节匹配、收藏/分类、读/书签/日期等复制语义。
- 批量迁移在原版语义上增加 checkpoint、逐项失败、取消和恢复；这些被明确标为可靠性增强，而不是伪称原版行为。
- tracker 抽成 provider-neutral core，当前 Android 与 Desktop 都接入；Desktop 额外实现 loopback OAuth、OS 凭据存储、绑定编辑/解绑、自动匹配及延迟同步。
- 最终审计曾发现并修复迁移语义债，包括 `copyCategories=false` 时错误清空分类、章节 viewer flags 未完整持久化，以及复制收藏时 `dateAdded` 语义错误。

### 7.7 阅读器共享核心

- 从 fixed-main 提取页面顺序、宽页拆分/反转/旋转、章节边界、缺章、加载/错误/重试、预加载窗口、点击区域、滤镜与跳过规则的测试向量和共享模型。
- Skia、region decoding、tile/采样和 Compose effect 留在 Desktop 图片 adapter，不复制 Android View viewer。
- Desktop 的双页、相邻竖页配对、edge matching、封面/手动 spread、Webtoon 自动滚动、键鼠/滚轮/右键菜单全部保留在共享基线之后。
- 修复了奇数像素拆页等精确性问题，并以当前 Android/Desktop consumer 测试和 Desktop viewer 产品保护覆盖。

### 7.8 源、扩展、登录和挑战处理

- 共享 source query、paging、空/错/重试、global search 并发/取消、源启用/固定/语言/排序和 repository CRUD。
- 共享扩展 index/installed model、版本比较、信任、安装事务、替换/回滚、详情/更新/卸载与 source discovery；Android PackageManager 和 Desktop class loading 分别作为 adapter。
- 登录流程统一 session、Cookie 原子交换、完成/取消状态和失败反馈；Cloudflare 检测/验证/重试语义共享，FlareSolverr 作为明确的 Desktop 后备能力保留。
- Task 4B 因真实扩展、compat ABI、失败分类和运行时证据而形成很长的 child plan，但最终完成原计划 Tasks 1–7，没有通过删减原定 13 项功能来“控制膨胀”。后续整改减少的是重复验证和过程材料，不是产品范围。
- 早期保留 APK→JAR 是为了不丢失 Desktop 已有能力；收口后的 JAR-first 演进见第 11 节。

### 7.9 系统集成、隐私和更新

- 共享 URI parser、deep-link 路由结果、share payload、锁定策略、隐私 capability、版本比较、校验/下载/安装状态和失败反馈。
- Windows/macOS 分别实现单实例/URI、剪贴板或 native share、凭据、窗口隐私、文件选择和安装交接。
- 平台能力统一报告 `Supported`、`Limited`、`Unsupported` 或 `Failed`，不再用空操作伪装成功。
- Widget 保留共享 Updates 数据能力，但 Desktop 明确没有跨 Windows/macOS 的系统 Widget provider，因而成为唯一 `EXEMPT`。
- Linux 只留防御性 fallback，不是本项目产品、发行或验收平台；签名、公证和发布安装交接属于 release operations，而非仓库内 parity 阻断。

### 7.10 设置、主题、国际化、无障碍和许可

- 建立 shared searchable settings model、索引、导航 anchor 与 highlight；Desktop 搜索结果可到达真实设置入口。
- 对齐主题 identity、默认值、codec 和 palette，平台动态色只在有可靠 API 时开放。
- Desktop 文案迁入资源系统，补齐简体中文、繁体中文和英文；不再把硬编码英文当作完成。
- 许可信息由构建生成真实依赖清单并提供详情入口；About 同时展示可验证的版本/构建信息。
- 补齐语义标签、焦点顺序、键盘操作和 exactly-once action；验证覆盖真实 Compose/Screen wiring。

## 8. 最终审计如何关闭剩余缺口

最终审计不是简单把 checkbox 全部改为完成，而是分成以下闭环：

1. 建立可独立触发的 final closure RED gate。
2. 分四批补齐 fixed-main provenance，确保 manifest 路径、符号和 fixture 真实存在。
3. 分八批裁决 64 项状态，不允许 `UNCLASSIFIED_DEBT` 或模糊“基本完成”。
4. 对剩余产品缺口建立 consolidated child plan，重点关闭 ID 3、32、69、70、87：共享源状态、扩展仓库 CRUD、tracker core/两端 consumer、自动追踪和 Desktop 语言选择。
5. 审计 compat 与历史格式，检查重复业务规则，并建立 UI→data/network/manager 架构守卫。
6. 将 Test Mode 收敛为 13 个场景族、5 项永久产品保护和 64 项 capability 映射；`unmapped=0`。
7. 只有 fixed-main、shared/adapter、当前 Android、Desktop production consumer、测试与用户反馈链都存在，才由 Task 18 提升为 `VERIFIED`。
8. Task 19 执行全量测试及 Windows/macOS 构建和真实启动；Task 20 统一收口文档与状态。

最终 `productSource` 取证覆盖 1,703 个 production input，摘要为：

```text
12f05d2c9ec7d9b7b41f790afac32100d8540ba2b6a5f4bfbd10c70912996a78
```

## 9. 施工中暴露的治理问题及修复

### 9.1 治理成本过高

提交 `6c8995454` 完成了治理降本：

- 将“8 文件/400 行”等硬上限改为内聚性和风险提示，停止“实现—压缩—格式化—再压缩—重测”循环。
- 将父 roadmap、child plan、manifest、tracker 的职责分离，消除多个 active-task 权威。
- 将计划治理测试从普通 `jvmTest` 中分层，避免每个产品微改都重复执行昂贵治理审计。
- 按可独立交付的功能批次实现、审查、提交和验证，不再机械按文件或微 Task 重启代理。
- focused test 跟随红绿循环；模块全量、发布构建和平台运行集中到阶段/最终 verify。

### 9.2 反复失败与长时间停滞

提交 `8074775e8`、`100d966e0`、`884ec53be` 修复了最明显的重复失败来源：

- `scripts/gradle-coordinator.py` 记录重型 Gradle 任务、PID、状态和退出码；外层等待超时后附着原任务，禁止再次启动一份 Gradle。
- 协调器增加 OS 文件锁、PID 身份核验、孤儿恢复和瞬时状态写入的有界重试；需要终止时只停止已记录的进程树。
- `scripts/agent-handoff.py` 约束子代理回执包含 `status`、`diff`、`tests`、`commit`、`process`、`next`，避免代理已完成却没有返回而被重复中断/恢复。
- 浏览器、Explorer/Finder 等系统副作用默认隔离；自动化测试不会再打开假网址或反复打开 `build/test-tmp`。
- 对环境抖动记录真实失败并定向复验，不把一次不相关失败演变为无限全量测试循环。

这些改动解决的是流程膨胀和不可靠等待，没有缩水父 roadmap 的功能内容。

### 9.3 平台证据阻断的裁决

Task 15 平台证据施工曾把仓库行为、真实机器能力和发布运营条件混在一起，造成反复阻断。后续按证据类型重新划界：

- Windows 窗口隐私必须保留真实外部取证：由 PowerShell 测试宿主捕获指定 Mihon 窗口，不能恢复应用内部 Robot 截图。
- macOS 通过本机或 `ssh mbp`/`ssh mbp-lan` 在真实应用 bundle 上验证启动、Test Mode、权限和平台 adapter；远程机器不可用时只能记录延期，不能把 Windows mock 当作 macOS 通过。
- Android 只在改动影响 Android production wiring、共享契约或 Android 平台行为时部署 API 36 模拟器，避免无关 Desktop 批次反复启动模拟器。
- 签名、publisher reputation、公证和商店发布属于 release operations；仓库内 verifier、安装交接和失败闭合必须验证，但缺少正式证书不再阻断 repository-local parity。
- Linux 从错误扩张的产品/验收范围中移除，只保留代码防御性 fallback；原始产品目标始终是 Android、Windows 和 macOS。

这套裁决既没有降低 Windows/macOS 真实运行要求，也没有让外部证书、TCC 状态或非目标平台无限扩张父 roadmap。

## 10. 最终验证结果

Windows 与 macOS 均从 `19a55d7c27e854a9a5b8baa27871b6d8e1c3608c` 构建版本 `0.11.14.51.19a55d7`，source tree 与 1,703 项 production-input digest 一致。

| 范围 | 最终结果 | 说明 |
|---|---:|---|
| Android `testReleaseUnitTest` | 231/231 | 0 失败、0 跳过 |
| Windows Desktop `jvmTest` | 2,293/2,296 | 0 失败、3 个明确平台/fixture 跳过 |
| `test-desktop:test` | 28/28 | 0 失败 |
| Windows Desktop smoke | 92/92 | 使用 Windows JDK 21 与 Git Bash |
| macOS build-script Desktop `jvmTest` | 2,295/2,296 | 0 失败、1 个 Windows-only 跳过 |
| Windows/macOS final Test Mode | 13/13 场景族 | 5/5 永久保护、64/64 capability、0 unmapped |
| `finalParityAudit` | PASS | manifest、provenance、架构与运行时门禁通过 |
| `spotlessCheck` | PASS | 最终源码格式通过 |

Windows 的三个跳过是两项本机当时没有 live extension JAR 的测试和一项 macOS-only native-share 测试；用户随后允许从线上关联仓库获取 live-extension fixture，解决了本机 fixture 的获取途径。macOS 的一个跳过是 Windows-only unsigned-installer verifier。它们不是隐藏的产品失败。

历史最终验证报告中的 Windows EXE 位于当次 `app-desktop/tmp/` 固定输入目录，仅用于记录当时的 provenance，**不是当前交付路径**。后续提交 `779fce95f` 与 `18550518f` 已把 Windows 未打包产物发布到 durable artifact 目录；当前构建交付地址必须以构建日志 `Final unpacked EXE:` 指向的 `app-desktop/artifacts/windows/...` 为准。

## 11. 收口后的直接修正与演进

这些提交发生在 roadmap 已完成之后，但直接修正或延续了其能力边界，因此应与本总结一起理解。

### 11.1 移除应用内截图能力

`e79477442` 删除了 Mihon 内置的 AWT Robot 截图服务、`/test/screenshot`、`--screenshot-dir`、视觉回归截图客户端和 Robot 截图入口。原因是 macOS 即使 final parity 没有调用截图端点，Test Mode 启动链仍会预检 ScreenCapture 权限。

结果：

- macOS Test Mode 不再请求 ScreenCapture、AudioCapture 或 Microphone；
- Test Mode 只暴露状态、导航和动作等非屏幕像素接口；
- Windows 隐私验收仍由 PowerShell 测试宿主在应用外部捕获目标 Mihon 窗口，未恢复任何 Mihon 内部截图能力；
- 原设计保护清单中“截图 API 不回退”的条目被这一更严格的隐私决策取代。

### 11.2 扩展改为官方签名 JAR 优先

`b9ddeaac5` 将扩展主路径从 APK 转换改为签名仓库 JAR：

- 读取 Keiyoushi v2 protobuf index；
- 校验仓库、签名和 artifact 信任链；
- 对齐 extension-lib 1.6、`SourceFactory` discovery 与必要的窄 Page URI ABI 适配；
- 对当时 1,368 个官方 JAR 做兼容性调查；
- APK→JAR 不再是默认安装方式，仅保留为 `Legacy` fallback。

Legacy 路径随后由以下提交加固：

- `9b1433854`：让 APK fallback 可安装并提供可诊断失败信息；
- `37550ca0e`：在打包运行时验证转换，并补齐 `jdk.zipfs`；
- `6400f0fa2`：转换时保留扩展 classpath resources；
- `38f3d00d1`：按转换版本迁移旧的 stale converted extension。

因此 APK 支持仍有历史兼容意义，但不再承担官方扩展生态的主路径职责。

### 11.3 网络路由和产品维护

- `b9902bd48`、`457444b82`、`576d73735`：增加 global/system/direct/manual 与逐扩展路由，明确 HTTP CONNECT/SOCKS、TLS 和 HTTP 阶段，并提供 production route 反馈。现行设计见 [desktop-network-routing.md](../architecture/desktop-network-routing.md)。
- `46c88edb5`：完成简体中文、繁体中文、英文界面本地化。
- `cfb1fba31`：改进 Desktop 设置导航。
- `781114653`：将扩展管理整合进 Browse 产品入口。
- `cf2c24c7d`：统一扩展网络路由与错误反馈。

这些属于已完成能力的维护和产品化，不代表原 roadmap 又出现未完成项。

## 12. 保留的 Desktop 产品能力与有意差异

| 能力 | 最终边界 |
|---|---|
| 作者聚合与作品版本比较 | 保留 Desktop UI，消费共享 manga/source 数据 |
| Upcoming | 保留 Desktop 产品用例，消费共享 updates 数据 |
| 键盘、鼠标、滚轮、右键菜单 | 保留在 Desktop reader input adapter |
| 双页、自动拆页、edge matching | 共享页面模型之后的 Desktop viewer 增强 |
| Webtoon 自动滚动 | 保留开关、速度与停止语义 |
| 普通文件系统与 CBZ | 保留在 Desktop storage adapter |
| FlareSolverr | 作为共享 challenge contract 的可选后备实现 |
| Test Mode | 保留真实 production 状态/导航/动作控制；不再读取屏幕像素 |
| 自由窗口与宽屏布局 | 保留 Desktop UI 响应式布局 |
| APK→JAR | 保留 Legacy fallback；官方签名 JAR 为主路径 |
| Widget | Desktop 无伪造 provider，明确 `Unsupported`；ID 85 为唯一豁免 |
| Linux | 仅防御性 fallback，不是产品、构建或验收平台 |

## 13. 64 项 capability 最终状态

下表是可读摘要；字段级证据以 [parity-manifest.json](../../app-desktop/src/test/resources/parity/parity-manifest.json) 为准。

| 组 | ID | capability | 状态 | 最终实现/边界摘要 |
|---|---:|---|---|---|
| A | 3 | 状态管理 | `VERIFIED` | 两端消费共享 immutable state/reducer/event，平台各管生命周期 |
| A | 4 | 依赖注入 | `VERIFIED` | 共享领域 binding 与确定性解析，Desktop 只保留 UI/platform wiring |
| A | 7 | 偏好存储 | `VERIFIED` | typed key/default/codec/migration 共享，平台存储为 backend |
| A | 12 | 崩溃与诊断 | `VERIFIED` | 共享诊断/错误模型，Desktop 文件和窗口为 adapter |
| A | 93 | 高级维护 | `VERIFIED` | 清理用例与结果边界共享，打开目录为 Desktop 增强 |
| A | 95 | 代码模块边界 | `VERIFIED` | 依赖方向和 UI 越层守卫生效 |
| A | 96 | 平台兼容层成本 | `VERIFIED` | shim 由真实扩展证据约束，历史重复路径已审计 |
| B | 8 | 网络栈 | `VERIFIED` | OkHttp/Cookie/错误/重试语义共享，代理与浏览器接入平台化 |
| B | 10 | 后台任务 | `VERIFIED` | 持久任务、恢复和幂等共享，Desktop scheduler 薄适配 |
| B | 11 | 通知 | `VERIFIED` | 共享通知事件/进度，OS 或应用内反馈 |
| B | 61 | 书库更新 | `VERIFIED` | 更新策略、过滤、部分失败和持久生命周期共享 |
| C | 16 | 分类 | `VERIFIED` | 分类用例、排序和校验共享 |
| C | 17 | 书库筛选 | `VERIFIED` | LibraryFlags 与组合/持久化规则共享 |
| C | 19 | 书库多选批处理 | `VERIFIED` | 批量动作和失败语义共享，Desktop 键鼠选择保留 |
| C | 22 | 收藏与分类联动 | `VERIFIED` | 收藏、分类和清理策略接入共享用例 |
| C | 24 | 章节批量操作 | `VERIFIED` | 范围、下载/书签/已读和错误结果共享 |
| C | 26 | 封面管理 | `VERIFIED` | 封面用例、缓存失效和错误共享，文件选择平台化 |
| C | 66 | 统计 | `VERIFIED` | 聚合、维度和筛选共享，Desktop 图表布局保留 |
| D | 28 | 源列表 | `VERIFIED` | 启用、语言、固定、排序和筛选规则共享 |
| D | 29 | 单源浏览 | `VERIFIED` | paging、FilterList、空/错/重试共享，loader 平台化 |
| D | 30 | 全局搜索 | `VERIFIED` | 并发搜索、取消和结果状态共享 |
| D | 32 | 扩展仓库 | `VERIFIED` | repository CRUD、验证和错误模型共享 |
| D | 33 | 扩展发现 | `VERIFIED` | index、installed model、版本与状态机共享 |
| D | 34 | 扩展安装 | `VERIFIED` | 信任、事务、替换/回滚共享；JAR/Legacy APK 为 Desktop adapter |
| D | 35 | 扩展加载 | `VERIFIED` | source discovery/失败隔离共享，class loading/ABI 适配平台化 |
| D | 36 | 扩展安全 | `VERIFIED` | 签名、哈希、仓库信任和确认链完成 |
| D | 37 | 扩展详情与更新 | `VERIFIED` | 详情、版本、更新事务和卸载语义共享 |
| D | 38 | 源偏好设置 | `VERIFIED` | preference schema/语义共享，Desktop 控件渲染适配 |
| D | 39 | WebView/源登录 | `VERIFIED` | session、Cookie 交换、完成/取消和反馈闭环 |
| D | 40 | Cloudflare 绕过 | `VERIFIED` | challenge 检测/验证/重试共享，FlareSolverr 后备保留 |
| D | 87 | 国际化 | `VERIFIED` | Desktop UI 接入资源系统并具备语言选择 |
| E | 9 | 图片加载与解码 | `VERIFIED` | 请求/缓存/取消/错误/内存语义共享，Skia 解码适配 |
| E | 43 | 宽页拆分 | `VERIFIED` | 拆分/反转/旋转/虚拟页共享，edge matching 保留 |
| E | 44 | 大图与缩放 | `VERIFIED` | region/tile/采样契约对齐，Desktop 使用 Skia |
| E | 45 | 图片预加载 | `VERIFIED` | 预载窗口、优先级、取消和驱逐共享 |
| E | 47 | 页面过渡 | `VERIFIED` | 章节边界、缺章、加载/错误/重试状态共享 |
| E | 49 | 点击区域方案 | `VERIFIED` | 导航区域和反转规则共享，键鼠命令叠加 |
| E | 51 | 色彩处理 | `VERIFIED` | 滤镜/灰度/反色语义共享，Skia/Compose effect 适配 |
| E | 54 | 跳过规则 | `VERIFIED` | 已读、过滤和重复章节规则共享 |
| F | 53 | 章节进度 | `VERIFIED` | progress/history/read/tracker 事务统一 |
| F | 56 | 下载队列 | `VERIFIED` | 持久状态机、恢复、取消和重试共享 |
| F | 57 | 下载并发 | `VERIFIED` | 按源公平性、并发限制和退避共享 |
| F | 59 | 自动下载 | `VERIFIED` | 规则、分类和数量限制共享，调度平台化 |
| F | 62 | 更新列表 | `VERIFIED` | 更新状态、多选和下载联动共享，Upcoming 保留 |
| F | 64 | 历史记录 | `VERIFIED` | 搜索、删除、继续阅读和清空用例共享 |
| G | 67 | 单部漫画迁移 | `VERIFIED` | 选项、匹配、状态复制和结果语义对齐 fixed-main |
| G | 68 | 批量迁移 | `VERIFIED` | 共享编排并保留 checkpoint/逐项失败增强 |
| G | 69 | 追踪服务 | `VERIFIED` | provider-neutral core + Android/Desktop adapter/OAuth |
| G | 70 | 自动追踪更新 | `VERIFIED` | ReaderProgress 触发共享同步与平台重试/反馈 |
| H | 71 | 手动备份 | `VERIFIED` | canonical protobuf、选项和 creator pipeline 对齐 |
| H | 72 | 备份恢复 | `VERIFIED` | validator、预览、逐项恢复、错误与结果共享 |
| H | 73 | 自动备份 | `VERIFIED` | 任务与保留/幂等共享，scheduler 平台化 |
| H | 74 | 跨端备份兼容 | `VERIFIED` | 原版 protobuf 单写，原版/旧 Desktop 双读迁移 |
| I | 81 | Deep link | `VERIFIED` | URI parser 与路由共享，OS 注册/单实例平台化 |
| I | 82 | 分享/系统 Intent | `VERIFIED` | payload/action 共享，Desktop native/clipboard/browser 适配 |
| I | 83 | 安全锁 | `VERIFIED` | 锁定策略共享，凭据使用 OS 安全存储 |
| I | 84 | 屏幕与截图安全 | `VERIFIED` | 共享隐私状态和平台窗口能力；应用内截图链后来删除 |
| I | 85 | Widget | `EXEMPT` | 共享 Updates 保留；Desktop 明确无系统 Widget provider |
| I | 86 | 应用更新 | `VERIFIED` | 版本/校验/下载/交接状态共享，包类型与 verifier 平台化 |
| I | 92 | 安全设置 | `VERIFIED` | 共享安全/隐私设置，仅展示当前 OS 可靠能力 |
| J | 88 | 无障碍 | `VERIFIED` | 语义、焦点、键盘和可访问反馈经 production wiring 验证 |
| J | 90 | 设置搜索 | `VERIFIED` | searchable model/index 共享，Desktop 可导航到目标 |
| J | 91 | 外观 | `VERIFIED` | 主题 identity/default/codec/palette 对齐，平台能力有界 |
| J | 94 | 开源许可与构建信息 | `VERIFIED` | 构建生成真实许可数据，About 展示版本与详情 |

状态总数：`63 VERIFIED + 1 EXEMPT = 64`。

## 14. 关键提交时间线

| 提交 | 日期 | 作用 |
|---|---|---|
| `470cb44db` | 2026-07-12 | 初始 parity roadmap 与扩展兼容工作进入仓库 |
| `70b0ef56c` | 2026-07-18 | 分离固定原版权威，纠正 Android/原版概念混淆 |
| `6c8995454` | 2026-07-25 | 降低 parity 治理成本 |
| `8074775e8` | 2026-07-25 | 防止反复流程失败 |
| `100d966e0` | 2026-07-25 | 加固 Gradle coordinator 恢复 |
| `884ec53be` | 2026-07-25 | 为协调器状态写入增加有界重试 |
| `19a55d7c2` | 2026-07-28 | 分配最终 parity 版本 `0.11.14.51.19a55d7` |
| `e68e7dfcb` | 2026-07-28 | 完成 macOS 最终验证并关闭主 roadmap |
| `e79477442` | 2026-07-28 | 删除应用内截图链和 macOS 录屏权限需求 |
| `b9ddeaac5` | 2026-07-29 | 官方签名仓库 JAR 成为扩展主路径 |
| `873619d0b` | 2026-07-29 | 删除过时 Superpowers/OpenSpec/Comet 过程资产 |
| `779fce95f`、`18550518f` | 2026-07-30 | 建立 durable Windows 未打包产物与最终 EXE 交付路径 |
| `b9902bd48`、`457444b82`、`576d73735` | 2026-07-30 | 建立可审计的 Desktop 网络/代理路由 |
| `46c88edb5`、`cfb1fba31` | 2026-07-30 | 完成本地化并改进设置导航 |
| `781114653`、`cf2c24c7d` | 2026-07-31 | 扩展入口整合 Browse，统一网络与错误 |
| `9b1433854`、`37550ca0e`、`6400f0fa2`、`38f3d00d1` | 2026-08-01 | 加固 APK Legacy 安装、打包运行、资源和迁移 |

## 15. 当前维护入口

历史过程计划不再维护。后续开发应从这些当前资料出发：

- [功能实现比较](../MIHON_ANDROID_DESKTOP_FEATURE_IMPLEMENTATION_COMPARISON.md)：人类可读的 Android/Desktop 能力差异结论。
- [parity manifest](../../app-desktop/src/test/resources/parity/parity-manifest.json)：64 项状态与证据机器权威。
- [parity tracker](../desktop-parity/PARITY_TRACKER.md)：权威关系与维护说明。
- [automation tracker](../automation/TASK_TRACKER.md)：当前自动化覆盖和场景入口。
- [source/extension authority baseline](source-extension-authority-baseline.md)：源与扩展取证边界。
- [extension diagnostics baseline](extension-diagnostics-baseline.md)：扩展失败分类和诊断边界。
- [smoke/regression baseline](smoke-and-regression-baseline.md)：烟测与回归范围。
- [verification commands](verification-commands.md)：现行验证命令。
- [ADR 0001](../architecture/adr/0001-product-baseline-and-platform-boundaries.md)：产品基线和平台边界。
- [ADR 0002](../architecture/adr/0002-platform-interface-boundaries.md)：平台接口边界。
- [migration authority](../architecture/migration-authority.md)：迁移语义权威。
- [reader authority](../architecture/reader-authority.md) 与 [reader shared core](../architecture/reader-shared-core.md)：阅读器 fixed-main 与共享边界。
- [desktop platform integration](../architecture/desktop-platform-integration.md)：系统能力 adapter。
- [desktop extension artifacts](../architecture/desktop-extension-artifacts.md)：JAR-first 与 APK Legacy artifact 边界。
- [desktop network routing](../architecture/desktop-network-routing.md)：代理和路由链。
- [versioning and release matrix](../architecture/versioning-and-release-matrix.md)：构建版本与发布矩阵。

## 16. 不属于本次已完成 roadmap 的后续工作

以下内容不得反向解释为原 parity roadmap 未完成，也不得被写回旧 manifest 作为新的 active task：

- [2026-08-02 reader core migration and presentation roadmap](2026-08-02-reader-core-migration-and-presentation-roadmap.md) 是收口后新建的独立 Reader 演进计划。
- 2026-08-02 之后针对既有 Global Search 的体验修改、Recent History 等新增工作属于后续产品迭代。
- 图标、美术、签名、公证、商店发布等不在 repository-local parity closure 内。
- 若未来升级 fixed-main 基线，应建立新的显式差异审计，而不是改写本次已经完成的历史结论。

## 17. 总结

这次工程的实质不是“让 Desktop 看起来更像 Android”，而是偿还 fork 初期为了快速交付而产生的架构债：把成熟原版的规则、状态、数据和失败语义固定为可追溯契约，把共享部分真正迁入两端共同消费的 production core，把 unavoidable platform work 限制在 adapter，并把 Desktop 已经形成的产品能力作为受保护的增强层。

最终状态已经由 64 项 manifest、fixed-main provenance、当前 Android/Desktop wiring、自动化门禁和 Windows/macOS 同源运行验收共同证明。后续维护应在这套边界上增量演进，不再恢复 Desktop 第二套业务规则，也不应为了形式上的“完全相同”删除真实有价值的平台能力或 Desktop 产品特性。
