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

`1 → 2 → 3 → 3R → 4A → 4B → 4C → 4D → 4E → 4F → 4G → 4H → 4I → 4J → 4K → 4L → 4M → 5 → 6A → 6B → 7A → 7B → 7C → 8A → 8B → 8C → 9 → 10A → 10B → 10C → 10D → 10E → 11 → 12 → 13 → 14 → 15 → 16 → 17 → 18A → 18B → 18C → 18D → 18E → 18F → 19 → 20A → 20B → 20`

其中 `DesktopSettingsCatalog.kt` 只在 5→7A→7B→7C→8A→8B→8C→11 串行修改；`AppearanceSettingsScreen.kt` 只在 4A→6A→11→16 串行修改；`AboutScreen.kt` 只在 4I→8A→15→18B、`ExtensionRepoScreen.kt` 只在 4J→8B→18B→18C→18D、`TrackingSettingsScreen.kt` 只在 4K→4L→8C→18E→18F 串行修改；4M 只补 4L 的真实动作/失败路径测试，不返改 production。

## 执行状态

- [x] Task 1：固定原版 provenance 与行为 fixture
- [x] Task 2：共享设置搜索与 breadcrumb 契约
- [x] Task 3：当前 Android consumer 消费共享搜索契约
- [x] Task 3R：Android 设置搜索 production 默认 shared wiring 证据
- [x] Task 4A：Desktop 入口/基础设置 i18n 同源化
- [x] Task 4B：Desktop Reader/Library/Download 设置 i18n 同源化
- [x] Task 4C：Desktop Backup 设置 i18n 同源化
- [x] Task 4D：Desktop Backup 错误与系统反馈 production identity
- [x] Task 4E：Desktop Backup picker 与 production feedback wiring
- [x] Task 4F：Desktop Backup typed preview reason production contract
- [x] Task 4G：Desktop Advanced 设置 i18n 同源化
- [x] Task 4H：Desktop Security 设置 i18n 同源化与 locale 隔离
- [x] Task 4I：Desktop About 与 updater/诊断 i18n 同源化
- [x] Task 4J：Desktop Extension repository i18n 同源化
- [x] Task 4K：Desktop Tracking typed message 与 formatter identity
- [x] Task 4L：Desktop Tracking UI/dialog i18n 同源化
- [x] Task 4M：Desktop Tracking 动作副作用与失败 fallback identity
- [x] Task 5：Desktop 设置 catalog、搜索 Screen 与入口
- [x] Task 6A：Desktop 标题 anchor 核心、搜索交接与 General/Appearance
- [x] Task 6B：Desktop 标题 anchor 的 Reader/Library 接线
- [x] Task 7A：Desktop 标题 anchor 的 Download/Backup 数据页面
- [x] Task 7B：Desktop 标题 anchor 的 Advanced 页面
- [x] Task 7C：Desktop 标题 anchor 的 Security 页面
- [x] Task 8A：Desktop 标题 anchor 的 About 页面
- [x] Task 8B：Desktop LazyList anchor 核心与 ExtensionRepo 页面
- [x] Task 8C：Desktop 标题 anchor 的 Tracking 页面
- [x] Task 9：共享主题模块、identity/default/codec 与 Android consumer
- [x] Task 10A：共享静态调色板基础与第一批
- [x] Task 10B：共享静态调色板第一批收口
- [x] Task 10C：共享静态调色板第二批
- [x] Task 10D：共享静态调色板第三批
- [x] Task 10E：共享调色板 selector/AMOLED 与 Monet adapter 收口
- [x] Task 11：Desktop 主题 adapter、外观 UI 与迁移
- [x] Task 12：共享许可证 notice 与详情选择契约
- [x] Task 13：Desktop 许可证元数据构建生成
- [x] Task 14：Desktop 许可证 provider 与 DI identity
- [x] Task 15：Desktop 许可证列表/详情与 About wiring
- [x] Task 16：Desktop 设置 accessibility primitives 与入口页面
- [x] Task 17：Desktop 设置 accessibility 内容页面第一批
- [x] Task 18A：Desktop Security/Advanced accessibility
- [x] Task 18B：Desktop About/ExtensionRepo accessibility
- [x] Task 18C：Desktop ExtensionRepo physical-key coverage
- [x] Task 18D：Desktop ExtensionRepo async test stabilization
- [x] Task 18E：Desktop Tracking accessibility
- [x] Task 18F：Desktop Tracking service-action state matrix
- [x] Task 18G：Desktop Tracking login dialog keyboard accessibility
- [x] Task 18H：Desktop Tracking logout/unbind dialog keyboard accessibility
- [x] Task 19：IDs 88/90/91/94 exact parity evidence
- [x] Task 20A：共享许可证首项规则与 Desktop production wiring
- [x] Task 20B：Android 许可证 shared consumer 与 parity evidence
- [ ] Task 20C：Windows updater 测试 helper 启动边界
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
- 唯一修复复审 `0/2/0`：指出 palette 在 Base 迁移前不可编译，以及原 `internal` 对象跨模块不可见。按门禁停止继续复审并重规划 Task 10A–10E：10A 先移动 Base；每批 rename 同步提供最小跨模块可见 API，使每个提交独立编译；10E 再统一到 public selector，Monet 继续留 Android adapter。
- 重规划后项目 guard 检查 26 个待办 Task 正文通过；实现从 Task 1 开始，任何 palette 批次实际 touched 超限时必须在实施前进一步拆分。

## 父 roadmap 映射

| 父 Task 5B Step | 子 Task |
|---|---|
| 1 固定原版设置搜索 RED | 1–3 |
| 2 主题/许可 RED | 1、9–15 |
| 3 共享搜索/主题语义 | 2、3、9–10E |
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

**Review status（已完成）：** 实现 `9e94d417f` 完成 Backup 主屏、预览、进度、基础终态与全部自动备份 interval 的 MR 接线；production RED 为 `1/1 failed`，精确暴露硬编码 `Backup and Restore`，GREEN 为 focused `1/1`、Backup 必要回归 `53/53`，范围 `4 files/297 touched`。首审 `0/2/0` 发现两个同源 production identity 风险：PartialSuccess 误用整体恢复失败语义且 Failure 继续显示 ScreenModel 固定中文；chooser/snackbar/错误列表资源只有存在性检查，硬编码消费点仍可逃逸。结构化错误边界由 4D 修复，真实 picker/feedback 与六种 preview reason 证据由 4E/4F 闭合；4F 独立审查 APPROVED `0/0/0` 后确认本 Task 全部遗留拒绝点已关闭。下一项为父 Task 5B / 子 Task 4G。

### Task 4D：Desktop Backup 错误与系统反馈 production identity

**Risk axis:** settings-i18n-backup-feedback

**Platform boundary:** desktop

**Estimated scope:** 6 files, 380 lines

**Verification:** structured AppError→localized Failure/Partial copy、chooser title/filter/cancel、create snackbar success/failure、restore error count production formatter identity

**Files:** `BackupRestoreScreenModel.kt`、`BackupSettingsScreen.kt`、`BackupRestoreScreenModelTest.kt`、base/`zh-rCN` strings、既有 resource identity test。

1. RED：真实 Storage/Permission/Malformed/PartialFailure 在 en/zh 任一详情错语、Partial 裸数字或整体失败误分类、chooser/snackbar/filter/error-count 任一 production formatter 硬编码或接错 MR 时失败。
2. GREEN：ScreenModel 保留结构化错误分类至 presentation 边界；production 与测试共同消费不可变纯 formatter/MR，不增加可替换 lambda、反向解析 message、Locale formatter 兼容 accessor 或业务规则副本；现有 ScreenModel 测试直接迁移到 typed reason/AppError，不改变 chooser、备份事务和 retry/cancel 规则。
3. 复跑 4C 全部 production state、Backup ScreenModel/workflow/creator/restorer/compat/scheduler、Spotless/diff/range/guard；通过后同时关闭 4C/4D。

**Review status（已完成）：** 实现 `8273e0482` 以 `BackupRestoreFailureReason`、原始 `AppError`/`PartialFailure` 和纯 production formatter 消除固定中文、整体失败误分类及 formatter 自证；编译 RED 后 focused `3/3`、Backup 回归 `54/54`、Spotless/diff/guard 通过，范围 `6 files/350 touched`。作为 4C 唯一修复复审的独立审查仍以 `0/1/0` 拒绝：六种 preview reason 只执行 UnsupportedVersion；picker/filter/cancel/snackbar 仅直接测 formatter，不能证明真实按钮消费，且 `restoreErrors` 对话框不可达。该单一风险由 4E 的 required picker/真实按钮/反馈 wiring 与死分支删除、4F 的六种真实 Model→Screen 双语言契约闭合；4F 独立审查 APPROVED `0/0/0` 后确认本 Task 全部遗留拒绝点已关闭。

### Task 4E：Desktop Backup picker 与 production feedback wiring

**Risk axis:** settings-backup-production-feedback

**Platform boundary:** desktop

**Estimated scope:** 6 files, 400 lines

**Verification:** Create/Restore button→picker request/result→local snackbar/Preview、Swing chooser config、DI identity、dead restoreErrors removal

**Files:** new `DesktopBackupFilePicker.kt`、`BackupSettingsScreen.kt`、`DesktopUiDependencies.kt`、`DesktopAppModule.kt`、`DesktopDiWiringTest.kt`、new `BackupSettingsProductionWiringTest.kt`。

1. RED：production DI 无法解析 picker；真实 Create/Restore 语义点击不能观察 Directory/BackupFile title/filter、Selected/Cancelled、create success/failure snackbar 或 Preview；Swing adapter 配置错误时失败。
2. GREEN：新增 required injected Backup 专用 picker request/result port 与 Swing adapter，复用既有 EDT+suspend 模式；真实 Screen 按钮构造 4D formatter 文案并消费 port，页面内 Snackbar 保持唯一即时反馈。删除无 setter 的 `restoreErrors` 死分支，不新增默认 lambda、测试专用 HTTP action、全局通知重复反馈或泛化 picker 框架。
3. 运行 production UI/DI、Backup 回归、Spotless/diff/range/guard；通过后继续 4F，不提前关闭 4C/4D。

**Split evidence:** 合并 picker/按钮 wiring 与六种 preview reason 的 GREEN 工作树实际达到 `6 files/402 touched`，超过 400 行上限；不得用机械压行规避门禁。两者不共享 production 修改：picker/按钮风险修改 port、DI、Screen，preview reason 风险只扩展既有 production ScreenModel/Screen 场景测试，因此按 4E/4F 串行拆分。4E 提交前移除未提交的六 reason 场景；4E 审查通过后由 4F 独立重新加入。

**Review status（已完成）：** 实现 `714afd1ae` 新增 required `DesktopBackupFilePicker`、Swing adapter、production DI/UiDependencies、真实 Create/Restore 按钮接线与死 `restoreErrors` 删除；编译 RED 后 focused `3/3`、Backup/DI 回归 `52/52`，初始范围 `6 files/352 touched`。首审 `0/1/0` 指出按钮反馈只跑默认 locale 且未覆盖空错误详情；唯一修复 `3f2db5843` 让 Locale.US/zh-CN 每个 case 使用新 ImageComposeScene，覆盖 request、cancel、saved、非空/空 detail failure，并在 finally 恢复 Locale。完整 en-US 硬编码取消文案在英文轮通过、中文轮精确 RED，丢失 failure detail 同样精确 RED；复审 focused `2/2`、Backup/DI `52/52`、Spotless/diff/guard 通过，累计基线 diff 为 `6 files/356 touched`，production 零修复 diff、4F reason 零混入。唯一修复复审 APPROVED，Critical/Important/Minor `0/0/0`。下一项为父 Task 5B / 子 Task 4F。

### Task 4F：Desktop Backup typed preview reason production contract

**Risk axis:** settings-backup-preview-reasons

**Platform boundary:** desktop

**Estimated scope:** 2 files, 180 lines

**Verification:** EmptyBackup/UnsupportedVersion/EmptyFile/MissingData/Corrupted/RestoreNotStarted each execute real ScreenModel and production Screen in en/zh with exact MR identity

