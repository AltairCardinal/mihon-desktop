---
change: align-settings-accessibility
parent-task: 5B
base-ref: 34276dcf498b2aa0d956cb32464468846fd82d37
original-ref: 6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status-source: this-file
---

# Mihon Desktop 设置、外观、无障碍与许可实施计划

> 本计划执行父 roadmap **Task 5B**。以下 Task 1–20（含字母后缀）全部是父 Task 5B 的内部子 Task，不是父 roadmap 在 Task 6 之后新增的同级任务。施工与汇报必须写成“父 Task 5B / 子 Task N”。

固定原版 Mihon 的唯一权威是 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`。当前 `app/` 只是 fork 后的 Android consumer；`app-desktop/src/main/kotlin/android/` 只是 Desktop compatibility shim，二者都不得冒充原版依据。本计划不恢复或依赖已卸载的 Comet/OpenSpec 状态；旧 change 目录与 `.superpowers/sdd/progress.md` 只作历史材料，不是本 change 的状态源。执行状态只写本文件，阶段摘要同步到修正版父 roadmap。

## 目标与边界

- 共享固定原版的设置搜索过滤/顺序/上限/breadcrumb/标题锚点、主题 identity/default/兼容回退与静态 ColorScheme、许可证选择规则；当前 Android 与 Desktop 都消费共享 production 实现。
- Desktop 继续使用 Voyager、Compose Desktop、Gradle/JVM resource 与 OS URI/focus adapter；不复制 Android View、AppCompat、Wallpaper、Activity recreate 或 `R.raw.aboutlibraries`。
- 保留 Desktop 独有的 grid columns、更新器、诊断/路径、扩展仓库、备份文件选择、OS credential/window privacy、挑战恢复、reader/local source/tracking 等设置能力。
- 固定原版没有独立 Accessibility 设置页，也没有全局 TalkBack 完成证明；只对齐组件级 role、最小交互面积、焦点、Enter/NumPadEnter、content description。Space 激活仅作为符合 Desktop role 的平台增强，不冒充原版契约。
- 原版搜索没有 keywords、同义词或 ranking；只对本地化 title/summary 做大小写不敏感 `contains`，排除 disabled/blank/info/disabled-group，保持登记顺序并最多返回 10 条。
- production catalog 的前九个 screen 必须严格为 Appearance、Library、Reader、Download、Tracking、Browse、Data、Security、Advanced；Desktop-only screen 只能确定性追加其后，不能前插或挤掉前九页的 top-10 命中。
- anchor 保持原版的 title 精确匹配、滚动、高亮、一次性消费与重复标题首个命中边界；额外 focus 只标记为 Desktop accessibility enhancement。
- 所有 catalog title/summary、页面标题、anchor、content description 与状态反馈消费同一 MR resource identity；至少 base 与 `zh-rCN` 完整，不能另建只供搜索的文案副本。

## 固定原版取证摘要

| ID | 固定原版路径/符号 | 必须保护的语义 |
|---:|---|---|
| 88 | `presentation-core/.../util/Modifier.kt#runOnEnterKeyPressed`、AppBar/checkbox/surface/navigation semantics | Enter/NumPadEnter 仅 KeyDown 一次；role、最小交互面积、焦点与本地化描述。无独立无障碍页面。 |
| 90 | `SearchableSettings.kt`、`SettingsSearchScreen.kt#getIndex`、`PreferenceScreen.findHighlightedIndex` | 九页顺序；title/summary contains；过滤；最多 10；RTL breadcrumb；replace route + 一次性 title anchor/highlight。 |
| 91 | `UiPreferences.kt`、`AppTheme.kt`、`ThemeMode.kt`、`SettingsAppearanceScreen.kt`、静态 color schemes | SYSTEM default；动态色能力决定 MONET/DEFAULT；未知 enum 回退；废弃主题可读但 picker 隐藏；dark+AMOLED；Monet/语言留 Android adapter。 |
| 94 | `app/build.gradle.kts`、`AboutScreen.kt`、两个 OpenSourceLicense Screen | 构建生成真实依赖元数据；列表→首 license；无 license 为空；website 为空无 action；构建信息与平台 adapter 分离。 |

## 严格串行 DAG

按以下顺序一次执行一个子 Task，不并发写共享文件：

`1 → 2 → 3 → 3R → 4A → 4B → 4C → 4D → 4E → 5 → 6 → 7 → 8 → 9 → 10A → 10B → 10C → 10D → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18 → 19 → 20`

其中 `DesktopSettingsCatalog.kt` 只在 5–8、11 串行修改；`AppearanceSettingsScreen.kt` 只在 4A→6→11→16 串行修改；`AboutScreen.kt` 与 Tracking/ExtensionRepo 只在 4E→8→15→18 串行修改。

## 执行状态

