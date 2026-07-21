---
change: align-desktop-platform
role: technical-design
base-ref: 952be2f7897f9221b2e07bf7e52891a8fdaa8696
original-ref: 6fbf6dfca203d99d6dd32137f2df97ced40c81b8
status: executable
---

# Mihon Desktop 系统集成、隐私与发布技术设计

## 1. 背景与目标

Mihon Desktop 已有漫画详情、系统剪贴板、系统凭据存储、发布 API 和更新数据查询等局部能力，但缺少与原版 Mihon 一致的完整产品链路：外部 URI 不能进入应用，分享失败被静默吞掉，系统凭据只服务 tracker 而没有应用锁，安全设置与窗口隐私没有真实能力探测，Desktop 发布检查也没有接入下载、验证和安装交接。

本 change 对齐固定原版 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 的产品语义。当前 `app/` 只是 fork 后的 Android consumer；`app-desktop/src/main/kotlin/android/` 是 Desktop 为扩展兼容提供的 Android API shim。两者都不能替代固定原版证据。

目标不是复制 Android API，而是形成以下结构：

```text
fixed-main fixture
       │
       ▼
shared parser / policy / state machine
       │
       ├── current Android Intent / Biometric / Window / WorkManager adapter
       └── Desktop Windows / macOS / Linux adapter
                         │
                         ▼
                 Voyager UI + 可见反馈
```

Desktop 独有功能不得删除或降级。系统能力无法等价实现时，必须由真实 capability probe 返回限制，并在 UI 和 parity 证据中明确说明，不能用成功文案掩盖不支持。

## 2. 权威行为与平台边界

| ID | 固定原版语义 | 共享语义 | 必须保留的平台边界 | Desktop 目标 |
|---|---|---|---|---|
| 81 Deep link | 搜索/分享文本进入全局搜索；可解析源 URI 进入漫画或章节；无结果回退全局搜索；备份和仓库 URI 进入对应页面 | 外部动作模型、URI 校验、空输入 no-op、首个可解析源和 NoResults fallback | Android manifest、Intent flags、Activity/Reader 启动；Desktop argv、IPC、协议注册和 Voyager 导航 | 冷启动和运行中实例均接收动作，非法输入有安全反馈 |
| 82 分享 | HTTP(S) 作为文本；content URI 作为带 MIME/消息的流；入站文本分享等价于搜索 | Share payload、动作结果和失败语义 | Android chooser/ClipData/URI grant；Desktop native share、clipboard/save adapter | 系统分享可用则使用；否则明确复制/保存并反馈真实结果 |
| 83 应用锁 | `-1` 从不、`0` 总是、正数为分钟；进程首次必锁；关闭时间只为延迟锁保留；认证失败保持锁定 | `AppLockPolicy`、生命周期事件、认证结果和 fail-closed 规则 | Android BiometricPrompt；Desktop passphrase/OS credential service 与窗口生命周期 | 启动、超时、失败和凭据不可用均有受保护 UI |
| 84 屏幕安全 | `ALWAYS`、`INCOGNITO`、`NEVER` 与隐身模式形成纯决策表 | `SecureScreenPolicy` 和 capability/result | Android `FLAG_SECURE`；各 Desktop OS 的窗口 API | 只在已验证支持时显示保护成功；有限/不支持时说明边界 |
| 85 Widget | 三个月更新、按漫画去重、loading/empty/locked 三态；应用锁开启时不泄露内容 | 复用既有 `GetUpdates` 与安全偏好，不新建无消费者抽象 | Android Glance/AppWidget/Samsung cover provider | Desktop 记为平台豁免；保留更新数据与锁定隐私契约证据 |
| 86 应用更新 | 三日节流、force check、版本比较、可见结果；下载失败可重试，成功后由平台安装 | 发布检查、版本/产物选择、下载/验证/安装状态 | Android 通知、WorkManager、APK 安装；Desktop MSI/DMG/发行格式与签名验证 | About 入口、真实版本、兼容产物、校验、确认、失败恢复和手动后备 |
| 92 安全设置 | 能力决定可用性；开启锁和修改锁策略前认证；屏幕安全三态；偏好键与默认值稳定 | `SecurityPreferences`、可用性矩阵和设置状态 | Android FragmentActivity/通知 consumer；Desktop Security Screen 和 capability 文案 | More → Security 入口、确认、错误反馈、DI/导航/实例化测试 |

## 3. 采用的架构

### 3.1 外部动作

`domain/common` 定义 `ExternalAction`、`ExternalActionParser`、`SharePayload` 和结构化结果。parser 只处理字符串、URI 字段和固定动作，不引用 `Intent`、AWT、Compose 或 Voyager。

当前 Android consumer 把 Intent action/extras 映射为共享输入，再执行 Android Activity/Screen side effect。Desktop 通过三层完成同一语义：