**Files:** existing `BackupSettingsProductionWiringTest.kt`。

1. RED：六种 preview reason 任一未由真实 ScreenModel 产生、分类互换、Screen/formatter MR 接错或硬编码时失败。
2. GREEN：以真实 preview provider failure 驱动现有 `BackupRestoreScreenModel`，在 en/zh 各自挂载 production `BackupSettingsScreen` 并断言对应 4D MR；不直接调用 formatter代替 Screen、不修改 production 或创建测试专用分类路径。
3. 复跑 4C–4E 全状态、Backup/DI 回归、Spotless/diff/range/guard；独立审查通过后同时关闭 4C/4D/4E/4F。

**Review status（已完成）：** 实现 `abc347e1b` 仅在既有 production wiring test 新增 64 行；EmptyBackup、UnsupportedVersion、EmptyFile、MissingData、Corrupted、RestoreNotStarted 均由真实 preview provider/restore TaskState 驱动 `BackupRestoreScreenModel` 产生 typed Failure，再挂载 required UiDependencies/picker 的 production `BackupSettingsScreen`，在 Locale.US/zh-CN 共 12 个组合断言准确 MR。分类互换 mutation 精确 RED，恢复后目标 `3/3`、组合回归 `58/58`、Spotless/diff/guard 通过，production 零 diff。独立审查 APPROVED，Critical/Important/Minor `0/0/0`，并确认 4C/4D 全部遗留拒绝点已由 4D–4F 共同闭合。下一项为父 Task 5B / 子 Task 4G。

### Task 4G：Desktop Advanced 设置 i18n 同源化

**Risk axis:** settings-i18n-security

**Platform boundary:** desktop

**Estimated scope:** 4 files, 300 lines

**Verification:** Advanced title、cache/cookie/Cloudflare/FlareSolverr/crash-folder feedback同源MR与base/zh-rCN完整

**Files:** `AdvancedSettingsScreen.kt`、base/`zh-rCN` strings、resource identity test。

1. RED：cache/cookie/FlareSolverr/crash-folder danger、validation、success/failure feedback 未本地化或搜索 title 与页面不一致时失败。
2. GREEN：只替换 presentation copy，不改 challenge、network/cache/cookie production 规则。
3. 运行 Advanced/challenge wiring、rendered copy、Spotless/range gate。

**Split evidence:** Security+Advanced 的真实动态状态 GREEN 工作树为 `5 files/380 touched`；同一回归启用 JUnit 并行时，切换进程 Locale 的新场景会使既有 Security 测试偶发读取中文。正确隔离需要 `@Isolated`，范围将超过 380；不得用命令行关闭并行掩盖 CI 风险，因此按不共享 production Screen 的边界拆为 4G/4H。4G 提交前移除未提交的 Security production/资源/测试部分；4G 审查通过后由 4H 独立重做 Security 与 locale 隔离。

**Review status（已完成）：** 初始实现 `99ee9dea9` 将 Advanced 标题、返回说明、网络缓存、Cookie/Cloudflare/FlareSolverr 与崩溃日志文案接入同一 MR identity；真实 Compose focused `6/6`、默认 JUnit 并行相关回归 `102/102`，`@Isolated` 消除了进程 Locale 竞态。首审 `0/1/0` 指出崩溃日志打开成功/失败与缓存计算瞬态仅有资源枚举，交换 MR 或恢复硬编码仍会通过。唯一修复 `81888828d` 增加 production 默认 platform actions，并由真实 Advanced Screen 在 en/zh 下观察挂起态 `Calculating…`、完成值及目录打开成功/失败反馈；两类 mutation 均会精确 RED。复审 APPROVED `0/0/0`，Spotless、diff、guard 通过，累计 `4 files/291 touched`，Security 与用户脏文件零混入。下一项为父 Task 5B / 子 Task 4H。

### Task 4H：Desktop Security 设置 i18n 同源化与 locale 隔离

**Risk axis:** settings-i18n-security

**Platform boundary:** desktop

**Estimated scope:** 4 files, 400 lines

**Verification:** Security title/back/save/cancel/hide-notification shared MR、credential/backend/privacy capability/window privacy states in en/zh、parallel-safe Locale isolation

**Files:** `SecuritySettingsScreen.kt`、base/`zh-rCN` strings、resource identity test；必要时既有 `SecuritySettingsWiringTest.kt` 仅用于共享 locale isolation contract。

1. RED：credential success/mismatch/auth-failure、backend unavailable、privacy supported/unsupported、window privacy supported/unsupported 任一 production 状态未本地化或 identity 分叉时失败；默认并行回归不得跨测试泄漏 Locale。
2. GREEN：复用 fixed-main title/back/save/cancel/hide-notification MR，Desktop credential/backend/capability 文案补齐 base/zh；locale 场景使用 JUnit `@Isolated` 或等效全套隔离，不靠命令行关闭并行，不改 credential/window privacy 业务规则。
3. 运行 Security wiring/rendered/DI/并行回归、Spotless/diff/range/guard。

**Replan evidence:** 补齐 Security 的 `zh-rCN` 后，既有 `SecuritySettingsWiringTest` 不再走英文 fallback，暴露 4 个硬编码英文 capability 断言；若在该未隔离测试中临时切换进程 Locale，会重新制造默认并行竞态。将断言改为当前 Locale 的 MR identity 需要累计约 `4 files/305 touched`，仍低于项目 400 行硬上限，因此把本 Task 估算调整为 310 行，不为 5 行差额拆出无独立产品风险的子 Task，也不删除覆盖或压缩格式。

**Repair replan evidence:** 首审 `0/2/0` 证明 credential 字段 fallback 与 capability 同屏无序包含无法杀死 identity 对调。唯一修复需要按输入节点绑定 current/new/confirm，并以三个真实 Security Screen 配置分别覆盖 native/telemetry supported 与 unsupported、widget provider supported、unsupported+updates supported、两者 unsupported；自然格式预计累计 `4 files/369–389 touched`。这些状态共享同一 production Screen/capability 契约，不形成可独立交付的产品风险，故把本 Task 上限调整为 400 行并在唯一修复复审中一次闭合。

**Review status（已完成）：** 实现 `3ee98f9dd` 将 Security 标题、返回、保存、取消与隐藏通知内容切换到 fixed-main 共享 MR，并补齐 30 个 Desktop credential/privacy/window capability 中文状态；真实 Screen RED 首先精确暴露中文仍显示 `Security`，GREEN 后 Identity `7/7`、默认并行回归 `103/103`。首审 `0/2/0` 指出输入框 fallback 无法杀死 current/new/confirm identity 错接，且 native/telemetry/widget 同屏无序包含无法杀死 capability 分支互换。唯一修复 `bc4aa0f6d` 删除 fallback、按真实 editable 节点绑定三类口令字段，并用逐 capability 的 present/absent 场景锁定三组 widget 状态；field、native/telemetry、widget 三类 mutation 均精确 RED，production 相对实现提交零差异。唯一复审 APPROVED `0/0/0`，Identity `7/7`、默认并行 `103/103`、Spotless、diff、guard 通过；产品/测试/资源累计 `4 files/353 touched`，低于 400，用户脏文件零混入。下一项为父 Task 5B / 子 Task 4I。

### Task 4I：Desktop About 与 updater/诊断 i18n 同源化

**Risk axis:** settings-i18n-about

**Platform boundary:** desktop

**Estimated scope:** 5 files, 380 lines

**Verification:** About/updater/诊断/path/cache同源MR、base/zh-rCN completeness、Desktop-only copy preservation

**Files:** `AboutScreen.kt`、`DesktopUpdateScreenModel.kt`、base/`zh-rCN` strings、resource identity test。

1. RED：Idle/Checking/UpToDate/UpdateAvailable/NoCompatiblePackage/CheckFailed、Downloading/Verifying/Ready/HandingOff/HandedOff/InstallFailed/RetryableFailure/Cancelled/ManualOnly、版本/路径/DB/cache/扩展数/Java/OS/清缓存任一真实状态硬编码、zh 缺键或 identity 分叉时失败。
2. GREEN：复用 fixed-main About/update/action identity；Desktop KMP port、updater 状态与诊断/path/cache 保留准确 `desktop_*`，不删除 updater 或诊断能力。
3. 运行 About/updater production wiring/rendered copy、Spotless/range gate。

**Split evidence:** 原合并 4I 对 About、ExtensionRepo、Tracking 的只读盘点自然范围约 `920–1,130 touched`，且三者不共享 production Screen。按独立 Screen 拆为 4I/4J；Tracking 合并范围约 `380–470`，再按 typed model message/formatter 与其余 UI copy 的稳定接口串行拆为 4K/4L，避免超过 400 或用 waiver 掩盖范围。

**Review status（已完成）：** 实现 `fa8a89468` 将 About 标题/返回、版本、更新操作与诊断文案接入 fixed-main/shared 或准确 Desktop MR，并把全部 `DesktopUpdateState.presentation()`、手动打开失败反馈切换到 base/zh identity；真实 About Screen 覆盖 Idle→Available→Ready、临时 DB/cache/path/extensions/Java/OS、清缓存与安装对话框，诊断 label/value 对调 mutation 精确 RED。首审 `0/1/0` 指出 manual open fallback 只有资源枚举，旧英文硬编码仍可逃逸；唯一修复 `85c1cd430` 通过真实 controller/model 的 CHECK→DOWNLOAD→ManualOnly 链，在 en/zh 下验证 `openUrl=false`、抛异常与成功清空反馈，中文 hardcode mutation 精确 RED，production 零差异。唯一复审 APPROVED `0/0/0`，focused `11/11`、相关回归 `120/120`、Spotless、diff、guard 通过；累计 `5 files/371 touched`，低于 380，用户脏文件零混入。下一项为父 Task 5B / 子 Task 4J。

### Task 4J：Desktop Extension repository i18n 同源化

**Risk axis:** settings-i18n-extension-repo

**Platform boundary:** desktop

**Estimated scope:** 4 files, 380 lines

**Verification:** empty/list/pending、create/delete/refresh/conflict/error、website/copy同源MR与base/zh-rCN完整

**Files:** `ExtensionRepoScreen.kt`、base/`zh-rCN` strings、resource identity test。

1. RED：initial URL、required/duplicate、Success/InvalidUrl/Unavailable/InvalidMetadata/AlreadyExists/Error、fingerprint conflict、delete/refresh/website/copy 任一真实状态硬编码或 identity 分叉时失败。
2. GREEN：优先复用 fixed-main extension-repo identity；Desktop base/index URL、HTTPS/network/metadata 细分错误与 pending 后果使用准确 `desktop_*`，不改 repository 规则。
3. 运行 ExtensionRepo production wiring/rendered/navigation、Spotless/range gate。

**Replan evidence:** 真实 Screen + repository/interactor 测试覆盖 initial URL、pending、全部 create outcome、fingerprint conflict、delete/refresh/copy 后，首次 GREEN 前自然范围为 `286 additions + 37 deletions = 323 touched`；不得按净行数误报为低于 320，也不删除状态覆盖或压缩格式。所有场景共享同一 ExtensionRepo production Screen/wiring，故将本 Task 上限调整为 350 行，不拆出无独立产品风险的子 Task。

**Repair replan evidence:** 首审 `0/2/0` 发现剪贴板按钮误用 fixed-main 明确禁止用于 clipboard 的名词 `copy`，且 open/copy description 无序集合无法杀死按钮 identity 对调。唯一修复改用 `action_copy_link`、恢复原 `copy` 翻译，并通过 Compose `LocalClipboardManager` 按 repo card 按钮节点绑定 open→copy→delete、验证 `${baseUrl}/index.min.json`；自然范围预计低于 380，故不拆出独立 Task。