- [x] Task 1：固定原版 provenance 与行为 fixture
- [x] Task 2：共享设置搜索与 breadcrumb 契约
- [x] Task 3：当前 Android consumer 消费共享搜索契约
- [x] Task 3R：Android 设置搜索 production 默认 shared wiring 证据
- [x] Task 4A：Desktop 入口/基础设置 i18n 同源化
- [x] Task 4B：Desktop Reader/Library/Download 设置 i18n 同源化
- [ ] Task 4C：Desktop Backup 设置 i18n 同源化
- [ ] Task 4D：Desktop 安全/高级设置 i18n 同源化
- [ ] Task 4E：Desktop About/扩展/Tracking i18n 同源化
- [ ] Task 5：Desktop 设置 catalog、搜索 Screen 与入口
- [ ] Task 6：Desktop 标题 anchor 核心与基础页面
- [ ] Task 7：Desktop 标题 anchor 的安全/数据页面
- [ ] Task 8：Desktop 标题 anchor 的扩展页面
- [ ] Task 9：共享主题模块、identity/default/codec 与 Android consumer
- [ ] Task 10A：共享静态调色板第一批
- [ ] Task 10B：共享静态调色板第二批
- [ ] Task 10C：共享静态调色板第三批
- [ ] Task 10D：共享调色板 selector/AMOLED 与 Monet adapter 收口
- [ ] Task 11：Desktop 主题 adapter、外观 UI 与迁移
- [ ] Task 12：共享许可证 notice 与详情选择契约
- [ ] Task 13：Desktop 许可证元数据构建生成
- [ ] Task 14：Desktop 许可证 provider 与 DI identity
- [ ] Task 15：Desktop 许可证列表/详情与 About wiring
- [ ] Task 16：Desktop 设置 accessibility primitives 与入口页面
- [ ] Task 17：Desktop 设置 accessibility 内容页面第一批
- [ ] Task 18：Desktop 设置 accessibility 内容页面第二批
- [ ] Task 19：IDs 88/90/91/94 exact parity evidence
- [ ] Task 20：whole-change 审查与三平台 verify

## 全局门禁

1. 每个实现 Task 由一个实现代理按 RED→GREEN→重构完成，再由未参与实现的 reviewer 独立审查；首审有 Critical/Important 时只允许一轮修复复审，仍失败则重新规划该单一风险。
2. 普通 Task 只运行定向测试、Spotless 和 diff/range gate；全量测试、版本递增构建及 Windows/macOS 运行验收集中在 Task 20。
3. 同一 worktree 不并发 Gradle。每个 Task 精确暂存并自动提交；排除用户已有 `AGENTS.md`、`DownloadQueueScreen.kt` 和 SDK/Gradle/build/OpenSpec 残留。
4. 测试必须执行真实 production model、route、DI、resource/provider 或 Compose semantics；源码字符串扫描不能替代行为证据。新增 Screen/导航/DI 必须有实例化、类型安全和 production wiring 测试。
5. 当前 Android consumer 测试只能证明迁移后消费 shared，不能单独证明原版一致；原版结论必须绑定 fixed-main blob/path/symbol 或 provenance fixture。
6. 用户可见 capability 必须有入口和反馈；内部 catalog/generator 必须被真实 UI 链消费并有集成测试。Desktop 独有能力不得因共享抽取而删除、降级或隐藏。
7. 调色板从 Android source 移入共享 Compose Multiplatform 模块时必须保持高相似度 rename；按 `git diff --numstat` 新增+删除计算 touched。任一批次若实际超过 400 行，实施前继续拆分，不以净行数或 waiver 掩盖。

## 计划审查状态

- 初审 `0/7/1`：指出 palette 范围/TDD、catalog 顺序、license DI 闭链、ID88 键盘分层、i18n 同源、错误路径、状态源与同文件串行问题；修订版已全部关闭。
- 唯一修复复审 `0/2/0`：指出 palette 在 Base 迁移前不可编译，以及原 `internal` 对象跨模块不可见。按门禁停止继续复审并重规划 Task 10A–10D：10A 先移动 Base；每批 rename 同步提供最小跨模块可见 API，使每个提交独立编译；10D 再统一到 public selector，Monet 继续留 Android adapter。
- 重规划后项目 guard 检查 26 个待办 Task 正文通过；实现从 Task 1 开始，任何 palette 批次实际 touched 超限时必须在实施前进一步拆分。

## 父 roadmap 映射

| 父 Task 5B Step | 子 Task |
|---|---|
| 1 固定原版设置搜索 RED | 1–3 |
| 2 主题/许可 RED | 1、9–15 |
| 3 共享搜索/主题语义 | 2、3、9–10D |
| 4 Desktop 设置搜索/anchor | 4A–8 |
| 5 Desktop 外观叠加 | 9–11 |
| 6 许可生成与详情 | 12–15 |
| 7 控件语义/焦点/键盘 | 16–18 |
| 8 可观察语义验证 | 16–18、20 |
| 9 Screen/导航/DI/资源 | 4A–8、11、13–15、19 |
| 10 shared/Android/Desktop 测试 | 每项 focused；20 full matrix |
| 11 IDs 88/90/91/94 | 1、19、20 |