1. `DesktopExternalActionBroker` 接收进程 argv 或已运行实例转发的数据；
2. `DesktopDeepLinkHandler` 使用现有 `SourceManager`、`ResolvableSource`、`NetworkToLocalManga`、章节同步和备份/仓库能力解析业务目标；
3. `DesktopExternalActionNavigator` 把类型安全目标映射到 Voyager Screen，并把拒绝、无结果和执行失败送到可见反馈。

无结果是固定原版的正常 fallback：原始 query 进入全局搜索。非法 scheme、缺少必需参数或越权本地路径才是拒绝错误，不能执行部分导航。

外部入口矩阵必须精确，不用“支持 deep link”笼统代替：

- `tachiyomi://add-repo?url=<https-url>` 是 fixed-main 唯一需要注册的 URL scheme/host/query 组合；缺少、重复或非 HTTP(S) `url` 时拒绝；
- `.tachibk` 是备份文件关联，Desktop 只接受用户通过 OS 传入且经路径/文件验证的本地文件；
- Android `ACTION_SEARCH`、私有 SEARCH 和 `ACTION_SEND text/plain` 不是 URI scheme。Desktop 通过有界 `--search <text>`/运行中 broker 输入表达等价搜索动作，不注册虚构 scheme；
- 源漫画/章节 HTTP(S) URL 作为搜索文本进入共享解析/NoResults fallback，不把所有网页 URL 注册为 Mihon 协议；
- 不注册没有 fixed-main provenance 的 `mihon://` alias；将来增加 alias 必须作为显式 Desktop 产品增强记录。

单实例使用本机 loopback IPC、随机认证 token 和 owner lock。只有 owner 创建应用窗口；后续进程把长度受限的动作发送给 owner，收到确认后退出。消息不能监听外网地址，不能反序列化任意类，也不能把未验证 payload 直接送入导航。

协议注册是独立 adapter：Windows 使用当前用户 URL protocol/安装器注册，macOS 使用 bundle URL type，Linux 使用 `.desktop`/`x-scheme-handler`。注册存在不等于运行时 broker 已工作，二者分别测试。

### 3.2 分享与后备

UI 只提交 `SharePayload`，不直接操作 Toolkit 或 `java.awt.Desktop`。`DesktopShareService` 返回以下真实结果之一：

- `SharedNatively`：平台 adapter 确认 native share 已打开；
- `CopiedToClipboard`：native share 不可用，clipboard 写入成功；
- `Saved`：二进制/文件 payload 通过用户选择保存成功；
- `Cancelled`：用户取消，不显示成功；
- `Unavailable` / `Failed`：显示可恢复错误，不静默吞掉异常。

现有详情页“Share link”不得继续调用无结果的 `copyText`。复制仍可作为 Desktop 增强保留，但必须与分享结果区分。

### 3.3 应用锁与安全设置

`AppLockPolicy` 精确复现固定原版的延迟语义：

- 进程启动且锁已启用时要求解锁；
- `-1` 不因后台时间再次锁定；
- `0` 每次离开活动状态后锁定；
- 正数按 `lastAppClosed + delay` 判断；
- 每次恢复判断后清除一次性关闭时间；
- 认证取消、失败、异常或凭据不可用都保持锁定。

Desktop 不伪装成 Android 生物识别。用户设置 Mihon 专用 passphrase；验证材料由现有 DPAPI、macOS Keychain 或 Linux Secret Service backend 保存，不存在 Preferences/明文 fallback。credential service 不可用时不允许启用新锁；已启用锁若 backend 失败则 fail closed，并显示恢复说明。

`DesktopAppLock` 是运行时状态机，消费窗口 active/inactive、关闭和认证事件。锁定时根 Compose 内容只渲染 unlock surface，不能先构造或短暂显示受保护页面。More → Security 提供启用/关闭、锁定延迟和屏幕安全设置；开启、关闭或修改凭据前都要求当前验证。

ID 92 的其他设置按真实 Desktop consumer 处理：

- `hideNotificationContent` 只控制系统通知中的敏感详情。当前 `DesktopNotificationService` 是应用内 Snackbar，不是锁屏/系统通知，不能错误套用该偏好；Security UI 仅在 production native-notification capability 存在时显示此开关，否则显示/记录“不适用”边界。
- fixed-main 的 crashlytics/analytics 组本来就受 `telemetryIncluded` 条件控制。当前 Desktop 产物未接入 telemetry runtime，因此 production capability 为 false，不显示两个无消费者开关；不能仅注册 `PrivacyPreferences` 伪装完成。
- `DesktopPrivacyCapabilities` 是 DI 注入的产品能力事实，Security UI、parity 和测试共同消费；它不得仅由 OS 名称或源码扫描推导。

### 3.4 窗口隐私