**Review status（已完成）：** 实现 `3649d64f9` 将 ExtensionRepo 空态、添加/等待、全部 create outcome、删除、刷新与指纹冲突接入 fixed-main 或准确 Desktop MR；真实 Screen + Get/Create/Delete/Replace/Update/repository 覆盖 initial URL、pending、success/error、new/old replace 参数与列表行为，初始硬编码页面和 replace 参数互换均精确 RED。首审 `0/2/0` 指出剪贴板按钮误用名词 `copy`，且 open/copy 无序 description 集合无法杀死按钮错接；唯一修复 `7254180b4` 改用 `action_copy_link`、恢复 `zh-rCN copy` 原义，并通过真实 RepoCard 动作顺序与受控 `LocalClipboardManager` 精确验证 `${baseUrl}/index.min.json`，open/copy swap mutation 精确 RED。唯一复审 APPROVED `0/0/0`，focused `12/12`、相关回归 `88/88`、Spotless、diff、guard 通过；累计产品/测试/资源 `4 files/357 touched`，低于 380，用户脏文件零混入。下一项为父 Task 5B / 子 Task 4K。

### Task 4K：Desktop Tracking typed message 与 formatter identity

**Risk axis:** settings-i18n-tracking-message

**Platform boundary:** desktop

**Estimated scope:** 6 files, 400 lines

**Verification:** model validation/error/feedback typed contract、external data preservation、Screen formatter en/zh production wiring

**Files:** `TrackingScreenModel.kt`、`TrackingSettingsScreen.kt` 的 state message formatter 区、base/`zh-rCN` strings、`TrackingScreenModelTest.kt`、resource identity test。

1. RED：LoadFailed、Bound/Updated/Removed/LoggedOut、SearchTitleEmpty、MangaRequired、NotBound、UnsupportedStatus/Score、NegativeChapter/ChapterOutOfRange、UnknownService、ServiceUnavailable、LoginRequired 的 variant/参数或失败不写 repository 契约错误时失败；formatter MR 对调时真实 Screen 精确失败。
2. GREEN：model 只发 typed message 或 `External(text)`；唯一 `trackingMessageText()` 负责 MR，真实 provider/profile/exception 数据原样保留；不改变验证、repository 调用与原子持久化语义。
3. 运行 ScreenModel/Identity/AutoSync/Tracking integration/navigation/smoke、Spotless/range gate；4L 不得返改 typed contract/formatter。

**Review status（已完成）：** 初始实现 `ebefc7eba` 将 Tracking state/error/feedback 改为 typed message，并由唯一 `trackingMessageText()` 映射 base/zh-rCN MR；首审 `0/2/0` 指出登录取消/登录失败/退出失败/解绑失败仍把 app-owned 英文包装为 `External`，以及 UnsupportedStatus/UnsupportedScore 只由直接 formatter 断言保护。唯一修复 `e214cee15` 新增四种 typed failure 与双语 MR，使无 message 的应用失败走 typed fallback、真实非空 provider/profile/exception 文本才走 `External`；en/zh 均通过真实 repository/registry/model → `TrackingSettingsScreen` → Manage → Update 路径验证 Status/Score，production MR 对调 mutation 同时精确 RED。唯一复审 APPROVED `0/0/0`，focused `18/18`、相关回归 `51/51`、Spotless、diff 与 21-Task guard 通过；累计 `6 files/399 touched`，验证异常类别、调用顺序、失败不写入和远端失败原子语义未回退，用户脏文件零混入。下一项为父 Task 5B / 子 Task 4L。

### Task 4L：Desktop Tracking UI/dialog i18n 同源化

**Risk axis:** settings-i18n-tracking-ui

**Platform boundary:** desktop

**Estimated scope:** 5 files, 400 lines

**Verification:** settings/manga 模式、service/auth/logout/unbind/search/bound editor 的渲染 identity 与 base/zh-rCN 完整

**Files:** `TrackingSettingsScreen.kt`（不改 4K formatter）、base/`zh-rCN` strings、resource identity test、`TrackingAutoSyncPreferenceWiringTest.kt`。

1. RED：两种顶层模式、四种 service 状态、username/password/API-key/OAuth、logout/unbind、search empty/results、Status/Score/Chapter editor 的渲染 identity/参数错误时失败；AutoSync 测试不得修改未隔离的进程 Locale。
2. GREEN：复用 fixed-main tracking/login/logout/search/status/score/track-delete/action identity；Desktop source-managed/OAuth/browser/bind/update copy 使用准确 `desktop_*`；外部 service/status/URL/unavailable reason 原样保留。
3. 不改 selectedId/confirmation、OAuth、tracker/repository wiring、按钮 enabled 或章节边界；本 Task 只关闭 UI/resource 与状态渲染，动作副作用和失败 fallback 的真实链由 4M 独立验证；运行 Identity/AutoSync/ScreenModel/Tracking integration/navigation/smoke、Spotless/range gate。

**Review status（已完成）：** 实现 `3c88a436` 将 Tracking settings/manga 两模式、service 四态、三种认证表面、logout/unbind、search 与 bound editor UI 接入 fixed-main/shared 或准确 Desktop MR；首审 `0/2/0` 指出 AutoSync 测试污染全局 Locale，以及同屏存在性断言不足以保护真实动作/fallback。完整无损修复会达到 `403 touched`，按范围门禁停止并重规划：本 Task 只关闭渲染 identity/Locale 隔离，动作副作用与三类 fallback 由 4M 关闭。修复 `3aa49af05` 删除未隔离 Locale 切换与资源枚举自证，真实 Screen 的 API-key/OAuth field 和 manga/service 参数互换均精确 RED；复审 APPROVED `0/0/0`，focused `13/13`、指定六组 `70/70`、Spotless、diff 与 21-Task guard 通过，累计 `5 files/375 touched`，4K formatter/contract 与 production/resources 在修复提交中零差异。额外全模块中的两个 `WindowPrivacyWiringTest` 失败可在其隔离单类独立复现，且本 Task 无 privacy/security/Locale 依赖差异，记录为范围外既有失败。下一项为父 Task 5B / 子 Task 4M。

### Task 4M：Desktop Tracking 动作副作用与失败 fallback identity

**Risk axis:** settings-i18n-tracking-action-wiring

**Platform boundary:** desktop

**Estimated scope:** 1 files, 170 lines

**Verification:** credentials/action side effects、search/bind/update empty-error fallback、en/zh production mutation

**Files:** `DesktopSettingsResourceIdentityTest.kt`；只复用 4L production，不修改 `TrackingSettingsScreen.kt`、4K typed contract/formatter 或资源。

1. RED：username/password/API-key 参数、Login/Cancel、Logout/Cancel、Unbind Remove/Cancel、Track/Close、Update 的 action identity/副作用错接时失败。
2. RED：search/bind/update 分别抛空 message 异常时，真实 Screen 必须在 en/zh 显示对应 fallback；恢复旧英文或交换三种 fallback MR 时精确失败。
3. GREEN：仅以真实 editable/action 节点和 repository/service side effect 证明 4L production wiring；不得引入 test-only production seam。运行 Identity 与 Tracking 相关回归、Spotless/range gate。

**Review status（已完成）：** test-only 实现 `661666c59` 仅修改 `DesktopSettingsResourceIdentityTest.kt`，挂载真实 `TrackingSettingsScreen` 并通过 editable/action semantics 验证 USERNAME_PASSWORD/API_KEY 参数、Login/Cancel、Logout/Cancel、Unbind Remove/Cancel、Track/Close、Update `TrackEdit` 以及 service/repository 副作用；三个独立场景以无 message 异常验证 search/bind/update 的 en/zh fallback。字段交换、认证参数交换、Track/Close 错接、旧英文 fallback 与三类 fallback 互换五种 production mutation 均精确 RED 并恢复；Cancel/Close 以零副作用断言锁定。独立审查 APPROVED `0/0/0`，相关回归 `71/71`、Spotless、diff、range 与 20-Task guard 通过；范围 `1 file/152 touched`，production/resources/4K contract/formatter 零差异，无 test-only production seam。下一项为父 Task 5B / 子 Task 5。

### Task 5：Desktop 设置 catalog、搜索 Screen 与入口

**Risk axis:** desktop-settings-search-entry

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** exact nine-screen prefix、Desktop-only append、shared top10、More entry/query feedback、Voyager type/Screen smoke

**Files:** `DesktopSettingsCatalog.kt`、`SettingsSearchScreen.kt`、`MoreRootScreen.kt`、base/`zh-rCN` strings、search wiring test、navigation contract、Screen smoke。

1. RED：交换九页、Desktop-only 前插、Desktop 项抢占原版 top10、catalog 未调用 shared policy或 route 类型错误时失败。
2. GREEN：前九页严格映射原版 screen IDs 到 Desktop routes；General/About 等只确定性追加。Browse/Data 分别映射 Desktop 真实扩展/备份入口。
3. 搜索初始聚焦、Enter/NumPadEnter/IME 清焦点；空/无结果有反馈。运行 UI/navigation/i18n/range gate。

**Review status（已完成）：** 初始实现 `19c962bac` 新增 Desktop catalog、真实搜索 Screen 与 More 入口；前九页严格保持 fixed-main Appearance/Library/Reader/Download/Tracking/Browse/Data/Security/Advanced，Browse/Data 映射 `ExtensionListScreen`/`BackupSettingsScreen`，Desktop-only 页面只确定性追加，production 直接调用 shared `SettingsSearchPolicy`。公开 `CanvasLayersComposeScene` 覆盖真实初始焦点、Enter/NumPadEnter/IME 清焦点、双语空态/无结果与结果 `replace`；11 类 shared/order/route/focus/feedback/入口 mutation 精确 RED。首审 `0/1/0` 只发现 catalog 测试未恢复全局 Locale；唯一修复 `58497587c` 以 `withRestoredLocale` 的 `try/finally` 覆盖正常及主动异常路径，消除顺序污染。唯一复审 APPROVED `0/0/0`，搜索/导航/实例化 `53/53`、资源/More `14/14`、shared policy `7/7`、provenance `6/6`、Spotless、diff 与 19-Task guard 通过；累计 `8 files/400 touched`，用户脏文件零混入。下一项为父 Task 5B / 子 Task 6。

### Task 6A：Desktop 标题 anchor 核心、搜索交接与 General/Appearance

**Risk axis:** desktop-settings-anchor-core

**Platform boundary:** desktop

**Estimated scope:** 7 files, 340 lines

**Verification:** result→search handoff→Screen→exact title→scroll/highlight→one-shot；duplicate first match；optional focus enhancement separate

**Files:** `DesktopSettingsAnchor.kt`、`SettingsComposables.kt`、`SettingsSearchScreen.kt`、General/Appearance screens、两份 anchor tests。

1. RED：重复消费、错误 title、route 无 anchor、滚动但不高亮均失败；缺 focus 不能冒充原版 anchor failure。
2. GREEN：搜索结果必须把 `anchorTitle` 交给统一一次性 owner/highlight 后再 replace route；额外 focus 独立标记和测试，不改变首个重复标题边界。
3. 接入 General/Appearance 并保护 grid 独有项；运行真实 Compose/navigation/range gate。

**Review status（已完成）：** 初始实现 `e7c6d9598` 新增 route-owned one-shot anchor、统一 scroll/highlight host，并让搜索结果先发布 `route::class + exact anchorTitle` 再 replace；General/Appearance 真实接线同时保护 incognito/grid 写入。owner 的错误 route claim 原子清空，host 以 direct-child offset 驱动同一 `ScrollState`，focus 不作为 anchor 成功条件；search publish、route、highlight、one-shot、重复项五类 mutation 精确 RED。首审 `0/1/0` 指出测试未杀死 exact→contains 模糊匹配；唯一修复 `6fec99d28` 在两个 exact duplicate 之前加入独立 `duplicate-prefix`，contains mutation 精确误选 prefix 并 RED。唯一复审 APPROVED `0/0/0`，focused `7/7`、Desktop 相关/provenance `80/80`、shared policy `7/7`、Spotless、diff 与 19-Task guard 通过；累计 `7 files/323 touched`，Reader/Library 与用户脏文件零差异。下一项为父 Task 5B / 子 Task 6B。

### Task 6B：Desktop 标题 anchor 的 Reader/Library 接线

**Risk axis:** desktop-settings-anchor-reader-library

**Platform boundary:** desktop