### Task 1：固定原版 provenance 与行为 fixture

**Risk axis:** settings-provenance

**Platform boundary:** verification

**Estimated scope:** 3 files, 300 lines

**Verification:** fixed-main blob/path/symbol identity、搜索/主题/许可/无障碍 exact fixtures、fork/shim authority mutation rejection

**Files:** fixed-main inventory、`fixed-main-settings-fixtures.json`、`SettingsFixedMainProvenanceTest.kt`。

1. RED：交换 fixed-main 与当前 consumer/shim 路径、修改 blob、九页顺序/过滤/default/首许可规则时失败。
2. GREEN：用固定提交真实 blob/path/symbol 建最小 fixture；ID 88 明确“无专用页面”。
3. 不提前升级 manifest `NOT_STARTED`；运行 provenance/JSON/Spotless/range gate。

**Review status（已完成）：** 初始实现 `c10e22a83` 固定 ID 88/90/91/94 的 15 条真实 fixed-main authority；首审以 Critical/Important/Minor `0/2/0` 指出 ID91 未绑定未知枚举回退与废弃主题 picker 的实际 authority，以及 fixture schema/mutation 仍可接受自创字段、缺失过滤键、shim 或虚构 AccessibilityScreen。唯一修复 `d0a069bde` 增补 `PreferenceStore.getEnum` blob `2016f3d…` 与 `AppThemePreferenceWidget.filterNot` blob `5e3f76e…`，并对所有嵌套对象执行 exact-key 校验；RED 分别为 schema/mutation `6 tests/5 failed`、authority 缺链 `6 tests/1 failed`，GREEN 为 provenance `6/6`、既有 product contract `32/32`。严格 UTF-8 JSON、Spotless、diff/range gate 通过，累计 `3 files/264 touched`，manifest 与产品代码未改；唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。下一项为父 Task 5B / 子 Task 2。

### Task 2：共享设置搜索与 breadcrumb 契约

**Risk axis:** settings-search-policy

**Platform boundary:** shared

**Estimated scope:** 4 files, 360 lines

**Verification:** order、title/summary match、disabled/blank/info/group filtering、limit10、LTR/RTL breadcrumb、duplicate-title boundary

**Files:** common `SearchablePreference.kt`、`SettingsSearchPolicy.kt`、common test、`domain/build.gradle.kts`。

1. RED：fixture 覆盖固定原版过滤、顺序、上限和 breadcrumb；keywords/ranking mutation 必须失败。
2. GREEN：不依赖 Compose/Voyager 的 entry/group/screen route token 与纯 policy；anchor 保留 title identity。
3. 运行 common/JVM、fixture、Spotless/range gate。

**Review status（已完成）：** 实现 `7d0a368f7` 先因缺少 production 类型取得编译 RED，再新增无 Compose/Voyager 依赖的 shared entry/group、泛型 route token、结果模型和纯搜索策略；空查询补充 RED 为 `7 tests/1 failed`，GREEN 后 focused `7/7`。首审以 Critical/Important/Minor `0/1/0` 指出测试未杀死 direct-before-group、匹配过滤前 `take(10)` 与 summary 大小写敏感三种变异。唯一修复 `325f9568e` 仅调整测试输入，三种 mutation 各自精确得到 `7 tests/1 failed`；production 未变，复审 APPROVED `0/0/0`。Task 1 provenance `6/6`、Spotless、diff/range 与项目 guard 通过，累计 `3 files/265 touched`，未修改 Gradle、manifest、计划外 consumer 或用户脏文件。下一项为父 Task 5B / 子 Task 3。

### Task 3：当前 Android consumer 消费共享搜索契约

**Risk axis:** android-settings-search-consumer

**Platform boundary:** android

**Estimated scope:** 6 files, 380 lines

**Verification:** real Preference projection、shared policy invocation、replace route、one-shot title highlight、single/two-pane navigation

**Files:** Android `SearchableSettings.kt`、`SettingsSearchScreen.kt`、`PreferenceScreen.kt`、两份行为/导航测试、`app/build.gradle.kts`。

1. RED：断开 shared policy、route replace 或 highlight consume 任一 production wiring 时失败。
2. GREEN：Android Preference tree 只做平台投影与 Compose route/highlight，不反向冒充原版证据。
3. 运行 Android focused、navigation/Screen smoke、Spotless/range gate。

**Review status（已完成）：** 初始实现 `14a3f5bf7`，唯一修复 `8f01dcb91`；累计 `5 files/380 touched`。首次审查 `0/2/0` 指出的空查询 eager index 与真实 caller/滚动/RTL/no-result 证据已关闭：API 36 设备测试 `5/5`，JVM consumer `2/2`，Task 2 shared `7/7`，Task 1 provenance、Spotless、diff/guard 均通过；空查询、caller、0.4s、未命中清理、点击顺序与 shared helper 断开 mutation 均取得精确 RED。唯一复审仍以 `0/1/0` 拒绝 production 默认 shared identity 可被显式测试 seam 绕过，因而将该单一风险重规划为子 Task 3R；3R 通过独立审查后，本 Task 与 3R 一并关闭。下一项为父 Task 5B / 子 Task 4A。