`SecureScreenPolicy(mode, incognito)` 是共享纯函数，当前 Android consumer 和 Desktop 都消费它。Desktop adapter 必须返回 `Supported`、`Limited(reason)`、`Unsupported(reason)` 或 `Failed(error)`：

- Windows 可以在真实窗口句柄上验证 `SetWindowDisplayAffinity` 的应用和清除结果；
- macOS 仅能声明实际验证到的窗口共享限制，不能把有限能力描述为全面阻止系统截图；
- Linux 没有统一窗口级截图阻止契约，默认不支持，除非目标 compositor/portal 有可执行证据。

无论系统截图能力如何，应用锁覆盖层都必须阻止 Mihon 自身继续渲染敏感内容。系统级保护和应用内遮挡是两个不同能力，不能互相冒充。

当前仓库没有 native window binding。Task 11 显式引入版本锁定的 JNA/JNA Platform：Windows bridge 负责 HWND、`SetWindowDisplayAffinity`/query ABI 和错误清理；macOS 若同一 bridge 无法给出可执行且可查询的保证，只返回 Limited/Unsupported，不在该 Task 静默扩张另一套 JNI。native handle 生命周期绑定 Compose Window 创建/销毁，adapter 关闭后不得继续持有句柄。

### 3.5 发布检查、下载和安装交接

保留固定原版 `GetApplicationRelease` 的三日节流、force check 和版本比较结果。`ReleaseServiceImpl` 不再用 Android ABI 子串猜测所有平台产物，而是消费显式 `ReleaseTarget(os, arch, packageType)` 与结构化 asset metadata。

Desktop 发布通道由可注入构建配置提供，生产默认指向本项目的发布仓库；测试不得访问公网。没有兼容 Desktop 产物时，结果是“当前发布没有适用包”，而不是“没有新版本”。

更新状态机为：

```text
Idle
  → Checking
  → UpToDate | UpdateAvailable | NoCompatiblePackage | CheckFailed
UpdateAvailable
  → Downloading(progress)
  → Verifying
  → ReadyToInstall
  → HandingOff
  → HandedOff | InstallFailed
Downloading/Verifying/InstallFailed
  → Cancelled | RetryableFailure
```

下载只写临时目录，限制重定向与最大体积，完成后先验证 release 声明的 SHA-256，再验证平台签名/发布者。缺少可信校验元数据时不得自动安装，只能打开 release 页面供用户手动处理。Windows adapter 交接 MSI，macOS adapter 交接已验证 DMG/应用更新路径；未提供受支持发行格式的 Linux 只显示手动后备。安装交接失败不得修改当前可启动应用。

fixed-main 的 APK 流程没有自行实现 SHA-256、下载体积和重定向门禁；Android 安装器以包签名承担平台验证。Desktop 的 SHA-256、平台签名/发布者、体积/重定向限制和更细状态均是 **Desktop security enhancement / cross-platform hardening**。它们保护 Desktop 安装 side effect，但不得在 parity provenance 中写成原版 Mihon 行为，也不得改变 fixed-main 的节流、版本比较、可见检查结果和失败可重试语义。

About 页面显示 `APP_VERSION`，提供手动检查入口和各状态反馈。任何下载、取消、安装都需要用户可见进度；启动安装 side effect 前要求确认。

### 3.6 Widget 豁免

Desktop 不复制 Android Glance/AppWidget。现有 `GetUpdates` 已是 Android Widget 与 Desktop Updates 页面共同消费的更新数据契约；再造一个没有 production consumer 的“Widget provider”只会增加技术债。

ID 85 的 Desktop 状态为平台豁免，证据包括：三桌面 OS 没有与 Android Glance 等价的统一 provider/runtime；当前产品没有可维护的跨平台宿主；More → Security 的应用锁说明明确 Desktop 不向系统 Widget 发布内容。若未来某 OS 增加独立 Widget，必须作为平台 adapter change 实现，不能改变共享更新与锁定隐私规则。

Android Widget 证据必须落在 `presentation-widget` production 链：由真实数据门禁在锁启用时拒绝调用 `GetUpdates`，`WidgetManager` 的更新触发仍同时观察 updates 与锁偏好。Desktop parity 测试只能证明 Desktop Updates consumer 和“不提供系统 Widget”的 capability，不能冒充 Android Glance wiring。

## 4. 复用与禁止重复实现

必须复用：

- `SecurityPreferences` 的键、默认值与持久化；
- `GetApplicationRelease`、`ReleaseService` 和 `Release` 的既有业务入口；
- `GetUpdates` 的更新查询；
- `DesktopCredentialStore` 的 DPAPI/Keychain/Secret Service 能力；
- Desktop 现有 `SourceManager`、源 URI 解析、漫画落地、章节同步、备份恢复、扩展仓库和 Voyager Screen；
- 现有 `APP_VERSION` 唯一版本来源、通知/任务状态和 Test Mode 基础设施。

