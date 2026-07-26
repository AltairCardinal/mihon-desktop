---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 16C
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: completed
---
# UI dependency boundary closure
本计划只关闭 ID8/95 已确认的 current Android mapper 缺边与 32 条 Desktop compiled UI 违规。顺序固定为 164→169；每项独立红绿重构、mutation、focused test 和提交，不创建第二套 repository/manager。UI 入口与成功、失败、取消反馈保持不变；平台 adapter 只允许带非空理由的 OS side effect port。
- [x] Task 164：Android network error mapper consumer
- [x] Task 165：Library UI use-case boundary
- [x] Task 166：Creator and tracking use-case boundary
- [x] Task 167：Extension and browse ports
- [x] Task 168：Settings, home and download ports
- [x] Task 169：Compiled boundary closeout
### Task 164 Android network error mapper consumer
**Risk axis:** android-network-error-wiring
**Platform boundary:** shared+android
**Estimated scope:** 5 files, 340 lines
**Verification:** Android production HTTP response path executes `NetworkErrorMapper` for auth、429、5xx 与 malformed data；断开 mapper 时测试 RED。
**Files:** `NetworkErrorMapper.kt`, Android response adapter/installer, its MockWebServer test, DI wiring test。
**TDD:** 先写 current Android raw response→`AppError` 集成 RED，再最小委托 shared mapper；mutation 绕过 mapper 必须失败。
**User/feedback:** Android browse/extension network actions；保留 login、retry-after、server 与 malformed feedback。
**Execution evidence:** MockWebServer RED 精确证明 current Android 未消费 shared mapper 且 429 丢失 `Retry-After`；GREEN 后 production `ExtensionApi → AndroidNetworkResponseAdapter → NetworkErrorMapper` 与 AppModule no-arg wiring focused `10/10`。绕过 shared status mapper、绕过 shared payload parser及移除 AppModule binding 均分别精确 RED 后恢复；唯一审查的两项 P1 与一项 P2 经一轮修复复审后 `APPROVED`。主门禁补入 catalog→install 下游回归后 Android `19/19`、parity governance 与 Spotless 全绿。
### Task 165 Library UI use-case boundary
**Risk axis:** desktop-library-repository-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** `LibraryScreenModel`/`MangaDetailScreenModel` compiled graph 不再引用六类 repository，真实 library/detail behavior tests 全绿。
**Files:** 两个 ScreenModel、既有 category/chapter/manga/membership use cases、对应 production behavior tests。
**TDD:** 每组先写 use-case port consumer RED，再移除 repository constructor/call；mutation 注回 repository 必须由 compiled guard 拒绝。
**User/feedback:** Desktop Library 与 Manga detail；筛选、分类、章节、收藏、追踪及失败反馈零回退。
**Execution evidence:** compiled guard 先精确 RED 于 Library/Detail 的 9 条 repository edge；GREEN 后两 ScreenModel 改由 category/chapter/history/manga/membership/creator/tracking interactors 消费，production factory 与 DI 真实 wiring。独立审查发现 unread 进度、NEXT 排序/scanlator filter、chapter flags 及错误传播语义缺口；唯一修复轮提取 shared `SetChapterReadStatus`、复用 `GetNextChapters`/`SetMangaChapterFlags` 并补 throwing mutation，单项 NEXT 遗漏由主代理以精确 RED→GREEN 收口。相关 focused `113/113`、单项 Library `34/34`、提交前组合门禁、repository 回注/吞异常 mutations、Spotless 与 diff check 全绿；Desktop creator、迁移、下载及反馈能力保留。
### Task 166 Creator and tracking use-case boundary
**Risk axis:** desktop-creator-tracking-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 380 lines
**Verification:** authors/tracking UI 的五条 repository compiled edge 清零，creator/tracker production behavior tests 执行新 port。
**Files:** author detail/root/compare、tracking model/settings、共享 creator/tracker interactors 与测试。
**TDD:** 先让真实 screen/model 只接 use case；断开 port 或恢复 repository 直连时分别 RED。
**User/feedback:** Authors、work compare、tracking settings/detail；列表、编辑、失败与重试保持可见。
**Execution evidence:** compiled guard 先精确 RED 于 authors 3 条 `CreatorRepository` 与 tracking 2 条 `TrackRepository` edge；GREEN 后 Authors 通过 `GetCreators`/`GetCreatorDetails`/`DiscoverCreatorWorks`/`SetCreatorFollow` 保留列表、关注、发现、候选与作品比较，Tracking 复用 `GetTracks`/`InsertTrack`/`DeleteTrack` 的 throwing 路径并保留 mutex、enhanced bind、更新、解绑及反馈。Local dependencies、settings construction、Test Mode 与既有 fixtures 同步 production wiring；repository 回注 mutation 精确 RED 后恢复。实现 focused、主组合门禁、独立审查、Spotless 与 diff/residue checks 全绿。
### Task 167 Extension and browse ports
**Risk axis:** desktop-extension-browse-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** extension/browse UI 不再编译引用 manager、raw `HttpUrl`、network helper 或 `ClassLoader`，安装/login/preferences tests 全绿。
**Files:** presentation port、source browse/login/preferences/details、既有 extension/network ports 与 wiring tests。
**TDD:** 先对 typed URL/context/install ports 写 consumer RED，再迁移；注回任一禁止类型时 compiled guard RED。
**User/feedback:** Extensions、Source browse/login/preferences；trust、login、retry、unsupported 与 failure feedback 不变。
**Execution evidence:** compiled guard 移除 extension/browse 的 11 条 manager、raw `HttpUrl`、network helper 与 `ClassLoader` edge 后精确 RED；GREEN 后 URL/cookie、challenge recovery、extension lifecycle/last-used/context 与 cookie clearing 均下沉到 typed adapter/窄端口，UI 保留 trust、安装、更新、卸载、login、取消、超时、invalid/commit、Cloudflare retry/failure 及 Desktop JAR/context 行为。完整 focused gate、真实 forbidden-edge mutation、独立审查、主门禁、Spotless 与 residual/diff checks 全绿；11 条目标 edge 清零。
### Task 168 Settings, home and download ports
**Risk axis:** desktop-settings-manager-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 380 lines
**Verification:** About/Advanced/More/Home/LibraryRoot 不再引用 extension/download/challenge/network manager 或 raw `HttpUrl`。
**Files:** 五个 UI owner、现有 maintenance/challenge/download ports、DI 与行为测试。
**TDD:** 先写 owner→port production wiring RED，再移除 manager；mutation 注回 manager 精确触发 compiled guard。
**User/feedback:** About、Advanced、More、Home、Library downloads；确认、进度、错误、取消和恢复反馈零回退。
**Execution evidence:** compiled guard 清除最后 7 条 Home/LibraryRoot/About/Advanced/More 的 concrete manager/helper/`HttpUrl` inventory 后精确 RED；GREEN 后 Home 复用同一 challenge flow/recovery port，Library/More 复用同一 download queue state，About 复用同一 extension presentation state，Advanced 的校验、URL canonicalization、`cf_clearance` 写入与 clear-all 下沉到原 network helper 的窄 maintenance port。DI 以 same-instance 断言 production wiring；51 项 focused、forbidden-edge mutation、独立审查、主门禁、Spotless 与 residual/diff checks 全绿，32 条初始违规现已归零。
### Task 169 Compiled boundary closeout
**Risk axis:** compiled-boundary-closeout
**Platform boundary:** tooling
**Estimated scope:** 4 files, 260 lines
**Verification:** 32 条 acknowledged violation 归零、合法 use-case/adapter 边仍存在、ID8/95 五角色和 ordinary/final contracts 闭合。
**Files:** compiled architecture guard、parity manifest、parity contract、父计划。
**TDD:** 先将 expected violations 设为空产生 RED；164–168 全闭合后 GREEN，并复跑 forbidden/disconnected/blank-reason mutations。
**User/feedback:** 无新增入口；证明既有入口只能经 use case/port，平台 side effect feedback 保持可执行。
**Execution evidence:** Task16C focused contract 先精确 RED 于 ID8 `expected VERIFIED but was SHARED`。终态审计确认 ID8/95 五角色闭合，ID8 的 3 条与 ID95 的 40 条 required compiled edge 全部存在，`forbidden=0`、`missing=0`；forbidden/disconnected mutation 与 platform allowlist 的缺边、空 reason mutation 均有效。ID8/95 分别由 `SHARED`/`WIRED` 精确提升为 `VERIFIED`，未批量 promotion。