### Task 3R：Android 设置搜索 production 默认 shared wiring 证据

**Risk axis:** android-settings-search-default-wiring

**Platform boundary:** android

**Estimated scope:** 4 files, 160 lines

**Verification:** production-default SearchResult→searchSettings→SettingsSearchPolicy identity、local-copy mutation rejection、既有 Android 搜索回归

**Files:** `SettingsSearchScreen.kt`、`SettingsSearchNavigationUiTest.kt`。

1. RED：设备测试不显式传 `searchPolicy`；将真实 caller 临时改为 `emptyList()` 时必须因缺少 production 结果节点失败。将 helper 临时改为行为等价的本地副本时，行为断言可通过但 JVM 对 shared object 的调用验证必须失败。
2. GREEN：移除 `SearchResult` 的 policy 注入参数，production 固定调用 `searchSettings`；由设备测试锁定 caller→helper，由既有 JVM MockK 调用验证锁定 helper→`SettingsSearchPolicy`，不新增 Android test 依赖或 production hook。
3. 复跑子 Task 3 的 JVM/设备/原版 provenance、Spotless/diff/range/guard；不得新增 production DI、可变测试 hook 或业务规则副本。

**Review status（已完成）：** 实现提交 `ea02ddfa2`，范围为 `2 files, 1+/13-`。真实设备测试不再显式注入 shared lambda，`SearchResult → searchSettings → SettingsSearchPolicy.search` 成为不可替换的 production 链；caller→`emptyList()` 变异由设备端 RTL breadcrumb/result 节点精确杀死，helper→等价本地副本变异在行为结果不变时由 JVM `verify(exactly = 1)` 精确杀死。API 36 / ADB 5038 XML 为 `5/5`、Android consumer `2/2`、shared policy `7/7`、provenance `6/6`，Spotless、diff、range 与项目 guard 均通过；无新增依赖、production hook、业务副本或用户脏文件。独立审查 APPROVED，Critical/Important/Minor `0/0/0`。下一项为父 Task 5B / 子 Task 4A。

### Task 4A：Desktop 入口/基础设置 i18n 同源化

**Risk axis:** settings-i18n-entry

**Platform boundary:** desktop

**Estimated scope:** 6 files, 380 lines

**Verification:** More/General/Appearance page title-summary-action consume same base/zh-rCN MR identity；hardcoded-equivalent mutation rejection

**Files:** `MoreRootScreen.kt`、`GeneralSettingsScreen.kt`、`AppearanceSettingsScreen.kt`、base/`zh-rCN` strings、`DesktopSettingsResourceIdentityTest.kt`。

1. RED：页面标题与预定 catalog resource identity 不同、zh 缺键或用等价英文硬编码时失败。
2. GREEN：迁移用户可见 title/summary/content description/state 文案；不改变设置行为。
3. 运行 i18n completeness、真实 Compose rendered copy、Spotless/range gate。

**Review status（已完成）：** 实现 `4975cca27` 将 More、General、Appearance 的用户可见文案接入 MR，标准语义复用 fixed-main identity，Desktop 独有组合语义使用 base/zh-rCN 成对资源；真实 Compose RED 为 `2 tests/1 failed`，精确发现硬编码 Tracking summary 与资源 identity 分叉，GREEN 后 focused `3/3`。首审 `0/2/0` 指出 fixed-main `pref_tracking_summary` 会错误宣称 Desktop 不具备的增强同步，以及零下载 subtitle 可被同名 title 掩盖；唯一修复 `914b49336` 改用准确的 Desktop Tracking summary，并从 clickable `SettingsEntry` 验证 title→subtitle 关系。两项变异各取得 `2 tests/1 failed` 精确 RED，最终必要回归 `57/57`、Spotless、i18n XML、diff、range 与 guard 通过；累计 `6 files/371 touched`，保留全部导航、设置写入、队列状态和 Desktop 独有入口。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。下一项为父 Task 5B / 子 Task 4B。

### Task 4B：Desktop Reader/Library/Download 设置 i18n 同源化

**Risk axis:** settings-i18n-content

**Platform boundary:** desktop

**Estimated scope:** 6 files, 300 lines

**Verification:** Reader/Library/Download同源MR、base/zh-rCN completeness、anchor-title identity

**Files:** 三个 Settings Screen、base/`zh-rCN` strings、既有 resource identity test。

1. RED：任一页面搜索 title 与真实页面 title 不同源、用户可见英文硬编码或 zh 缺键时失败。
2. GREEN：只迁移文案 accessor，保留 reader/library/download 全部 Desktop 行为。
3. 运行三页 rendered copy、resource completeness、Spotless/range gate。