**Estimated scope:** 3 files, 180 lines

**Verification:** Reader/Library exact-title scroll/highlight/one-shot、duplicate first match、独有 preference preservation

**Files:** Reader/Library screens、复用 6A 的 anchor wiring test。

1. RED：Reader/Library route 未消费 exact title、滚动/高亮错误、重复消费或重复标题非首个命中时失败。
2. GREEN：仅把两页接入 6A 统一 owner/host，不复制 anchor 状态机或搜索策略。
3. 保护 reader mode、grid/update interval 等独有 preference 与写入行为；运行真实 Compose/navigation/range gate。

**Review status（已完成）：** 实现 `78533b61d` 仅让 Reader/Library 接入 6A 统一 `DesktopSettingsAnchorColumn`/`desktopSettingsAnchor`，未修改核心、搜索或 catalog。两页真实 search→replace→Screen 场景均验证 exact title、`ScrollState > 0`、目标与可视区域相交、唯一 highlight，以及直接重开后无 highlight/scroll 的 one-shot；Reader `PAGER`、Library `EVERY_6H` 与既有 Appearance grid 写入回归通过。断开 Reader host、断开 Library host、Library anchor 错绑 update title 三类 mutation 精确 RED 并恢复。独立审查 APPROVED `0/0/0`，focused `9/9`、Desktop 设置/navigation/smoke/provenance `106/106`、shared policy `7/7`、Spotless、diff、range 与 18-Task guard 通过；范围 `3 files/113 touched`，无状态机复制或用户脏文件。下一项为父 Task 5B / 子 Task 7。

### Task 7A：Desktop 标题 anchor 的 Download/Backup 数据页面

**Risk axis:** desktop-settings-anchor-data

**Platform boundary:** desktop

**Estimated scope:** 4 files, 240 lines

**Verification:** Download/Backup real catalog-route-anchor、preference/picker/feedback preservation

**Files:** Download/Backup Settings Screen、`DesktopSettingsCatalog.kt`、数据 anchor wiring test。

1. RED：两页只路由不落点、exact route/title/one-shot/scroll/highlight 或 Catalog identity 分叉时失败。
2. GREEN：只接 6A host，不复制 owner/search；保留 Download preference 与 Backup picker/feedback。
3. 运行两页 focused/Backup wiring/navigation/Spotless/range gate。

**Review status（已完成）：** 实现 `fd43e132d` 新增集中 `DesktopSettingsAnchorResources`，Download/Backup 的 Catalog 与页面共同消费四个 MR identity，并只接入 6A 统一 owner/host。真实 Catalog→publish/replace→Screen 场景验证 exact title、滚动、可见、唯一 highlight、one-shot、wrong route 与 unknown title；同时执行 Download preference 写入和 Backup Directory picker cancel/snackbar，既有 restore→Preview 与 typed failure 语义保持。断开 Download host、断开 Backup host、Catalog route/title 错接三类 mutation 精确 RED。独立审查 APPROVED `0/0/0`，Desktop focused/相关回归 `137/137`、shared `7/7`、Spotless、diff 与 18-Task guard 通过；范围 `4 files/176 touched`，6A core/Search/shared/DownloadQueue 与用户脏文件零差异。下一项为父 Task 5B / 子 Task 7B。

### Task 7B：Desktop 标题 anchor 的 Advanced 页面

**Risk axis:** desktop-settings-anchor-advanced

**Platform boundary:** desktop

**Estimated scope:** 3 files, 180 lines

**Verification:** Advanced real catalog-route-anchor、platform action/status feedback preservation

**Files:** Advanced Settings Screen、`DesktopSettingsCatalog.kt`、Advanced anchor wiring test。

1. RED：页面只路由不落点、exact route/title/one-shot/scroll/highlight 或 Catalog identity 分叉时失败。
2. GREEN：只接 6A host，不复制 owner/search；保留 Advanced platform action/状态反馈。
3. 运行 Advanced focused/navigation/Spotless/range gate。

**Review status（已完成）：** 实现 `b5c892c3c` 仅修改 Advanced、Catalog 和独立 wiring test；`advancedCrashLog` 让 Catalog 与页面按钮共同消费 `desktop_advanced_crash_log_open`。真实 Catalog→publish→replace→Advanced 场景验证 exact title、scroll、visible、唯一 highlight、one-shot、wrong route/unknown title，并通过真实点击覆盖 `openCrashLogFolder()` true/false 与对应 Snackbar。Catalog identity/route 分叉、host/anchor 断开、平台调用或反馈分支破坏三类 mutation 精确 RED。独立审查 APPROVED `0/0/0`，Desktop focused/Advanced/search/navigation/smoke/provenance `85/85`、shared `7/7`、Spotless、diff 与 18-Task guard 通过；范围 `3 files/178 touched`，Security、6A、7A 和用户脏文件零差异。下一项为父 Task 5B / 子 Task 7C。

### Task 7C：Desktop 标题 anchor 的 Security 页面

**Risk axis:** desktop-settings-anchor-security

**Platform boundary:** desktop

**Estimated scope:** 3 files, 190 lines

**Verification:** Security real catalog-route-anchor、supported/unsupported capability feedback preservation

**Files:** Security Settings Screen、`DesktopSettingsCatalog.kt`、Security anchor wiring test。

1. RED：页面只路由不落点、exact route/title/one-shot/scroll/highlight 或 Catalog identity 分叉时失败。
2. GREEN：只接 6A host，不复制 owner/search；保留 supported native toggle 与 unsupported telemetry/widget 的诚实反馈。
3. 运行 Security focused/wiring/navigation/Spotless/range gate。

**Review status（已完成）：** 实现 `b9c1f51a5` 仅修改 Security、Catalog 和独立 wiring test；`securitySecureScreen` 让 Catalog 与页面共同消费 `desktop_secure_screen_title`。真实 Catalog→publish→replace→Security 场景验证 exact title、scroll、visible、唯一 highlight、one-shot、wrong route/unknown title。supported fixture 只启用原生通知能力，具名真实 Switch 写入 `DesktopPreferenceStore` 支撑的 `SecurityPreferences`；telemetry/widget 保持 production Unsupported，只显示准确 copy，页面 toggle 总数严格为 2。Catalog/Page identity 分叉、host/marker 断开、supported/unsupported 分支破坏三类 mutation 精确 RED。独立审查 APPROVED `0/0/0`，Security wiring/identity/navigation/smoke/search/anchor/provenance `106/106`、shared `7/7`、Spotless、diff 与 17-Task guard 通过；范围 `3 files/178 touched`，Advanced、6A、7A 与用户脏文件零差异。下一项为父 Task 5B / 子 Task 8。

### Task 8A：Desktop 标题 anchor 的 About 页面

**Risk axis:** desktop-settings-anchor-about

**Platform boundary:** desktop

**Estimated scope:** 3 files, 220 lines

**Verification:** About real catalog-route-anchor、updater/diagnostic action preservation、Desktop-only append order

**Files:** About Screen、`DesktopSettingsCatalog.kt`、About anchor wiring test。

1. RED：About 独有项遗漏、前插九页、route/anchor identity 断开或 updater/diagnostic action 丢失时失败。
2. GREEN：只接 ScrollState anchor host，不删除 updater/诊断能力；Catalog/Page 消费同一 MR。
3. 运行 About/catalog/navigation/Screen smoke/Spotless/range gate。

**Review status（已完成）：** 实现 `6b82a2307` 让 About 与 Catalog 共同消费集中 `aboutAppData` MR，并接入 6A ScrollState host。真实 Catalog→publish→replace→About 验证 exact title、scroll、visible、唯一 highlight、one-shot、wrong route/unknown title；fixed-main 九页前缀不变，Desktop 尾部严格为 `[General, ExtensionRepo, About]`，broad query top10 不被抢占。真实临时缓存目录及 `response.bin` 通过生产按钮删除并显示成功反馈，既有 updater/版本/路径/Java/OS 诊断回归保持。Catalog/顺序、host/identity、清缓存反馈三类 mutation 精确 RED。独立审查 APPROVED `0/0/0`，About/updater/identity/navigation/smoke/search/anchor/provenance `113/113`、shared `7/7`、Spotless、diff 与 18-Task guard 通过；范围 `3 files/137 touched`，Lazy 页与用户脏文件零差异。下一项为父 Task 5B / 子 Task 8B。

### Task 8B：Desktop LazyList anchor 核心与 ExtensionRepo 页面

**Risk axis:** desktop-settings-anchor-lazy-extension

**Platform boundary:** desktop

**Estimated scope:** 5 files, 320 lines

**Verification:** shared LazyList exact-title/first/one-shot、ExtensionRepo empty/list route-anchor、repository action preservation

**Files:** `DesktopSettingsAnchor.kt` 的 LazyList adapter、ExtensionRepo Screen、`DesktopSettingsCatalog.kt`、Lazy anchor/ExtensionRepo wiring test、Extension navigation test。

1. RED：Lazy exact/first/one-shot/visible 失败、empty/list 分支遗漏、Catalog route/anchor 分叉或 repository action 丢失时失败。
2. GREEN：扩展统一 owner 为可复用 LazyList host，不复制状态机；保留 add/replace/open/copy/delete 能力。
3. 运行 ExtensionRepo/catalog/navigation/Screen smoke/Spotless/range gate。

**Review status（已完成）：** 实现 `13d31e55a` 复用 route-owned `DesktopSettingsAnchorOwner` 建立 LazyList adapter，并让 ExtensionRepo 与 Catalog 消费同一 Add/Delete MR identity。真实 Catalog→publish→replace→Screen 覆盖空列表 Add、列表首个 Delete、未预组合 index 40 的滚动/可见/唯一高亮，以及 exact/首重复项/wrong route/one-shot；真实 `DeleteExtensionRepo` 精确删除 first URL 后仅保留 second，既有 add/replace/open/copy/delete 全部保留。7 个 production mutation 均精确 RED 后恢复。独立审查 APPROVED `0/0/0`，Desktop focused `96/96`、shared `7/7`、Spotless、diff 与 17-Task guard 通过；范围严格为 `5 files/258 touched`，用户脏文件零差异。下一项为父 Task 5B / 子 Task 8C。

### Task 8C：Desktop 标题 anchor 的 Tracking 页面

**Risk axis:** desktop-settings-anchor-tracking

**Platform boundary:** desktop

**Estimated scope:** 4 files, 260 lines

**Verification:** Tracking real catalog-route-lazy-anchor、service/auth/auto-sync preservation、Desktop-only append order

**Files:** TrackingSettings Screen、`DesktopSettingsCatalog.kt`、Tracking anchor wiring test、Tracking navigation test。

1. RED：Tracking route/anchor identity、Lazy visible/highlight/one-shot 或 service/auth/auto-sync 行为断开时失败。
2. GREEN：复用 8B LazyList host，不复制 owner/search；Catalog/Page 消费同一 MR。
3. 运行 Tracking/catalog/navigation/Screen smoke/Spotless/range gate。

**Review status（已完成）：** 实现 `9e90cc5c9` 让 Tracking 复用 8B 的 route-owned LazyList host，Catalog/Page 共享 auto-sync 与 Login MR identity。真实 Catalog→publish→replace→Tracking 跨 18 个未预组合服务滚动至首个 Login，验证 exact、首重复项、visible、唯一 highlight、wrong title/route 与 one-shot；真实点击 auto-sync Switch 将 production preference 由 `true` 写为 `false`，registry/OAuth/login/logout、bind/update/unbind、typed message/fallback 均保持。四项 mutation 精确 RED 后恢复。独立审查 APPROVED `0/0/0`，Tracking/设置回归 `115/115`、shared `7/7`、Spotless、diff 与 16-Task guard 通过；范围严格为 `4 files/212 touched`，8B 核心、计划外代码和用户脏文件零差异。下一项为父 Task 5B / 子 Task 9。

### Task 9：共享主题模块、identity/default/codec 与 Android consumer

**Risk axis:** shared-theme-semantics

**Platform boundary:** shared+android

**Estimated scope:** 8 files, 400 lines

