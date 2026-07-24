---
parent-plan: docs/superpowers/plans/2026-07-23-mihon-desktop-final-parity-audit.md
parent-task: Task 16C
original-ref: main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: planned
---
# UI dependency boundary closure
本计划只关闭 ID8/95 已确认的 current Android mapper 缺边与 32 条 Desktop compiled UI 违规。顺序固定为 164→169；每项独立红绿重构、mutation、focused test 和提交，不创建第二套 repository/manager。UI 入口与成功、失败、取消反馈保持不变；平台 adapter 只允许带非空理由的 OS side effect port。
- [ ] Task 164：Android network error mapper consumer
- [ ] Task 165：Library UI use-case boundary
- [ ] Task 166：Creator and tracking use-case boundary
- [ ] Task 167：Extension and browse ports
- [ ] Task 168：Settings, home and download ports
- [ ] Task 169：Compiled boundary closeout
### Task 164 Android network error mapper consumer
**Risk axis:** android-network-error-wiring
**Platform boundary:** shared+android
**Estimated scope:** 5 files, 340 lines
**Verification:** Android production HTTP response path executes `NetworkErrorMapper` for auth、429、5xx 与 malformed data；断开 mapper 时测试 RED。
**Files:** `NetworkErrorMapper.kt`, Android response adapter/installer, its MockWebServer test, DI wiring test。
**TDD:** 先写 current Android raw response→`AppError` 集成 RED，再最小委托 shared mapper；mutation 绕过 mapper 必须失败。
**User/feedback:** Android browse/extension network actions；保留 login、retry-after、server 与 malformed feedback。
### Task 165 Library UI use-case boundary
**Risk axis:** desktop-library-repository-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** `LibraryScreenModel`/`MangaDetailScreenModel` compiled graph 不再引用六类 repository，真实 library/detail behavior tests 全绿。
**Files:** 两个 ScreenModel、既有 category/chapter/manga/membership use cases、对应 production behavior tests。
**TDD:** 每组先写 use-case port consumer RED，再移除 repository constructor/call；mutation 注回 repository 必须由 compiled guard 拒绝。
**User/feedback:** Desktop Library 与 Manga detail；筛选、分类、章节、收藏、追踪及失败反馈零回退。
### Task 166 Creator and tracking use-case boundary
**Risk axis:** desktop-creator-tracking-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 380 lines
**Verification:** authors/tracking UI 的五条 repository compiled edge 清零，creator/tracker production behavior tests 执行新 port。
**Files:** author detail/root/compare、tracking model/settings、共享 creator/tracker interactors 与测试。
**TDD:** 先让真实 screen/model 只接 use case；断开 port 或恢复 repository 直连时分别 RED。
**User/feedback:** Authors、work compare、tracking settings/detail；列表、编辑、失败与重试保持可见。
### Task 167 Extension and browse ports
**Risk axis:** desktop-extension-browse-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 400 lines
**Verification:** extension/browse UI 不再编译引用 manager、raw `HttpUrl`、network helper 或 `ClassLoader`，安装/login/preferences tests 全绿。
**Files:** presentation port、source browse/login/preferences/details、既有 extension/network ports 与 wiring tests。
**TDD:** 先对 typed URL/context/install ports 写 consumer RED，再迁移；注回任一禁止类型时 compiled guard RED。
**User/feedback:** Extensions、Source browse/login/preferences；trust、login、retry、unsupported 与 failure feedback 不变。
### Task 168 Settings, home and download ports
**Risk axis:** desktop-settings-manager-boundary
**Platform boundary:** shared+desktop
**Estimated scope:** 8 files, 380 lines
**Verification:** About/Advanced/More/Home/LibraryRoot 不再引用 extension/download/challenge/network manager 或 raw `HttpUrl`。
**Files:** 五个 UI owner、现有 maintenance/challenge/download ports、DI 与行为测试。
**TDD:** 先写 owner→port production wiring RED，再移除 manager；mutation 注回 manager 精确触发 compiled guard。
**User/feedback:** About、Advanced、More、Home、Library downloads；确认、进度、错误、取消和恢复反馈零回退。
### Task 169 Compiled boundary closeout
**Risk axis:** compiled-boundary-closeout
**Platform boundary:** tooling
**Estimated scope:** 4 files, 260 lines
**Verification:** 32 条 acknowledged violation 归零、合法 use-case/adapter 边仍存在、ID8/95 五角色和 ordinary/final contracts 闭合。
**Files:** compiled architecture guard、parity manifest、parity contract、父计划。
**TDD:** 先将 expected violations 设为空产生 RED；164–168 全闭合后 GREEN，并复跑 forbidden/disconnected/blank-reason mutations。
**User/feedback:** 无新增入口；证明既有入口只能经 use case/port，平台 side effect feedback 保持可执行。