**Split evidence:** 原 Task 4B 在四页 production GREEN 后实际达到 `7 files/416 touched`，超过 400 行上限；不得通过把资源清单并列压缩掩盖范围，因此按不共享 production Screen 的风险边界拆为修订后的 4B 与新增 4C，原 4C/4D 顺延为 4D/4E。已取得的共同 RED 为真实 Reader 页面硬编码 `Default Reading Mode` 与 fixed-main `pref_viewer_type` identity 分叉；未提交的 Backup 修改不计为 4B 证据，必须在 4B 审查关闭后由 4C 独立重做和验证。

**Review status（已完成）：** 实现 `1de3bee43` 仅迁移 Reader、Library、Download 三页及其 base/zh-rCN 资源，真实 production RED 为 `1/1 failed`，精确暴露 Reader 硬编码 `Default Reading Mode` 与 fixed-main `pref_viewer_type` identity 分叉；GREEN 后资源/渲染 `3/3`、必要回归 `73/73`，累计范围初始 `6 files/199 touched`，Backup production/资源/断言均零 diff。首审 `0/1/0` 指出 Library 仅覆盖 OFF、未执行 6H/12H/24H/WEEKLY 四个已迁移状态；唯一修复 `ddb910099` 对每个 locale/interval 写入真实 preference、创建独立 ImageComposeScene，并从 `Selected=true` 的同一 radio entry 断言 MR identity。24H 临时硬编码 `Daily` 在中文轮精确 RED，恢复后 GREEN；复审限定命令独立重跑 `31/31`，Spotless、diff、range 与 guard 通过，累计 `6 files/229 touched`。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。下一项为父 Task 5B / 子 Task 4C。

### Task 4C：Desktop Backup 设置 i18n 同源化

**Risk axis:** settings-i18n-backup

**Platform boundary:** desktop

**Estimated scope:** 4 files, 300 lines

**Verification:** Backup main/preview/progress/completed/partial/error/cancel states consume base/zh-rCN MR、chooser/snackbar production feedback、anchor-title identity

**Files:** `BackupSettingsScreen.kt`、base/`zh-rCN` strings、既有 resource identity test。

1. RED：Backup 主屏、确认、预览、加载/恢复/完成/部分成功/取消/错误、chooser/snackbar 任一 production 状态使用硬编码、zh 缺键或 identity 分叉时失败。
2. GREEN：只迁移 presentation copy 与格式化 accessor，保留 `.tachibk` 兼容、最大备份、调度、文件选择、恢复事务和错误分类行为。
3. 运行 Backup production state/rendered copy、resource completeness、既有 backup wiring、Spotless/range gate。

### Task 4D：Desktop 安全/高级设置 i18n 同源化

**Risk axis:** settings-i18n-security

**Platform boundary:** desktop

**Estimated scope:** 5 files, 380 lines

**Verification:** Advanced/Security title、danger/capability/credential feedback同源MR与base/zh-rCN完整

**Files:** `AdvancedSettingsScreen.kt`、`SecuritySettingsScreen.kt`、base/`zh-rCN` strings、resource identity test。

1. RED：danger/unsupported/auth feedback 未本地化或搜索 title 与页面不一致时失败。
2. GREEN：只替换 presentation copy，不改 challenge、credential、window privacy production 规则。
3. 运行 Security/Advanced wiring、rendered copy、Spotless/range gate。

### Task 4E：Desktop About/扩展/Tracking i18n 同源化

**Risk axis:** settings-i18n-extended

**Platform boundary:** desktop

**Estimated scope:** 6 files, 400 lines

**Verification:** About/ExtensionRepo/TrackingSettings同源MR、base/zh-rCN completeness、Desktop-only copy preservation

**Files:** `AboutScreen.kt`、`ExtensionRepoScreen.kt`、`ui/tracking/TrackingSettingsScreen.kt`、base/`zh-rCN` strings、resource identity test。

1. RED：updater/诊断/扩展/tracking 用户反馈硬编码、zh 缺键或 identity 分叉时失败。
2. GREEN：迁移文案并保留全部 Desktop 独有能力。
3. 运行三页 production wiring/rendered copy、Spotless/range gate。

### Task 5：Desktop 设置 catalog、搜索 Screen 与入口

**Risk axis:** desktop-settings-search-entry

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** exact nine-screen prefix、Desktop-only append、shared top10、More entry/query feedback、Voyager type/Screen smoke

**Files:** `DesktopSettingsCatalog.kt`、`SettingsSearchScreen.kt`、`MoreRootScreen.kt`、base/`zh-rCN` strings、search wiring test、navigation contract、Screen smoke。