**Verification:** Compose Multiplatform module compiles both consumers、SYSTEM/default/Monet capability、unknown/deprecated fallback、canonical keys

**Files:** 新 `presentation-theme` module/build、settings include、AppTheme/ThemeMode 高相似度 move、ThemeDefaults/test、Android build与UiPreferences wiring。

1. RED：动态色 capability、未知字符串、废弃 picker 可见性、canonical key/default 漂移或 Desktop 无法依赖模块时失败。
2. GREEN：共享 module 持有 UI identity/default/codec；Android 保留 AppCompat/DynamicColors/Wallpaper adapter。
3. 不伪造显式 migration；运行 shared/Android compile与behavior、Spotless/range gate。

**Review status（已完成）：** 实现 `781b0f87a` 新增 Compose MPP `presentation-theme` Android/JVM module，将 fixed-main `AppTheme.kt` 以 R100 精确移动并共享 `ThemeMode`、canonical keys、SYSTEM/default、capability-aware MONET/DEFAULT、codec unknown fallback 与 deprecated/MONET picker 可见性。Android `UiPreferences` 真实消费共享合同，AppCompat/DynamicColors/Wallpaper adapter 留在 Android；移除 app module dependency 会令真实 Android compile RED，Desktop UI/直接依赖按 Task 11 边界未提前迁移。六类 production mutation 精确 RED 后恢复。独立审查 APPROVED `0/0/0`，shared test `7/7`、fixed-main provenance `6/6`、Android/JVM 强制编译、Spotless、diff 与 15-Task guard 通过；范围严格为 `8 files/167 touched`，用户脏文件零差异。下一项为父 Task 5B / 子 Task 10A。

### Task 10A：共享静态调色板基础与第一批

**Risk axis:** theme-palette-foundation-first

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 320 lines

**Verification:** Base + Tachiyomi/GreenApple high-similarity moves、shared Material3/UI classpath、cross-module visibility、fixed-main exact tokens、Android shared-module wiring

**Files:** `BaseColorScheme.kt`、Tachiyomi/GreenApple color-scheme 从 Android source high-similarity move 到 `presentation-theme/commonMain`；module build、shared exact test、Android wiring test。

1. RED：token 交换或 Android 仍加载旧本地复制路径时失败。
2. GREEN：先补齐共享 Material3/UI classpath 并移动 Base；保留 package/实现，采用 rename 而非重新抄写 ARGB。Base 与本批 palette 把原 `internal` 调整为最小跨模块可见 API，使 Android 在本提交即可继续直接消费；无平台 adapter 重复数据。
3. 检查 rename similarity、共享/Android 编译与真实 touched≤320。

**Replan status：** 原 Task 10A 的只读盘点确认五个 palette blob 均与 fixed-main 完全一致，但 `presentation-theme` 尚缺 Material3/UI classpath；完整最小范围必须为 5 moves + module build + shared exact test + Android wiring test，共 8 文件，超过原 7 文件上限。代码零差异时按可独立编译边界拆为新 10A/10B，原 10B–10D 顺延为 10C–10E；编号符合项目 guard 的“数字+至多一个字母”结构，不以省略依赖或测试规避门禁。

**Review status（已完成）：** 实现 `1ccf94908` 将 Base/Tachiyomi/GreenApple 以 R97/R98/R98 高相似度 move 到共享模块，只把 `internal` 调整为最小 public，并补齐 Material3/UI classpath；Android production map 真实消费共享对象，旧 app-local 路径消失。首审 `0/1/0` 指出 exact snapshot 漏掉部分显式 role 与两个 AMOLED container；唯一测试修复 `9a7a91d4a` 补齐 36-role 快照及 `surfaceContainerLowest/Low`，三类遗漏 mutation 各自精确 RED，production 零差异。唯一复审 APPROVED `0/0/0`，shared `10/10`、Android wiring `2/2`、provenance `6/6`、JVM/Android/consumer compile、Spotless、diff 与 15-Task guard 通过；累计严格为 `6 files/189 touched`。下一项为父 Task 5B / 子 Task 10B。

### Task 10B：共享静态调色板第一批收口

**Risk axis:** theme-palette-foundation-rest

**Platform boundary:** shared+android

**Estimated scope:** 4 files, 260 lines

**Verification:** Lavender/Yotsuba high-similarity moves、fixed-main exact tokens、Android shared-module wiring extension

**Files:** Lavender/Yotsuba 两个 color-scheme high-similarity move；扩展 10A shared exact test 与 Android wiring test。

1. 按 10A 的 RED/rename/consumer mutation 模式执行；交换任一 token 或恢复旧 Android 本地路径时失败。
2. 保留 light/dark 语义与最小跨模块可见性，不重新抄写或近似 ARGB。
3. 运行 shared exact、Android production wiring/compile、Spotless、rename/range gate。

**Review status（已完成）：** 实现 `b22f80a87` 将 Lavender/Yotsuba 以 R98/R98 高相似度 move 到共享模块，唯一 production 变化为 `internal object`→`object`；旧 Android 路径消失，production 各仅保留一个共享定义。shared exact 扩展为两套 light/dark 完整 36-role 快照，Android production map 以 singleton identity 与 runtime CodeSource 证明真实共享来源；缺失 shared type、旧 local origin 及两套 token mutation 均精确 RED 后恢复。独立审查 APPROVED `0/0/0`，shared `12/12`、Android wiring `2/2`、provenance `6/6`、JVM/Android/consumer compile、Spotless、diff 与 14-Task guard 通过；范围严格为 `4 files/50 touched`。下一项为父 Task 5B / 子 Task 10C。

### Task 10C：共享静态调色板第二批

**Risk axis:** theme-palette-muted

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 400 lines

**Verification:** Catppuccin/MidnightDusk/Monochrome/Nord high-similarity moves、exact tokens、Android wiring

**Files:** 四个 palette verbatim move、同一 shared exact/Android wiring tests。

1. 按 10A/10B 的 RED/rename/consumer mutation 模式执行。
2. 不合并或近似颜色；保留 light/dark 语义，并在 rename 内把本批 palette 调整为最小跨模块可见，使当前 Android 在本提交可编译消费。
3. 检查 touched≤400；超限实施前按 2+2 拆分。

**Review status（已完成）：** 实现 `9720357f0` 将 Catppuccin/MidnightDusk/Monochrome/Nord 四套 palette 以 R98 高相似度 move 到共享模块，normalized fixed-main 精确且唯一 production 变化为 `internal object`→`object`；旧 Android 定义全部消失。shared exact 对每套 light/dark 执行完整 36-role 快照，Android production map 以 singleton identity 与 runtime CodeSource 验证真实共享来源；缺失 type、旧 local origin 与四套 token mutation 各自精确 RED 后恢复。独立审查 APPROVED `0/0/0`，shared `16/16`、Android wiring `2/2`、provenance `6/6`、JVM/Android/consumer compile、Spotless、diff 与 13-Task guard 通过；范围严格为 `6 files/100 touched`。下一项为父 Task 5B / 子 Task 10D。

### Task 10D：共享静态调色板第三批

**Risk axis:** theme-palette-colorful

**Platform boundary:** shared+android

**Estimated scope:** 6 files, 400 lines

**Verification:** Strawberry/Tako/TealTurqoise/TidalWave high-similarity moves、exact tokens、Android wiring

**Files:** 四个 palette verbatim move、同一 shared exact/Android wiring tests。

1. 按 10A/10B 的 RED/rename/consumer mutation 模式执行。
2. 原版拼写/序列化 identity 保持兼容，并在 rename 内把本批 palette 调整为最小跨模块可见，使当前 Android 在本提交可编译消费。
3. 检查 touched≤400；超限实施前按 2+2 拆分。

**Review status（已完成）：** 实现 `64dfbbd34` 将 Strawberry/Tako/TealTurqoise/TidalWave 四套 palette 以 R98 高相似度 move 到共享模块，normalized fixed-main 精确且唯一 production 变化为最小 public；旧 Android 定义全部消失。`TEALTURQUOISE` enum、`theme_tealturquoise` 资源、`TealTurqoiseColorScheme` 类型及 Android map 历史 identity 均保持。shared exact 对每套 light/dark 执行完整 36-role 快照，Android singleton/runtime origin、缺失 type、旧 local origin 与四套 token mutation 均闭合。独立审查 APPROVED `0/0/0`，shared `20/20`、Android wiring `2/2`、provenance `6/6`、跨端编译、Spotless、diff 与 12-Task guard 通过；范围严格为 `6 files/100 touched`。下一项为父 Task 5B / 子 Task 10E。

### Task 10E：共享调色板 selector/AMOLED 与 Monet adapter 收口

**Risk axis:** theme-palette-selection

**Platform boundary:** shared+android

**Estimated scope:** 8 files, 400 lines

**Verification:** YinYang move、public selector、dark AMOLED、Monet Android adapter、deprecated/unknown fallback

**Files:** YinYang high-similarity move、共享 public selector/test、Android `MonetColorScheme.kt`/`TachiyomiTheme.kt`、Android wiring test、module dependency cleanup。

1. RED：selector 错配、light AMOLED 生效、dark AMOLED 未置黑、Monet 被搬进 common 或 deprecated 未回退时失败。
2. GREEN：YinYang 调整为最小跨模块可见；在已可独立消费的 public palettes/Base 上建立最终 public selector，Android/Desktop 后续只消费 selector。Monet 只留 Android adapter，Desktop capability=false 不展示。
3. 运行全部 palette/Android adapter、Spotless/touched gate。

**Review status（已完成）：** 实现 `edab97e54` 将 YinYang 以 R98 move 到共享模块，建立覆盖全部 static theme、deprecated/unknown fallback、AMOLED 与可选 Monet adapter 的 public selector；Android `TachiyomiTheme` 删除本地 map，真实调用共享 selector，仅在 MONET 分支构造 Android adapter，Wallpaper/material-kolor 仍留平台侧。首审 `0/1/0` 发现 MONET 无 adapter 回退静态 Tachiyomi 时错误保留动态 containers；唯一修复 `3f9bdefdb` 改为依据实际选中的非空 Monet adapter，补充 dark AMOLED fallback 的 `0C/13/1B` production RED。唯一复审 APPROVED `0/0/0`，shared `26/26`、Android wiring `3/3`、provenance `6/6`、双端/consumer compile、Spotless、diff 与 11-Task guard 通过；累计严格为 `6 files/347 touched`，Task 11/Desktop 零 repair 差异。下一项为父 Task 5B / 子 Task 11。

### Task 11：Desktop 主题 adapter、外观 UI 与迁移

**Risk axis:** desktop-theme-consumer

**Platform boundary:** shared+desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** shared ThemeMode/AppTheme/static ColorScheme、legacy theme_mode migration、SYSTEM、grid preservation、Monet honesty

**Files:** Desktop preferences/migration/theme/Appearance/catalog、preferences tests、migration test、Appearance wiring test。

1. RED：Desktop 重复 enum、legacy 丢失、主题/AMOLED不改变真实 scheme、grid 删除、Monet虚假可选时失败。
2. GREEN：Desktop 直接依赖 `presentation-theme` 静态实现；system-dark 留 adapter，Monet capability=false。
3. 回归 search anchor/i18n；运行 focused/Spotless/range gate。

**Review status（已完成）：** 实现 `32f35d1ef` 让 Desktop 直接依赖 `presentation-theme`，本地 `ThemeMode` 以 shared typealias 兼容旧 import，迁移到 canonical theme/appTheme/AMOLED key/default/codec，并从 legacy `theme_mode` 无损迁移且 canonical 新值优先；grid key/default/UI 保持。`DesktopTheme` 订阅真实偏好并调用共享 selector，SYSTEM 仅由桌面 adapter 决定；Appearance 提供可用 static theme、AMOLED 与 grid 控件，MONET/deprecated 诚实隐藏，Catalog/Page 使用同一 MR 并支持真实 route-anchor。10 类 production mutation 精确 RED 后恢复。独立审查 APPROVED `0/0/0`，Desktop focused `254/254`、shared `26/26`、JVM/Android/Desktop compile、Spotless、diff 与 10-Task guard 通过；范围严格为 `8 files/280 touched`，Task 12/OpenSpec/用户脏文件零差异。下一项为父 Task 5B / 子 Task 12。