禁止：

- 把 Android `Intent`、`Context`、BiometricPrompt、`FLAG_SECURE`、WorkManager、APK installer 或 Glance 放进 common；
- 在 `DesktopAppPreferences` 重复安全偏好键或锁定超时规则；
- 用 `OperatingSystem.detect()` 代替真实 capability probe；
- 保留详情页无结果的 `copyText` 作为“分享成功”；
- 用当前 `app/` 测试或 Desktop 自生成 fixture 冒充固定原版 provenance；
- 为测试方便改变固定原版的 NoResults fallback、锁延迟或版本判断语义；
- 为“支持 Linux”虚构不存在的安装包、截图保护或 Widget provider。

## 5. UI 入口与反馈

- 外部 URI：OS 协议/命令行触发；有效动作进入目标页，无结果进入全局搜索，非法输入显示安全错误。
- 分享：漫画详情和适用阅读器动作进入 `DesktopShareService`；Snackbar/对话框区分系统分享、已复制、已保存、取消和失败。
- 应用锁：More → Security；锁定时显示 unlock surface，凭据不可用和失败均有明确恢复反馈。
- 窗口隐私：Security 页面显示当前平台能力、实际保护强度与限制；切换失败恢复原值。
- 应用更新：More → About → Check for updates；显示无更新、新版本、无适用包、下载/校验/安装交接与错误重试。
- Widget：Security/维护说明中明确 Desktop 不发布系统 Widget；不显示虚假的启用开关。

## 6. 迁移与回滚

1. 先以固定原版 fixture 建立 shared RED，再实现 parser/policy/state。
2. 先让当前 Android consumer 委托 shared，确认默认行为未变；Android 平台入口保持不动。
3. Desktop 逐项接入，每个入口有 production wiring 与集成测试后才删除旧的直接调用或静默路径。
4. OS 注册、credential、window privacy 和 installer 都通过 adapter；单一 OS 失败不允许污染 shared 状态。
5. updater 始终保留当前可启动版本；下载临时文件可安全删除，安装交接前不覆盖应用。
6. 若某 Task 回归，回滚该 Task 的入口切换提交；不得恢复长期双轨 parser/policy/state 作为最终方案。

本 change 不迁移漫画数据库。安全偏好沿用原版键；新增 app-lock verifier 使用独立版本化 credential namespace，可删除并重新设置，不把 secret 写入普通偏好。

## 7. 测试与验收策略

### 7.1 固定原版与 shared

- fixture 必须记录 fixed-main ref、路径、符号和输入/输出；
- URI/search/share、锁定三档、关闭时间、secure-screen 决策表、版本/节流和错误结果均做纯契约测试；
- mutation 必须证明当前 Android consumer 或 Desktop 任一 production delegate 被拆断时测试失败。

### 7.2 Android consumer

- JVM/集成测试验证 Intent 映射仍进入 shared action，SecureActivityDelegate 和窗口 adapter 消费 shared policy；
- `presentation-widget` 测试通过真实 Widget 数据门禁/manager wiring 证明锁启用时不查询或渲染更新内容；
- 需要平台 runtime 才能证明的入口使用 Android 模拟器；不能把 emulator 结果称为固定原版证据。

### 7.3 Desktop

- JVM：parser、resolver、loopback broker、share fallback、app-lock lifecycle、credential failure、capability probe、release asset、download/校验/installer、DI 和 Screen/导航；
- MockWebServer：发布成功、空/缺失 asset、403/429/500、畸形 JSON、超限/哈希错误与重试；
- Test Mode：外部动作、分享结果、锁定/解锁、隐私 capability 和更新状态必须可通过真实 production wiring 观察；
- Windows：协议启动/运行中转发、DPAPI、clipboard/share、窗口保护、MSI handoff；
- macOS：bundle scheme、Keychain（需要 GUI 时明确）、分享/窗口能力与 DMG handoff；
- Linux：可执行的 Secret Service、desktop entry/xdg、clipboard/portal 与手动更新后备；缺少环境证据的能力保持未完成或有限，不外推。

### 7.4 完成定义

只有以下全部成立才完成 Task 5A：

- parity 81–84、86、92 有固定原版 provenance、shared/Android/Desktop production wiring 和会因断线而失败的测试；
- ID 85 有复用 `GetUpdates`、锁定隐私边界、三平台事实和 roadmap 批准的豁免证据；
- Desktop 所有用户可见能力有入口与反馈；
- 每个实现 Task 经独立审查，修复复审后无未解决 Critical/Important；
- 定向、全量、Windows 固定 EXE、适用 Android 模拟器和 macOS/Linux 平台验证使用同一最终提交；
- 父 roadmap、子计划、parity manifest、维护文档和进度记录一致。