1. RED：交换九页、Desktop-only 前插、Desktop 项抢占原版 top10、catalog 未调用 shared policy或 route 类型错误时失败。
2. GREEN：前九页严格映射原版 screen IDs 到 Desktop routes；General/About 等只确定性追加。Browse/Data 分别映射 Desktop 真实扩展/备份入口。
3. 搜索初始聚焦、Enter/NumPadEnter/IME 清焦点；空/无结果有反馈。运行 UI/navigation/i18n/range gate。

### Task 6：Desktop 标题 anchor 核心与基础页面

**Risk axis:** desktop-settings-anchor-core

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** result→Screen→exact title→scroll/highlight→one-shot；duplicate first match；optional focus enhancement separate

**Files:** `DesktopSettingsAnchor.kt`、`SettingsComposables.kt`、General/Appearance/Reader/Library screens、两份 anchor tests。

1. RED：重复消费、错误 title、route 无 anchor、滚动但不高亮均失败；缺 focus 不能冒充原版 anchor failure。
2. GREEN：统一一次性 owner/highlight；额外 focus 独立标记和测试，不改变首个重复标题边界。
3. 保护 grid/reader/library 独有项；运行真实 Compose/navigation/range gate。

### Task 7：Desktop 标题 anchor 的安全/数据页面

**Risk axis:** desktop-settings-anchor-security

**Platform boundary:** desktop

**Estimated scope:** 6 files, 380 lines

**Verification:** Download/Backup/Advanced/Security real route-anchor、capability feedback preservation

**Files:** 四个 Settings Screen、`DesktopSettingsCatalog.kt`、安全/数据 anchor wiring test。

1. RED：只路由不落点、anchor 绕过 capability/credential feedback 或 catalog identity 分叉时失败。
2. GREEN：接统一 anchor，不重写业务。
3. 运行四页 focused/Security wiring/Spotless/range gate。

### Task 8：Desktop 标题 anchor 的扩展页面

**Risk axis:** desktop-settings-anchor-extended

**Platform boundary:** desktop

**Estimated scope:** 7 files, 380 lines

**Verification:** About/ExtensionRepo/TrackingSettings route-anchor、catalog completeness、Desktop-only append order

**Files:** 三个生产 Screen、`DesktopSettingsCatalog.kt`、extended anchor test、Tracking/Extension navigation tests。

1. RED：Desktop 独有项遗漏、前插九页、route/anchor identity 断开时失败。
2. GREEN：扩展 catalog/anchor adapter，不删除 updater/诊断/扩展/tracking 能力。
3. 运行 catalog/navigation/Screen smoke/Spotless/range gate。

### Task 9：共享主题模块、identity/default/codec 与 Android consumer

**Risk axis:** shared-theme-semantics

**Platform boundary:** shared+android

**Estimated scope:** 8 files, 400 lines

**Verification:** Compose Multiplatform module compiles both consumers、SYSTEM/default/Monet capability、unknown/deprecated fallback、canonical keys

**Files:** 新 `presentation-theme` module/build、settings include、AppTheme/ThemeMode 高相似度 move、ThemeDefaults/test、Android build与UiPreferences wiring。

1. RED：动态色 capability、未知字符串、废弃 picker 可见性、canonical key/default 漂移或 Desktop 无法依赖模块时失败。
2. GREEN：共享 module 持有 UI identity/default/codec；Android 保留 AppCompat/DynamicColors/Wallpaper adapter。
3. 不伪造显式 migration；运行 shared/Android compile与behavior、Spotless/range gate。

### Task 10A：共享静态调色板第一批

**Risk axis:** theme-palette-foundation

**Platform boundary:** shared+android

**Estimated scope:** 7 files, 400 lines

**Verification:** Base + Tachiyomi/GreenApple/Lavender/Yotsuba high-similarity moves、cross-module visibility、fixed-main exact tokens、Android shared-module wiring

**Files:** `BaseColorScheme.kt` 与四个 color-scheme 文件从 Android source high-similarity move 到 `presentation-theme/commonMain`；shared exact test、Android wiring test。

1. RED：token 交换或 Android 仍加载旧本地复制路径时失败。
2. GREEN：先移动 Base；保留 package/实现，采用 rename 而非重新抄写 ARGB。Base 与本批 palette 把原 `internal` 调整为最小跨模块可见 API，使 Android 在本提交即可继续直接消费；无平台 adapter 重复数据。
3. 检查 rename similarity 与真实 touched≤400；超限时实施前按 2+2 拆分。

### Task 10B：共享静态调色板第二批

**Risk axis:** theme-palette-muted

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 400 lines

**Verification:** Catppuccin/MidnightDusk/Monochrome/Nord high-similarity moves、exact tokens、Android wiring

**Files:** 四个 palette verbatim move、同一 shared exact/Android wiring tests。

1. 按 10A 的 RED/rename/consumer mutation 模式执行。
2. 不合并或近似颜色；保留 light/dark 语义，并在 rename 内把本批 palette 调整为最小跨模块可见，使当前 Android 在本提交可编译消费。
3. 检查 touched≤400；超限实施前按 2+2 拆分。