### Task 12：共享许可证 notice 与详情选择契约

**Risk axis:** license-notice-contract

**Platform boundary:** shared

**Estimated scope:** 3 files, 260 lines

**Verification:** deterministic order、first-license、empty website/license、malformed metadata result

**Files:** common `DependencyNotice.kt`、`LicenseNoticePolicy.kt`、common test。

1. RED：无 license 被伪造、第二项覆盖第一、空 website 仍有 action、排序不稳定时失败。
2. GREEN：纯不可变 model/selection policy，不引入 Android raw/HTML 或 Desktop file/URI。
3. 运行 shared/Spotless/range gate。

**Review status（已完成）：** 实现 `40e68eb89` 在 shared domain 建立不可变 dependency notice metadata/model、显式 Success/Failure 与纯 `LicenseNoticePolicy`。行为与 fixed-main `licenses.firstOrNull()` 对齐：首项原值保留（含空字符串）、无 license 为 null；blank website 不产生 action，非 blank 原值保留；empty metadata 成功为空，malformed/blank name 显式失败。排序使用 locale-invariant lowercase 加原名 tie-break，反转输入与大小写同名仍确定。五类 production mutation 精确 RED 后恢复。独立审查 APPROVED `0/0/0`，common test `9/9`、domain JVM/Android compile、Spotless、diff 与 9-Task guard 通过；范围严格为 `3 files/205 touched`，Task 13+/平台 IO/UI 零差异。下一项为父 Task 5B / 子 Task 13。

### Task 13：Desktop 许可证元数据构建生成

**Risk axis:** desktop-license-generation

**Platform boundary:** tooling

**Estimated scope:** 6 files, 400 lines

**Verification:** real resolved configuration/POM or AboutLibraries pipeline→deterministic packaged metadata、runtime resource presence

**Files:** `app-desktop/build.gradle.kts`、buildSrc plugin/task与测试、generated-resource integration test、build wiring fixture。

1. RED：手写 JSON 能过但真实 resolved dependency 未进入输出、顺序不稳定或 packaged resource 缺失时失败。
2. GREEN：复用仓库已解析依赖/POM/AboutLibraries metadata，生成确定性 Desktop resource；不复制 Android raw。
3. 运行 buildSrc functional/generated resource/Spotless/range gate。

**Review status（已完成）：** 实现 `56b986d75` 复用 AboutLibraries 13.2.1 与真实 Desktop resolved JVM configuration/POM 生成 `META-INF/mihon/dependencies.json`；production buildSrc 不携带插件实现，TestKit 以隔离 classpath 验证。生成目录接入 `jvmMain` resources，`jvmProcessResources` 依赖 export task，classpath integration 从打包路径读取。实际 generated/processed 资源均为 165,885 bytes、SHA-256 相同，含 192 项并按 uniqueId 排序，真实 coroutines/okio 存在；反序依赖声明逐字节一致，malformed POM 有具体诊断。删除真实输入、断 export/source-set/processResources/classpath wiring 均精确 RED 后恢复。独立审查 APPROVED `0/0/0`，buildSrc functional `2/2`、Desktop integration `1/1`、offline export/resource/compile、Spotless、diff 与 8-Task guard 通过；范围严格为 `5 files/222 touched`，无手写 JSON/Android raw/Task 14+ 差异。下一项为父 Task 5B / 子 Task 14。

### Task 14：Desktop 许可证 provider 与 DI identity

**Risk axis:** desktop-license-runtime-wiring

**Platform boundary:** desktop

**Estimated scope:** 6 files, 360 lines

**Verification:** generated resource→provider→shared policy→DesktopAppModule→DesktopUiDependencies same identity、malformed/absent structured failure

**Files:** provider及test、`DesktopAppModule.kt`、`DesktopUiDependencies.kt`、`DesktopDiWiringTest.kt`、runtime resource integration test。

1. RED：provider 读 fixture 而非 Task13 output、DI 重建第二实例或 malformed 静默空列表时失败。
2. GREEN：真实 generated resource 只解析一次并以同一 provider进入 UI dependencies；错误结构化反馈。
3. 运行 provider/DI identity/integration、Spotless/range gate。

**Review status（已完成）：** 实现 `255fce295` 新增固定 classpath/lazy `DependencyNoticeProvider`，严格解析 Task 13 的 libraries/root licenses 引用，将首项解析为真实 content 后交给 Task 12 policy；缺资源、malformed/schema/blank name 显式 Failure，首引用无 content 不回退第二项。`DesktopAppModule` 注册单例，`DesktopUiDependencies.fromInjekt()` 暴露同一实例。真实 integration 验证 192 项、确定性排序、coroutines Apache 正文。首审 `0/1/0` 指出测试只断言非空可能漏失多数条目；唯一修复 `2f537aaea` 锁定精确 192，截断到仅 coroutines 的 mutation 精确 RED，production 零差异。唯一复审 APPROVED `0/0/0`，provider/resource/DI `22/22`、offline Desktop compile、Spotless、diff 与 7-Task guard 通过；累计严格为 `6 files/248 touched`。下一项为父 Task 5B / 子 Task 15。

### Task 15：Desktop 许可证列表/详情与 About wiring

**Risk axis:** desktop-license-ui

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** About→list→detail、real injected provider、website action、empty license、navigation/resource failure feedback

**Files:** 两个 license Screen、About、base/`zh-rCN` strings、UI wiring test、navigation contract、Screen smoke。

1. RED：About 不消费 injected provider、详情选错 license、空 website 可点或 resource 错误无反馈时失败。
2. GREEN：Voyager Screen + Desktop URI adapter；保留 updater/路径/cache/extension/环境诊断。
3. 运行 generated resource→DI→UI、navigation/i18n/Spotless/range gate。

**Review status（已完成）：** 实现 `b6a649de9` 新增 Voyager `LicenseListScreen`/`LicenseDetailScreen`，About 通过真实注入 provider 进入列表和详情。production UI 链读取 Task 13/14 的 192 项稳定结果，点击真实 coroutines 展示 Task 12 首个 Apache 正文；nonblank website 原值交给 `LocalUriHandler`，blank website 无 action，null/blank license 与 provider Failure 均有 base/zh-rCN 明确反馈。列表使用 LazyColumn、详情可滚动；About 仅增加入口，原 updater 下载/安装/manual fallback、版本、网站、路径/cache/extension、Java/OS 与清缓存保持。五类 UI/wiring mutation 精确 RED 后恢复。独立审查 APPROVED `0/0/0`，focused `92/92`、Desktop compile、Spotless、diff 与 6-Task guard 通过；范围严格为 `8 files/298 touched`，Task 16+/计划/OpenSpec/用户脏文件零差异。下一项为父 Task 5B / 子 Task 16。

### Task 16：Desktop 设置 accessibility primitives 与入口页面

**Risk axis:** settings-accessibility-primitives

**Platform boundary:** desktop

**Estimated scope:** 8 files, 400 lines

**Verification:** fixed-main Enter+NumPadEnter KeyDown exactly once、Desktop Space role enhancement、single action、Role/state/disabled、focus order

**Files:** `SettingsComposables.kt`、More/Search/Appearance/General screens、accessibility contract/keyboard tests、`zh-rCN` completeness test。

1. RED：漏 NumPadEnter、KeyUp 再触发、整行/子控件双触发、无 role/state/disabled、搜索不获焦时失败。
2. GREEN：Enter/NumPadEnter 严格标为 fixed-main；Space 仅对支持的 Desktop Button/Checkbox role 增强。装饰图标不制造重复语义。
3. 用真实 Compose semantics/key/focus 行为测试，不扫描源码；回归 anchor 行为不要求 focus。

**Review status（已完成）：** 实现 `38ffa700ce` 完成 Desktop 设置 accessibility primitives 与 More/Search/Appearance/General 入口接线：Radio/Switch 整行仅保留一个 semantics action，子控件清除重复语义；Button/RadioButton/Switch role、Selected/ToggleableState/stateDescription/Disabled、装饰图标与搜索自动聚焦/Tab 顺序均由真实 Compose 场景保护。Enter/NumPadEnter 仅 KeyDown exact once，KeyUp 不触发；Space 只增强支持 action 的 role，且不污染搜索 TextField。六类 mutation 均精确 RED 后恢复，anchor identity/one-shot/scroll/highlight 保持且不依赖 focus。独立审查 APPROVED `0/0/0`，focused `31/31`、Desktop compile、Spotless、diff 与 5-Task guard 通过；范围严格为 `7 files/395 touched`，Task 17+/计划/OpenSpec/用户脏文件零差异。下一项为父 Task 5B / 子 Task 17。

### Task 17：Desktop 设置 accessibility 内容页面第一批

**Risk axis:** settings-accessibility-content-a

**Platform boundary:** desktop

**Estimated scope:** 6 files, 380 lines

**Verification:** Reader/Library/Download/Backup keyboard reachability、roles/state feedback、anchor focus enhancement

**Files:** 四个 Screen、content accessibility test、content keyboard test。

1. RED：任一 control 无唯一语义 action/状态/键盘路径或 focus enhancement 改变原版 anchor identity时失败。
2. GREEN：复用 Task16 primitives，不重写业务；所有 labels 来自 Task4B同一 MR。
3. 运行四页 UI/anchor/semantics/Spotless/range gate。

**Review status（已完成）：** 实现 `3fd0707e12` 与收尾修复 `c4d34a5ae` 完成 Reader/Library/Download/Backup accessibility 内容页第一批。Library 隐藏缺章与分类排除复选项改为整行唯一 Role.Checkbox action，子控件清除重复语义且原 preference 写入保持；Backup 创建/恢复按钮复用 `DesktopSettingsButton`，preview KeyDown 消费 Enter/NumPadEnter/Space 以避免 Material 默认双触发，KeyUp 与 disabled 均不执行。四页真实 rendered semantics、RequestFocus 与 anchor exact/首重复项/scroll/highlight/one-shot 均受保护，picker/snackbar/restore/autobackup、Download 与 Reader 独有行为未改。Library/Backup 断 helper mutation 均精确 RED 后恢复。独立审查 APPROVED `0/0/0`，focused `106/106`、Desktop compile、Spotless、diff 与 4-Task guard 通过；累计严格为 `4 files/360 touched`，Task 18+/计划/OpenSpec/用户脏文件零差异。下一项为父 Task 5B / 子 Task 18A。

### Task 18A：Desktop Security/Advanced accessibility

**Risk axis:** settings-accessibility-security-advanced

**Platform boundary:** desktop

**Estimated scope:** 5 files, 390 lines

**Verification:** Security/Advanced keyboard/semantics、credential/privacy/challenge identity、危险清理确认/取消与 capability feedback

**Files:** `SecuritySettingsScreen.kt`、`AdvancedSettingsScreen.kt`、security/advanced accessibility 与 keyboard tests、必要的 shared settings test helper。

1. RED：锁定/延迟/secure mode 无唯一 role/state、unsupported 被读成成功开关、密码/Cookie 身份字段暴露错误，或危险清理确认/取消无实体键路径时失败。
2. GREEN：复用 Task16 primitives 与 Button helper；保留 credential/window privacy/challenge、缓存/Cookie 清理的真实平台反馈，labels 来自 Task4G/4H 同一 MR。
3. 运行 Security/Advanced production wiring、anchor、semantics、keyboard、Spotless 与 range gate。