### Task 10C：共享静态调色板第三批

**Risk axis:** theme-palette-colorful

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 400 lines

**Verification:** Strawberry/Tako/TealTurqoise/TidalWave high-similarity moves、exact tokens、Android wiring

**Files:** 四个 palette verbatim move、同一 shared exact/Android wiring tests。

1. 按 10A 的 RED/rename/consumer mutation 模式执行。
2. 原版拼写/序列化 identity 保持兼容，并在 rename 内把本批 palette 调整为最小跨模块可见，使当前 Android 在本提交可编译消费。
3. 检查 touched≤400；超限实施前按 2+2 拆分。

### Task 10D：共享调色板 selector/AMOLED 与 Monet adapter 收口

**Risk axis:** theme-palette-selection

**Platform boundary:** shared+android

**Estimated scope:** 8 files, 400 lines

**Verification:** YinYang move、public selector、dark AMOLED、Monet Android adapter、deprecated/unknown fallback

**Files:** YinYang high-similarity move、共享 public selector/test、Android `MonetColorScheme.kt`/`TachiyomiTheme.kt`、Android wiring test、module dependency cleanup。

1. RED：selector 错配、light AMOLED 生效、dark AMOLED 未置黑、Monet 被搬进 common 或 deprecated 未回退时失败。
2. GREEN：YinYang 调整为最小跨模块可见；在已可独立消费的 public palettes/Base 上建立最终 public selector，Android/Desktop 后续只消费 selector。Monet 只留 Android adapter，Desktop capability=false 不展示。
3. 运行全部 palette/Android adapter、Spotless/touched gate。

### Task 11：Desktop 主题 adapter、外观 UI 与迁移

**Risk axis:** desktop-theme-consumer

**Platform boundary:** shared+desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** shared ThemeMode/AppTheme/static ColorScheme、legacy theme_mode migration、SYSTEM、grid preservation、Monet honesty

**Files:** Desktop preferences/migration/theme/Appearance/catalog、preferences tests、migration test、Appearance wiring test。

1. RED：Desktop 重复 enum、legacy 丢失、主题/AMOLED不改变真实 scheme、grid 删除、Monet虚假可选时失败。
2. GREEN：Desktop 直接依赖 `presentation-theme` 静态实现；system-dark 留 adapter，Monet capability=false。
3. 回归 search anchor/i18n；运行 focused/Spotless/range gate。

### Task 12：共享许可证 notice 与详情选择契约

**Risk axis:** license-notice-contract

**Platform boundary:** shared

**Estimated scope:** 3 files, 260 lines

**Verification:** deterministic order、first-license、empty website/license、malformed metadata result

**Files:** common `DependencyNotice.kt`、`LicenseNoticePolicy.kt`、common test。

1. RED：无 license 被伪造、第二项覆盖第一、空 website 仍有 action、排序不稳定时失败。
2. GREEN：纯不可变 model/selection policy，不引入 Android raw/HTML 或 Desktop file/URI。
3. 运行 shared/Spotless/range gate。

### Task 13：Desktop 许可证元数据构建生成

**Risk axis:** desktop-license-generation

**Platform boundary:** tooling

**Estimated scope:** 6 files, 400 lines

**Verification:** real resolved configuration/POM or AboutLibraries pipeline→deterministic packaged metadata、runtime resource presence

**Files:** `app-desktop/build.gradle.kts`、buildSrc plugin/task与测试、generated-resource integration test、build wiring fixture。

1. RED：手写 JSON 能过但真实 resolved dependency 未进入输出、顺序不稳定或 packaged resource 缺失时失败。
2. GREEN：复用仓库已解析依赖/POM/AboutLibraries metadata，生成确定性 Desktop resource；不复制 Android raw。
3. 运行 buildSrc functional/generated resource/Spotless/range gate。

### Task 14：Desktop 许可证 provider 与 DI identity

**Risk axis:** desktop-license-runtime-wiring

**Platform boundary:** desktop

**Estimated scope:** 6 files, 360 lines

**Verification:** generated resource→provider→shared policy→DesktopAppModule→DesktopUiDependencies same identity、malformed/absent structured failure

**Files:** provider及test、`DesktopAppModule.kt`、`DesktopUiDependencies.kt`、`DesktopDiWiringTest.kt`、runtime resource integration test。

1. RED：provider 读 fixture 而非 Task13 output、DI 重建第二实例或 malformed 静默空列表时失败。
2. GREEN：真实 generated resource 只解析一次并以同一 provider进入 UI dependencies；错误结构化反馈。
3. 运行 provider/DI identity/integration、Spotless/range gate。

### Task 15：Desktop 许可证列表/详情与 About wiring

**Risk axis:** desktop-license-ui

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** About→list→detail、real injected provider、website action、empty license、navigation/resource failure feedback

**Files:** 两个 license Screen、About、base/`zh-rCN` strings、UI wiring test、navigation contract、Screen smoke。