**Review status（已完成）：** 实现 `0710e3a4f` 完成 Security/Advanced accessibility：应用锁整行唯一 Role.Switch action，delay/secure mode 具备 selected/state/disabled；三类 passphrase 保持 Password，`cf_clearance` 值标记敏感而 domain/solver URL 不误标；Cookie/缓存危险对话框按钮复用 Desktop 键盘 helper，unsupported privacy 仍是无 toggle 的诚实反馈。三类 mutation 精确 RED 后恢复。首审 `0/1/0` 发现相关 `WindowPrivacyWiringTest` 在默认 zh-CN 下硬编码英文、6 项中 2 项失败；唯一测试隔离修复 `435383d5cf` 以 PER_METHOD 实例保存/设置 Locale.US，并由既有 `@AfterEach` 恢复。唯一复审 APPROVED `0/0/0`：默认 zh-CN、无语言参数的 WindowPrivacy `6/6`、联合 10 类 `113/113`、Desktop compile、Spotless、diff 与 5-Task guard 通过；累计严格为 `5 files/389 touched`，Task 18B+/计划/OpenSpec/用户脏文件零差异。下一项为父 Task 5B / 子 Task 18B。

### Task 18B：Desktop About/ExtensionRepo accessibility

**Risk axis:** settings-accessibility-about-extension

**Platform boundary:** desktop

**Estimated scope:** 3 files, 400 lines

**Verification:** About/ExtensionRepo keyboard/semantics、缓存与仓库危险操作确认/取消、updater/repository feedback、LazyList anchor

**Files:** `AboutScreen.kt`、`ExtensionRepoScreen.kt`、about/extension accessibility test、about/extension keyboard test。

**Range adjustment（实现前 RED 后）：** 真实 Compose fixture 与两页最小 production 接线合计 `3 files/361 touched`；页面边界、风险轴与行为未扩张。为避免用格式压缩掩盖范围，也避免把共享 Button/dialog 上下文重复拆载，预算先校正为 `3 files/380 touched`。首审发现测试真实调用 `Desktop.browse`，且 refresh/FAB add/add cancel/conflict cancel 缺具体物理键证据；合并修复草案实测 `3 files/439 touched`，在 GREEN 前按硬门禁停止。18B 只关闭正式 URL opener adapter、open exact-once 与原 361 行合同；其余具体物理键路径拆为 18C，禁止压缩证据。

1. RED：更新/许可/缓存及仓库 add/replace/delete 无唯一键盘路径，危险操作不能确认/取消，或反馈/按钮 identity 退化时失败。
2. GREEN：复用 Task16 primitives/Button helper，保留 updater、诊断、许可证、repository/interactor 与平台反馈；labels 来自 Task4I/4J 同一 MR。
3. 运行 About/ExtensionRepo production wiring、URL opener 隔离、LazyList anchor、semantics、keyboard、Spotless 与 range gate；refresh/FAB add/add cancel/conflict cancel 的具体物理键矩阵由 18C 独立关闭。

**Review status（已完成）：** 实现 `b0b223153` 完成 About/ExtensionRepo 基础 accessibility：About updater/license/cache 与仓库 add/replace/delete 等 Material 控件复用 Desktop 键盘 helper，缓存仍立即执行并反馈，原 updater/诊断/许可证/repository/interactor/LazyList anchor 保持。首审 `0/2/0` 发现真实测试通过 `Desktop.browse` 打开 `repo.example`，且 refresh/FAB add/add cancel/conflict cancel 缺具体物理键证据；合并修复草案 `439 touched` 在 GREEN 前停止并重规划，后者拆为 18C。唯一修复 `a65abec2a` 新增正式 `ExtensionRepoUrlOpener` CompositionLocal：production 默认仍打开浏览器，测试注入 recording opener 并使用 `.invalid`，open 的初始 KeyUp=0、KeyDown=1、最终 KeyUp 仍=1，双调用 mutation 精确 RED。唯一复审 APPROVED `0/0/0`，安全相关 `35/35`、ArchitectureGuard `4/4`、Desktop compile、Spotless、diff 与 5-Task guard 通过，复审期间系统浏览器副作用为 0；累计严格为 `3 files/390 touched`。下一项为父 Task 5B / 子 Task 18C。

### Task 18C：Desktop ExtensionRepo physical-key coverage

**Risk axis:** settings-accessibility-extension-keys

**Platform boundary:** desktop

**Estimated scope:** 2 files, 200 lines

**Verification:** ExtensionRepo refresh/FAB add/add cancel/conflict cancel 的 production physical-key exact-once、disabled safety、repository feedback 与 LazyList anchor

**Files:** `ExtensionRepoScreen.kt`、about/extension accessibility test。

1. RED：refresh、FAB add、add cancel 或 conflict cancel 任一真实控件在 Enter/NumPadEnter/Space 的 KeyDown 未恰好触发一次、KeyUp 重复触发或 disabled 产生副作用时失败。
2. GREEN：复用 18B 的正式 URL opener 与 Task16/17 Button helper，不新增业务状态机；保留 create/replace/delete/open/copy 与 repository feedback。
3. 运行 ExtensionRepo production wiring、resource identity、LazyList anchor、semantics、keyboard、Spotless 与 range gate。

**Review status（已完成）：** test-only 实现 `6dd0174ce` 补齐 refresh/FAB add/create cancel/conflict cancel 的真实 full-Screen 路径，唯一修复 `186f7c355` 抽取 Screen 真实消费的 production FAB/Create/Conflict composable，并以非幂等 counter 锁定 Enter/Space/NumPadEnter 的 `0→1→1`；mutation 双调用精确 RED，浏览器副作用为 0。首审 `0/1/0` 发现幂等 dialog 状态不能证明 exact-once；唯一复审再次 `0/1/0`，确认 direct-control 合同有效，但组合回归 `44` 项中 full-Screen conflict 用例因 `scope.launch` 尚未调度而 `coVerify` 观察到 0 次，隔离类 `5/5`。按门禁未做第二修复，新增 18D 关闭该验收竞态；18D 独立审查 APPROVED `0/0/0` 后确认阻塞解除，18C/18D 同时关闭。

### Task 18D：Desktop ExtensionRepo async test stabilization

**Risk axis:** settings-extension-async-test-stability

**Platform boundary:** verification

**Estimated scope:** 1 files, 120 lines

**Verification:** ExtensionRepo production coroutine/interactor/dialog 的可观察等待、组合回归稳定性、无固定 render/yield 次数依赖

**Files:** about/extension accessibility test。

1. RED：在正常组合负载下，create/replace/update 尚未进入 interactor 时立即断言会稳定失败，并保留 18C 已复现的 `44` 项中 `1` 项失败证据。
2. GREEN：等待可观察的 interactor 调用或 dialog 状态达到预期后再断言 exact count；不使用 sleep、不增加 production seam、不改变业务状态机。
3. 连续运行 full-Screen 类与组合回归，验证无竞态；通过独立审查后同时勾选 18C/18D。

**Review status（已完成）：** test-only 实现 `cd28ffab7` 以 `withTimeout(5s)` 有界等待真实 create/replace counter 与 conflict dialog action 后再 exact verify；只捕获 `TimeoutCancellationException`，超时 `AssertionError` 保留 cause 并输出 description/calls/labels，无 sleep、固定 render 次数、无限等待或 production seam。移除 counter mutation 在 5 秒内以 `createCalls=0` 精确 RED 后恢复。独立审查 APPROVED `0/0/0`：full-Screen 无缓存两轮均 `5/5`，精确 8 类组合无缓存两轮均 `44/44`，四轮均使用 `--rerun-tasks --no-build-cache`；范围 `1 file/59 touched`，系统浏览器副作用为 0。18C 的唯一剩余竞态阻塞已关闭，18C/18D 同时勾选。下一项为父 Task 5B / 子 Task 18E。

### Task 18E：Desktop Tracking accessibility

**Risk axis:** settings-accessibility-tracking

**Platform boundary:** desktop

**Estimated scope:** 2 files, 220 lines

**Verification:** Tracking service single-action semantics、source-managed/unsupported feedback、credential 字段 identity 与 LazyList anchor

**Files:** `TrackingSettingsScreen.kt`、tracking accessibility test。

1. RED：服务行/trailing action 双触发、source-managed/unsupported 出现无效成功 action，或 username/password/API key 身份语义错误时失败。
2. GREEN：整行不再复制 trailing action，source-managed/unsupported 诚实禁用；password/API key 复用正式敏感字段，保留 registry/auth/model、typed-message、同步与服务独有行为。
3. 运行 Tracking production wiring、LazyList anchor、semantics、identity、Spotless 与 range gate。

**Review status（已由 18F 关闭）：** 实现 `3c8b5164d0` 完成 Tracking 服务行整行单 action、source-managed/unavailable 诚实禁用及 username/password/API key identity；首审 `0/1/0` 发现真实 Screen 测试未锁定 action 的 `Role.Button` 与服务名/trailing 文案同属 clickable 子树。唯一修复 `87c674fab3` 在累计硬上限 `2 files/220 touched` 内补齐唯一 action、`Role.Button` 与未登录 `Login` 子树合同，精确 Role mutation RED，focused `1/1`、相关回归 `46/46`、compile/Spotless 通过且外部副作用为 0；唯一复审仍以 `0/1/0` 拒绝，因为设置页已登录 `Logout` 与漫画页已登录 `Manage` 两种 production 状态尚无同类子树证据。按门禁停止第二修复并新增 test-only 18F；18F 已补齐三状态并通过独立审查，因此 18E/18F 同时关闭。

### Task 18F：Desktop Tracking service-action state matrix

**Risk axis:** settings-accessibility-tracking-actions

**Platform boundary:** desktop

**Estimated scope:** 1 files, 120 lines

**Verification:** Tracking Login/Logout/Manage 三种真实服务状态的唯一 Button action、服务名与 trailing 文案同一 clickable 子树

**Files:** tracking accessibility test。

1. RED：任一 Login/Logout/Manage trailing 文案脱离整行 clickable 子树、Role 不为 Button 或出现重复 OnClick 时失败。
2. GREEN：仅扩展真实 `TrackingSettingsScreen` 状态 fixture 与可复用断言，不增加 production seam，不点击认证动作，不触发 URL/OAuth/浏览器/网络。
3. 运行 focused 三状态 semantics、Tracking production wiring、compile、Spotless 与 range gate；通过独立审查后同时勾选 18E/18F。

**Review status（已完成）：** test-only 实现 `d893a906f7` 在真实 `TrackingSettingsScreen` 覆盖未登录设置页 `Login`、已登录设置页 `Logout`、已登录漫画页 `Manage`；每态均从全语义树锁定唯一含服务名的 `OnClick`，验证 `Role.Button`、子树单一 OnClick 及服务名/trailing 文案同属 clickable 子树。Manage→Logout mutation 精确 RED，恢复后独立安全矩阵 `15/15`、compile/Spotless、diff/range 通过，严格范围 `1 file/49 touched`，production 与外部副作用均为 0。独立审查 APPROVED `0/0/0`；18E/18F 同时关闭。下一项为父 Task 5B / 子 Task 18G。

### Task 18G：Desktop Tracking login dialog keyboard accessibility

**Risk axis:** settings-accessibility-tracking-login

**Platform boundary:** desktop

**Estimated scope:** 2 files, 280 lines

**Verification:** Tracking login confirm/cancel 的 production physical-key exact-once、disabled safety 与认证副作用

**Files:** `TrackingSettingsScreen.kt`、tracking keyboard/dialog test。

1. RED：login 的 confirm/cancel 任一真实控件在 Enter/NumPadEnter/Space 的 KeyDown 未恰好触发一次、KeyUp 重复触发，或 disabled 产生认证副作用时失败。
2. GREEN：复用 Task16/17 Button helper，不复制认证状态机；保留 registry/auth/model、typed-message、service-specific 参数与失败反馈。
3. 运行 Tracking auth/action production wiring、dialog semantics、keyboard、i18n、Spotless 与 range gate。

**Scope status（实现前已重规划）：** 原合并 18G 的未跟踪测试草稿已为 301 行，且仅覆盖 logout/unbind，尚缺 login 与 disabled safety；production 正式 Button/TextButton helper 接线还会继续增加 touched，完整实现预计再需 80–120 行。代码零修改、零 Gradle 时确认无法满足原 `2 files/280 touched`，拒绝压缩 harness 或省略真实路径；本 Task 仅关闭正式 helper、login confirm/cancel 与 disabled safety，新增 18H 关闭 logout/unbind。