1. RED：About 不消费 injected provider、详情选错 license、空 website 可点或 resource 错误无反馈时失败。
2. GREEN：Voyager Screen + Desktop URI adapter；保留 updater/路径/cache/extension/环境诊断。
3. 运行 generated resource→DI→UI、navigation/i18n/Spotless/range gate。

### Task 16：Desktop 设置 accessibility primitives 与入口页面

**Risk axis:** settings-accessibility-primitives

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** fixed-main Enter+NumPadEnter KeyDown exactly once、Desktop Space role enhancement、single action、Role/state/disabled、focus order

**Files:** `SettingsComposables.kt`、More/Search/Appearance/General screens、accessibility contract/keyboard tests、`zh-rCN` completeness test。

1. RED：漏 NumPadEnter、KeyUp 再触发、整行/子控件双触发、无 role/state/disabled、搜索不获焦时失败。
2. GREEN：Enter/NumPadEnter 严格标为 fixed-main；Space 仅对支持的 Desktop Button/Checkbox role 增强。装饰图标不制造重复语义。
3. 用真实 Compose semantics/key/focus 行为测试，不扫描源码；回归 anchor 行为不要求 focus。

### Task 17：Desktop 设置 accessibility 内容页面第一批

**Risk axis:** settings-accessibility-content-a

**Platform boundary:** desktop

**Estimated scope:** 6 files, 380 lines

**Verification:** Reader/Library/Download/Backup keyboard reachability、roles/state feedback、anchor focus enhancement

**Files:** 四个 Screen、content accessibility test、content keyboard test。

1. RED：任一 control 无唯一语义 action/状态/键盘路径或 focus enhancement 改变原版 anchor identity时失败。
2. GREEN：复用 Task16 primitives，不重写业务；所有 labels 来自 Task4B同一 MR。
3. 运行四页 UI/anchor/semantics/Spotless/range gate。

### Task 18：Desktop 设置 accessibility 内容页面第二批

**Risk axis:** settings-accessibility-content-b

**Platform boundary:** desktop

**Estimated scope:** 7 files, 400 lines

**Verification:** Security/Advanced/About/TrackingSettings/ExtensionRepo keyboard/semantics、danger confirmation与capability feedback

**Files:** 五个 Screen、advanced accessibility test、advanced keyboard test。

1. RED：危险动作无法键盘确认/取消、unsupported 被读成成功开关、密码/身份字段缺语义时失败。
2. GREEN：复用 primitives并保留 credential/window privacy/challenge/updater/extension/tracking真实反馈；labels来自Task4C/4D同一MR。
3. 运行 production wiring/semantics/Spotless/range gate。

### Task 19：IDs 88/90/91/94 exact parity evidence

**Risk axis:** settings-parity-evidence

**Platform boundary:** verification

**Estimated scope:** 3 files, 320 lines

**Verification:** exact fixed-main/shared/current/adapter/protection sets、cross-ID/path/method mutation、Desktop unique capability preservation

**Files:** parity manifest、fixed-main inventory、DesktopProductCapabilityContractTest。

1. RED：删除/交换任一 ID 的 fixed-main symbol、shared/current consumer或行为方法时失败。
2. GREEN：真实 capability完成后才更新状态；ID88不虚构页面，ID94不以generator自测冒充UI。
3. 运行 exact contract、绑定 behavior smoke、JSON/Spotless/range gate。

### Task 20：whole-change 审查与三平台 verify

**Risk axis:** settings-change-verify

**Platform boundary:** verification

**Estimated scope:** 5 files, 320 lines

**Verification:** independent whole-change review、shared/Android/Desktop full matrix、Windows fixed EXE、macOS app、Android emulator、Linux boundaries

**Files/Artifacts:** 本计划、修正版父 roadmap、验证报告、仅由构建脚本修改的 AppVersion、Windows fixed EXE。不得修改 `.superpowers/sdd/progress.md` 或 OpenSpec/Comet 状态。

1. reviewer 对 `base-ref..HEAD` 核对 fixed-main、shared/两端 consumer、Desktop 独有能力、搜索/主题/许可/无障碍 production wiring与测试有效性；Critical/Important只允许一轮修复复审。
2. 审查清零后串行运行 Spotless、domain/data、当前 Android、Desktop/test-desktop全量；Android API36验证搜索、theme/default/AMOLED/语言边界与可访问入口。
3. 仅用 `scripts/build-desktop.sh` 生成新 BUILD；Windows fixed EXE验证搜索→anchor、主题、grid、licenses、键盘/semantics/TestMode；macOS `ssh mbp` 验证同版本 app与可用 accessibility tree，SSH不能替代的screen-reader交互明确限界。
4. Linux/WSL只验证可用theme/resource/keyboard/capability adapter。报告完整版本、命令/计数/失败、EXE、OS、IDs状态和剩余有意偏差；全部通过后勾选父Task5B并继续父Task6。