**Review status（已完成）：** 实现 `a9f5d44155` 将正式 `LoginDialog` confirm/cancel 接入既有 Desktop Button helper，并以真实 Screen 覆盖三键 confirm、cancel 结果与无效输入；首审 `0/2/0` 指出 USERNAME_PASSWORD 单字段/blank 边界及幂等 dismiss 不能证明 exact-once。唯一修复 `97632068f9` 仅将 `LoginDialog` 从 private 调整为 internal 以直接渲染 production 组件，补齐全空、单字段、双方 blank、API key 空/blank 的 Disabled/auth0 矩阵，以及三键非幂等 dismiss `0→1→1`，同时保留完整 Screen→Dialog cancel wiring。双 dismiss mutation 精确 RED；独立安全回归 `22/22`、compile/Spotless 通过，累计 `2 files/279 touched`，URL/OAuth/浏览器/网络副作用为 0。唯一复审 APPROVED `0/0/0`。下一项为父 Task 5B / 子 Task 18H。

### Task 18H：Desktop Tracking logout/unbind dialog keyboard accessibility

**Risk axis:** settings-accessibility-tracking-confirmations

**Platform boundary:** desktop

**Estimated scope:** 1 files, 180 lines

**Verification:** Tracking logout/unbind confirm/cancel 的 production physical-key exact-once 与真实副作用边界

**Files:** `TrackingSettingsScreen.kt`、tracking keyboard/dialog test。

1. RED：logout/unbind 的 confirm/cancel 任一真实控件在 Enter/NumPadEnter/Space 的 KeyDown 未恰好触发一次、KeyUp 重复触发，或 cancel 产生认证/仓库副作用时失败。
2. GREEN：复用 18G 的 production helper 与测试 harness，只扩展真实 `TrackingSettingsScreen` 状态；保留 registry/auth/model、typed-message、service-specific 参数与失败反馈。
3. 运行 focused confirmation keyboard、Tracking auth/action production wiring、compile、Spotless 与 range gate。

**Review status（已完成）：** 实现 `4ce8e2029a` 抽取正式 `TrackingConfirmationDialog` 并由 full Screen 调用，confirm/cancel 统一复用 Desktop TextButton helper；直接 production 组件覆盖 Logout/Unbind 两标签、三键非幂等 `0→1→1`，full Screen 验证 logout 仅命中 tracker `71`、unbind 精确删除 `(mangaId=42, trackerId=81)`、cancel 无副作用及 typed success/removed feedback。首审 `0/1/0` 指出 full-Screen confirm 用 `matches.last()` 依赖语义树顺序；唯一修复 `4d8ffd92b6` 以真实 logout consequence / delete text 加目标 label 锚定结构最小 dialog 子树，并在子树内 `.single()` 定位 action，wrong-anchor mutation 精确 RED。唯一复审 APPROVED `0/0/0`，focused/安全/Architecture Guard 共 `25/25`、compile/Spotless 通过，累计 `2 files/179 touched`，外部副作用为 0。下一项为父 Task 5B / 子 Task 19。

### Task 19：IDs 88/90/91/94 exact parity evidence

**Risk axis:** settings-parity-evidence

**Platform boundary:** verification

**Estimated scope:** 3 files, 320 lines

**Verification:** exact fixed-main/shared/current/adapter/protection sets、cross-ID/path/method mutation、Desktop unique capability preservation

**Files:** parity manifest、fixed-main inventory、DesktopProductCapabilityContractTest。

1. RED：删除/交换任一 ID 的 fixed-main symbol、shared/current consumer或行为方法时失败。
2. GREEN：真实 capability完成后才更新状态；ID88不虚构页面，ID94不以generator自测冒充UI。
3. 运行 exact contract、绑定 behavior smoke、JSON/Spotless/range gate。

**Review status（已完成）：** 实现 `91e584903e` 将 17 条 fixed-main path/symbol/blob 按 capability ownership 绑定到 IDs 88/90/91/94，并锁定 exact shared/current Android/Desktop adapter/protection 方法集合；跨 ID symbol/authority/method/protection、inventory path/ownership/blob mutation 均精确失败。ID88 仅为 `CHARACTERIZED` 并机器可见记录无 commonMain primitive/跨端 shared consumer 的剩余债务；90/91/94 为 `VERIFIED`，91 保留 Desktop grid，94 使用真实 production DI 执行 About→LicenseList→LicenseDetail 而非 generator 自测，updater/诊断未降级。独立审查 APPROVED `0/0/0`：17/17 blob、严格 JSON、exact/full contract、四 ID behavior smoke `54/54`、Android consumer `5/5`、compile/Spotless 均通过；范围 `3 files/269 touched`，外部副作用为 0。下一项为父 Task 5B / 子 Task 20A。

### Task 20A：共享许可证首项规则与 Desktop production wiring

**Risk axis:** license-shared-desktop-wiring

**Platform boundary:** shared+desktop

**Estimated scope:** 3 files, 100 lines

**Verification:** shared sole first-item/blank normalization、Desktop full ordered candidate mapping、two-license production-chain mutation

**Files:** `LicenseNoticePolicy.kt`、`LicenseNoticePolicyTest.kt`、`DesktopDependencyNoticeProvider.kt`；复用既有 `DesktopDependencyNoticeProviderTest.kt` 两许可证场景，不为凑文件数复制测试。

1. RED：共享首项 blank 归一化测试先失败；Desktop provider 在 shared selector 临时改为 `lastOrNull` 或 first-nonblank 时，既有 First/Second 与 blank-first 场景必须精确失败。
2. GREEN：shared 是唯一首项选择与 blank→`null` 归一化位置；Desktop adapter 只按原 metadata 顺序映射完整候选，缺失/空内容保留为空占位，不预裁剪、不回退后项。
3. 运行 shared 与 Desktop provider focused、compile、Spotless、diff/range/guard；不修改 manifest，待 Android consumer 完成后统一更新 evidence。

**Review status（已完成）：** 实现 `26e52ff47a` 将首项选择与 blank→`null` 唯一集中到 shared `selectLicense`，Desktop provider 只按 metadata 顺序映射完整候选并以空字符串保留缺 ID/缺 content/blank 首项位置。初始 blank-first RED `1/1`、临时 `lastOrNull` mutation 使 Desktop 两项 production-chain 测试 `2/2` 精确失败；恢复后强制实际执行 shared `9/9`、Desktop provider `5/5`，main/test compile、root Spotless、diff/range/guard 均通过，范围 `3 files/19 touched`。与 20B 合并执行的唯一修复复审 APPROVED `0/0/0`，确认 shared 是唯一首项/blank 规则且 Desktop production consumer 未预裁剪或回退。

### Task 20B：Android 许可证 shared consumer 与 parity evidence

**Risk axis:** android-license-shared-consumer

**Platform boundary:** android

**Estimated scope:** 4 files, 140 lines

**Verification:** real AboutLibraries candidate order→shared selector→Android detail content、blank-first no fall-through、ID94 exact protection

**Files:** `OpenSourceLicensesScreen.kt`、新建同包 Android behavior test、`parity-manifest.json`、`DesktopProductCapabilityContractTest.kt`。

1. RED：使用真实 AboutLibraries `License` 候选执行 production adapter；交换 shared selector 为末项或 first-nonblank 时首项与 blank-first 场景必须失败。
2. GREEN：Android 只按原迭代顺序映射完整 HTML 候选并调用 shared selector，最终 UI 边界才 `.orEmpty()`；不得保留本地 `firstOrNull` 业务规则。
3. 将 Android production behavior 方法纳入 ID94 exact protection，运行 Android focused、ID94 behavior/exact contract、compile、Spotless、diff/range/guard；20A/20B 完成后由 whole-change reviewer 合并执行一次唯一修复复审。

**Review status（已完成）：** 实现 `ba47a9a4c` 让 Android `OpenSourceLicensesScreen` 将真实 AboutLibraries `License` 的全部 HTML 候选按原顺序交给 shared selector，只有详情 UI 参数在调用边界 `.orEmpty()`；新增 Android production adapter 行为测试，并把两项方法精确绑定到 ID94 protection/evidence。新增测试在 adapter 缺失时编译阶段 RED，临时 `lastOrNull` mutation 使 Android `2/2` 精确失败；恢复后 Android `2/2`、ID94 shared/Desktop 与 exact contract、compile、Spotless、diff/range/guard 全绿，范围 `4 files/105 touched`。20A/20B 唯一合并修复复审 APPROVED `0/0/0`，复审重跑 shared `9/9`、Android `2/2`、Desktop provider/contract `39/39`，合计 `50/50`；确认初审 I1 完全关闭，ID88 与 fixed-main authority 未漂移，外部副作用为 0。

### Task 20C：Windows updater 测试 helper 启动边界

**Risk axis:** updater-test-helper-startup

**Platform boundary:** desktop

**Estimated scope:** 1 files, 10 lines

**Verification:** updater process runner/About cancellation focused、Desktop full-tests、Spotless、diff/range/guard

**Files:** `app-desktop/src/test/kotlin/mihon/desktop/update/DesktopUpdateProcessRunnerTest.kt`

1. RED：Task 20 Windows full-tests 的 2100 项中，三个真实 updater cancellation 测试在 `awaitUpdaterPid` 的 2 秒边界失败；两类 focused 复测仍有一项相同失败，证明当前 `java SourceFile.java` helper 的编译/启动时间不能稳定满足 2 秒。
2. GREEN：只调整测试 helper 的有界启动等待，不改变 updater production timeout、取消、强制终止或 reader 清理合同；不得改生产代码或以无限等待掩盖启动失败。
3. 运行两个直接消费 helper 的 focused 测试类、Spotless、diff/range/guard；独立审查通过后再恢复 Task 20 full-tests。

### Task 20D：Desktop 测试进程外部目录动作隔离

**Risk axis:** desktop-test-directory-isolation

**Platform boundary:** desktop

**Estimated scope:** 2 files, 80 lines

**Verification:** DesktopDirectoryOpener focused、Desktop compile、Spotless、diff/range/guard

**Files:** `app-desktop/src/main/kotlin/mihon/desktop/ui/settings/DesktopDirectoryOpener.kt`、`app-desktop/src/test/kotlin/mihon/desktop/ui/settings/DesktopDirectoryOpenerTest.kt`

1. RED：新增测试锁定 Gradle test worker 中默认目录 opener 不得调用真实系统 launcher，同时保留显式注入 fake launcher 的目录创建、成功与失败合同。
2. GREEN：只在 Gradle test worker 边界阻止默认 `Desktop.open`；正常 Desktop 运行时与显式注入 launcher 的测试行为不变，不按路径名猜测或仅屏蔽 `test-tmp`。
3. 运行 opener focused、Desktop compile、Spotless、diff/range/guard；独立审查通过前不恢复 Desktop full-tests。

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

**Review status（审查已清零，验证进行中）：** 对 `base-ref..f20861616` 的 whole-change 独立首审为 REJECTED `0/1/0`：ID94 的首许可证选择同时存在于 Android Screen、Desktop provider 与 shared policy，Desktop 在进入 shared 前已裁成单项，导致 shared selector mutation 无法破坏 production consumer。其余 fixed-main、搜索、anchor、主题、Desktop 独有能力、许可 UI、无障碍和测试有效性未发现阻塞项；focused contract `40/40` 通过。唯一 repair 已按平台边界拆为 20A（shared+desktop，`26e52ff47a`）与 20B（android，`ba47a9a4c`）串行完成；合并唯一修复复审 APPROVED `0/0/0`，focused `50/50`，确认 shared 策略真实控制 Android/Desktop production consumers、ID94 evidence 完整、ID88/fixed-main 无漂移且外部副作用为 0。全量验证已完成 Spotless 与 domain/data；Android 两次 full 分别出现不同的单项时序失败，三个失败类 focused 共 `30/30` 通过并停止随机重跑。Desktop full-tests `2100` 项中三个 updater helper PID 等待在 2 秒失败，focused 仍可复现相同启动边界；已新增最小 test-only Task 20C，在不改 production 合同的前提下关闭该验收阻塞，再恢复 Desktop 全量与平台验收。
