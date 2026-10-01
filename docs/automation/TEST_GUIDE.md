# Mihon Desktop 自动化测试指南

## 快速开始

### 阅读器翻页动画回归

在「设置 → 阅读器 → 翻页动画」中切换开关。单页、双页和左右阅读方向下，
鼠标点击、键盘、滚轮与页码跳转共用此设置；常规设置里的既有开关使用相同偏好值。
条漫保留连续滚动方式。

分页视图保留前后各一个视口的图片持有，使已就绪相邻页在关闭动画时直接切换，
避免重新挂载图片期间露出背景。相邻视口预热必须等到当前章节可见页实际绘制后才开始，
避免额外解码抢占首帧；章节代际切换后重新等待绘制确认。此窗口复用现有解码与释放链路，不保证尚未下载、
未解码或加载失败的页面瞬间显示；这些页面继续提供加载或重试反馈。
快速连续请求必须以最后一次请求为准，鼠标操作中断动画后不得残留页码反馈屏蔽。
分页点击监听挂在稳定的 Pager 容器上，以便按下和松开跨越动画帧时仍接收同一次手势。
外部跳页尚在动画中时，首次用户点击以先前停稳页为基准；随后连续点击以最新用户目标为基准。
拖动或新的外部跳页会清除该用户目标。此规则同时适用于单页、双页及左右阅读方向。

`ReaderPageTurnPresentationTest` 挂载真实 ReaderContent 与图片 owner，以离屏帧检查
动画、无黑帧、设置实时生效、跨帧点击与快速请求；mounted production 测试同时约束相邻窗口
之外不得提前解码、可见页不得重复解码。

`ReaderCriticalPathProductionTest` 与 `ReaderIoProductionWiringTest` 保护首帧前仅当前页
打开和解码的约束。离屏夹具应注入真实偏好、有界推进渲染与后台 I/O，并释放每帧图片；
不能依赖不推进虚拟时间的循环触发 `withTimeout`，也不能通过放宽首帧计数接纳预热回归。

定向验证示例：

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/gradle-coordinator.py run --key reader-page-turn -- .\gradlew.bat :app-desktop:jvmTest --tests mihon.desktop.ui.reader.ReaderPageTurnPresentationTest
```

### 构建与测试

运行桌面 JVM 测试和 Robot 客户端测试：

```bash
./gradlew :app-desktop:jvmTest :test-desktop:test
```

构建并验收桌面应用：

```bash
./scripts/build-desktop.sh
```

如果当前未提交 diff 已经通过等价的完整 Desktop JVM 测试，只需避免收口构建重复测试时，必须显式使用：

```bash
./scripts/build-desktop.sh build-only
```

`build-only` 仍会分配新的 BUILD 版本、构建正式未打包应用、执行生产扩展安装与运行版本验收，
但跳过脚本内的 `:app-desktop:jvmTest`。默认、`feature`、`stage`、`msi` 和 `evidence` 模式仍会运行测试；
没有同一 diff 的完整测试证据时不得使用 `build-only`。

Windows 默认先在 Gradle 临时目录生成未打包应用并完成运行验收，然后将完整应用发布到持久目录；
不会生成 MSI。构建成功后可直接运行、并应写入完成报告的最终 EXE 为：

```text
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-unpacked/Mihon Desktop.exe
```

构建日志会输出该次构建的准确绝对路径：

```text
Final unpacked EXE: D:\...\app-desktop\artifacts\windows\Mihon-Desktop-<完整版本>-unpacked\Mihon Desktop.exe
```

完成报告必须复制这条输出中的实际路径并在报告前确认文件存在，不能根据模板猜测版本号。
`app-desktop/tmp/` 仅供内部构建、运行验收和 Test Mode 使用，不能作为完成报告中的构建地址。
最终目录中的 launcher 必须与同级 `app/`、`runtime/` 一起保留。Windows 构建成功后还会生成可搬运的完整 ZIP：

```text
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip
app-desktop/artifacts/windows/Mihon-Desktop-0.STAGE.FEATURE.BUILD.GIT_HASH-windows.zip.sha256
```

对外分发和人工安装可使用 ZIP；直接运行验收可使用上述最终未打包 EXE。脚本会在成功前检查压缩包内
同时包含 launcher、应用文件和 Java runtime，并生成 SHA-256。

构建脚本会先启动临时构建 EXE，确认窗口标题中的运行版本与本轮
`0.STAGE.FEATURE.BUILD.GIT_HASH` 完全一致，再发布最终目录。只有发布时才显式执行
`./scripts/build-desktop.sh msi`；MSI 不能替代未打包版本的开发验收。

启动测试模式：

```bash
"/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop" \
  --test-mode \
  --test-profile=/absolute/path/to/dedicated-mihon-test-profile \
  --test-http-port=8080
```

无界面模式仅适合 HTTP 状态/API 测试：

```bash
"/Applications/Mihon Desktop.app/Contents/MacOS/Mihon Desktop" \
  --test-mode --test-profile=/absolute/path/to/dedicated-mihon-test-profile \
  --test-http-port=8080 --headless
```

### macOS 桌面会话的安全存储验收

验收正式应用的 Keychain 读写时，通过 macOS 应用启动服务启动已核对的 `.app`，并使用独立 profile。
SSH 中直接执行 `Contents/MacOS/Mihon Desktop` 的结果须记录为该启动上下文的证据；若安全存储失败，
再核对桌面应用启动路径，不能仅凭 SSH 的默认钥匙串状态断言用户桌面钥匙串不可用。
不要修改钥匙串设置或收集用户密码来绕过失败。

下例将 `MIHON_ACCEPTANCE_APP` 替换为本轮构建日志 `Final macOS app:` 的实际绝对路径；
先确认 HTTP/JMX 端口空闲，旧 Test Mode 服务存在时停止本次启动，而非复用旧服务。

```bash
MIHON_ACCEPTANCE_APP='/absolute/path/to/validated/Mihon Desktop.app'
MIHON_ACCEPTANCE_PROFILE="${TMPDIR%/}/mihon-keychain-acceptance-$(uuidgen)"
open -n -W -a "$MIHON_ACCEPTANCE_APP" --args \
  --test-mode --test-profile="$MIHON_ACCEPTANCE_PROFILE" \
  --test-http-port=49163 --test-jmx-port=49164 --headless
```

验收原生窗口时去掉 `--headless`，并单独核对本次应用进程的窗口元数据或取得用户现场确认。
启动命令返回成功、HTTP health 正常及同步 state 的 `visible=true` 都不能证明窗口已呈现；
其中 `visible` 只表示产品面板状态。需要激活时，使用 `open -a "$MIHON_ACCEPTANCE_APP"` 激活已核对的应用包，
避免误打开日常安装。窗口元数据检查只核对目标进程的窗口存在性、屏幕列表和尺寸，不读取屏幕像素。
等待现场检查期间保持本次窗口打开；未收到反馈的窗口/键盘/视觉项目不记为通过。

`open -W` 等待应用退出，其 PID 是启动包装器，不能当作 Mihon 的 PID。通过本地 HTTP、显式绕过代理，
核对该实例的 health、production 同步面板打开/设置/关闭，再调用已有 `/test/sync/probe/write`。
保留返回的虚构 probe ID；正常 shutdown 后，复用同一 profile 再启动并调用
`/test/sync/probe/verify/{id}`，必须完成跨进程读回和删除。仅调用保留前缀的测试接口，不读取真实授权或空间秘密。
每次完成后调用 `/test/shutdown`，等待包装器退出并核对本次应用进程已退出；异常时只处理本次精确实例。
若有系统访问提示，由值守用户核对程序并决定授权；没有提示也不能代替真实读写断言。
`--headless` 的证据仅覆盖 HTTP/production controller/系统安全存储，不代表原生窗口、键盘或视觉验收。

### macOS 同步面板原生交互自动化

本轮正式应用必须包含只读 `GET /test/sync/ui` 接口。通过上述 LaunchServices 命令启动可见窗口（去掉 `--headless`），
使用全新隔离 profile，使同步面板初始关闭且未连接；随后在同一 Mac 执行：

```bash
python3 scripts/mac-sync-native-acceptance.py \
  --base http://127.0.0.1:49163 \
  --app "$MIHON_ACCEPTANCE_APP" \
  --profile "$MIHON_ACCEPTANCE_PROFILE"
```

脚本核对实际应用 PID、应用包路径、profile、窗口激活与控件坐标，并检查图形会话未锁屏。
CoreGraphics 窗口矩形仅用于确认目标窗口存在；Dock 等窗口可能报告覆盖全屏的矩形，不能单凭矩形顺序推导鼠标命中。
外部工具通过系统辅助功能的坐标命中接口只读取目标 PID，确认属于本次应用后才发送 CoreGraphics HID 鼠标事件；键盘事件发送给核对后的应用 PID。
它通过只读接口验证鼠标打开同步面板、Tab/Shift+Tab 完整正反回环、Escape 关闭与同步入口还焦、Enter/Space 重开。
HTTP 控制动作不能代替这些原生事件。同一 AWT 窗口中的底层 Compose owner 可能在弹层挂载后保留工具栏局部 Focus 标记。
面板挂载时只核对面板作用域，卸载后核对工具栏；同时要求 `ownerFocused` 和实际 `focusedWindow`，不把背景标记算作当前焦点。
脚本不读取屏幕像素，不登录 GitHub，不创建空间或读取输入内容；只验未登录 MAIN 场景，不覆盖密码页视觉或真实远端同步。
若屏幕锁定、系统拒绝原生输入或辅助功能命中权限，或场景发生变化，脚本立即失败；记录真实失败并由值守用户处理系统授权，不更改权限绕过验证。
运行结束仍保留应用供现场观察；调用 `/test/shutdown` 后核对实际应用 PID 与 `open -W` 包装器均退出。

### 隔离验收配置

在个人电脑验收时始终显式传 `--test-mode --test-profile=<绝对目录>`。Windows 使用本轮正式未打包 EXE，
参数相同；不要仅修改 `user.home` 或指定 JDK 内部 `FileSystemPreferencesFactory`，前者不能隔离
Windows APPDATA/注册表，后者不保证在 macOS 发布运行时可用。

- 首次使用不存在或空目录；程序写入 `.mihon-test-profile` 标记，后续可复用以验证冷启动持久化。
- 作者身份正式运行验收可使用 `authors_state`、`author_resolve`、`author_add_aliases`、
  `author_set_display_name`、`author_set_frequency`，参数见 `API_REFERENCE.md` 的作者身份验收动作。
  `author_resolve` 仅解析现有漫画的真实署名。固定离线 `author_sync_fixture` 必须在显式隔离
  profile 中运行，不能对普通用户配置执行；它验证生产 inbox/projector/journal，不能代替真实
  远端同步验收。重启复验继续使用同一 profile，并重新读取当前 revision 后再修改。
  非空且无标记、根目录、普通 home、符号链接路径被拒绝。不要将日常数据目录伪装成测试目录。
- 启动入口在 crash handler、实例选举和 DI 之前选择 profile。数据库、缓存、日志、扩展、下载默认目录
  及历史 `user.home` 路径均隔离；Java Preferences 全局切换到该 profile 的可持久化后端，涵盖旧偏好和扩展设置。
  偏好已提前初始化时拒绝启动，不退回普通用户后端。普通启动及未传 profile 的历史 Test Mode 行为不变。
- Windows 凭据仍通过 DPAPI 加密，密文保存到隔离偏好；macOS Keychain/Linux Secret Service 使用 profile
  路径派生的专用 service 名，保留生产安全存储实现。不会注册或覆盖系统 URI handler。
- 这是默认状态隔离，不是恶意扩展沙箱。测试主动传入的导入、导出或存储路径仍须位于专用目录，勿登录真实账号。
  一个 profile 只供一个应用 owner 使用；不要在运行期间手动编辑 profile 文件。删除 profile 文件不会自动清除
  OS 安全存储中的测试凭据，应先在该 profile 内正常退出测试账号/关闭测试应用锁。
- `--headless` 只验收 HTTP/状态，不替代真实 Reader Compose 窗口验收；Test Mode 不提供屏幕截图。

## 测试分层

| 层级 | 位置 | 目标 |
|---|---|---|
| JVM 单元测试 | `app-desktop/src/test/kotlin` | 业务逻辑、导航契约、HTTP parser、状态模型 |
| 桌面自动化 API | `app-desktop/src/main/kotlin/mihon/desktop/test` | 测试模式 HTTP server 与状态回读 |
| Robot 客户端 | `test-desktop/src/main/kotlin` | 面向场景的测试 DSL |
| Robot 测试 | `test-desktop/src/test/kotlin` | 客户端序列化、Robot API、场景 smoke |

## 编写规则

- 禁止“成功或失败都算通过”的断言。
- HTTP/API 变更必须覆盖成功、空数据、错误状态和 malformed body。
- 导航变更必须验证 Tab 与 Screen 类型，以及 pending 状态不会互相覆盖。
- Reader API 必须验证 UI 状态与 `/test/reader/state` 一致。
- Test Mode 不读取屏幕像素；视觉问题使用不需要系统录屏权限的 Compose 离屏测试或人工检查。

## 常用命令

```bash
# 全部桌面 JVM 测试
./gradlew :app-desktop:jvmTest

# Robot 客户端测试
./gradlew :test-desktop:test

# 指定测试类
./gradlew :app-desktop:jvmTest --tests "mihon.desktop.test.navigation.TestNavigationControllerTest"

# 冒烟测试脚本
./scripts/desktop-smoke-test.sh
```

## 测试模式 API

基础地址：

```text
http://localhost:8080/test
```

高频端点：

- `GET /health`：测试服务健康检查
- `GET /state`：应用状态
- `POST /navigate/{screen}`：导航
- `POST /action/{action}`：执行动作
- `GET /reader/state`：阅读器状态
- `POST /reader/next_page`：下一页
- `POST /reader/prev_page`：上一页
- `POST /reader/close`：关闭阅读器
- `POST /reset`：重置测试状态

完整字段见 [API_REFERENCE.md](./API_REFERENCE.md)。

## 最终对齐固定 EXE Runner

本轮构建已产出固定的 Windows 未打包应用后，运行：

```bash
./scripts/desktop-final-parity-test.sh
```

Runner 固定使用 `app-desktop/tmp/mihon-dist/main/app/Mihon Desktop/Mihon Desktop.exe`，并要求 `./scripts/build-desktop.sh evidence` 生成的 Task151 provenance sidecar。启动前会复用既有 provenance verifier 同时核对当前已提交 product source identity 和完整未打包应用哈希；EXE、sidecar 缺失或任一身份不匹配都会 fail-closed，不以 mtime 猜测 freshness。有效产物默认以带真实窗口的 `--test-mode` 启动，必须运行在可创建 Compose 窗口的 Windows 图形桌面或 macOS Aqua 会话中；`--headless` 不会创建 Reader Compose、Navigator 或 ScreenModel 生命周期，只适合 HTTP 控制面测试，不能作为 `FIRST_PAGE_PRESENTED`、关闭弹栈或 production resource disposal 的最终证据。若 `/test/health` 在启动前已响应则拒绝覆盖旧实例，启动后还会同时确认 health 与本次 PID 存活，并在成功、失败或超时时关闭本次启动的精确进程。

Reader 场景依次执行 downloaded directory、downloaded CBZ、local archive、online 与 partial download 五条 production 路径，验证真实页面 I/O、decode、首帧请求上限，以及 `closeRequested → productionClosed` 的两阶段关闭。partial fixture 由 production `DesktopDownloadManager` 实际提交若干页后打开 Reader：已提交当前页必须恰好走一次 probe/open/copy 且图片网络为 0，附近预取不能越过已提交边界，所有下载 I/O 都不得发生在 manager/index/coordinator/lifecycle 锁内。场景主体失败后仍会请求关闭，但清理失败不得覆盖原始首帧、I/O 或 decode 失败；场景主体成功时，关闭未获 production 确认仍使验收失败。

`test-desktop` 客户端通过 `MIHON_FINAL_PARITY_SUMMARY_FILE` 写入汇总。Runner 将它与既有 `test-mode-coverage-inventory.json` 对比，逐项输出 family 和 permanent protection，并要求：

```text
Families: 13/13
Permanent protections: 5/5
Capabilities: 64/64 unmapped=0
```

产物错误会给出固定路径和 `evidence` 构建命令；启动错误会区分旧 health 占用、本次进程提前退出与超时，并给出进程或启动日志。provenance、health command 和 test command override 仅用于隔离 runner fixture，正常验收不得用它们替换真实 verifier、固定路径或默认 `test-desktop` client。


## Windows 默认自适应阅读模式

入口：设置 → 阅读器 → 默认；或漫画详情／阅读器设置 → 阅读模式 → 默认。
“跟随全局设置”单独表示继承全局方向与单双页设置；阅读器选择漫画模式不会改写全局阅读方向。
全新偏好采用默认模式，已保存的手动方向保持原值；仅保存过单双页的旧配置保留手动 RTL 布局。
显式保存的 DEFAULT 不受历史单双页值影响。Android 阅读器本轮不变。

默认模式复用现有 RTL 单页／双页 presentation，以实际阅读内容 viewport 的宽高比判断。
首次有效尺寸或主动选择默认时，比例 ≥ 1.35 为双页，否则单页；后续单页在 ≥ 1.35 进入双页，
双页在 ≤ 1.25 退出。跨阈值后的目标须连续保持 150 ms；同一目标的连续 resize 不重置计时，
回到当前布局区间则取消待切换。无效尺寸取消待切换并保持布局；离开自动模式、销毁阅读器时取消计时。
共享策略位于 domain 的 `AdaptiveReaderLayout`，当前只有 Desktop adapter 接入。
临时工具栏和弹窗属于 overlay，不改变内容 viewport；图片比例变化不触发自动布局。

设置面板显示“默认 · 单页／双页”及说明，并禁用手动双页开关。双页配对、横向跨页图和章节末尾
继续按现有 presentation 处理，因此双页布局不保证每个视口都显示两张图。
双页当前页统一取逻辑阅读顺序的第一张：RTL 为右页，LTR 为左页；封面唯一页仍在左槽，当前页为封面。
单页进入双页时定位包含原页的配对，并将当前页归一到该配对的第一张；返回单页显示归一后的当前页。
底栏“调整跨页”原子更新配对边界与定位目标，例如 [1, 2] → [2, 3] → [1, 2]，不会停在新增边界的单页。
封面不调整，已有横向跨页与末尾唯一页仍按 presentation 处理。自动模式切换保留强制单页配对设置。
布局调整的 settled 回调只更新显示状态，不因新增伴页推进阅读进度；当前位置归一与进度抑制分别处理。
单页下自动匹配清理、跨页图检测等元数据更新不清除待完成的布局保护。用户跳页、选中其他显示单元或翻到不包含保护页的视口后，
恢复正常进度上报。双页 reporter 在显式定位请求后重新确认已显示页，配对往返不依赖数字页索引变化。

Desktop 自动标记保存于 viewerFlags 第 34 位，低 8 位仍为 Android RTL 值 2；显式方向覆盖清除自动标记，
继承全局同时清除 Desktop 双页覆盖位。Android 尚未接入自动策略；跨端使用本轮数据时仍以 RTL 解释。

回归证据使用共享 `AdaptiveReaderLayoutContractTest`、Desktop `DefaultReaderModeTest`、
真实 `ReaderContent` 离屏挂载的 `AdaptiveReaderViewportTest`、真实阅读器与底栏挂载的
`DualPageCurrentPageWiringTest`（默认／手动 RTL／LTR、连续调整、封面、后续翻页进度），
以及 `ReaderPageTurnPresentationTest` 的动画与快速翻页回归、设置搜索和漫画详情 persistence 测试。
手工验收：选择默认，拖动内容比例跨过 1.35／1.25，确认方向、单双页、当前页与阅读进度；
打开／关闭设置和工具栏不切换；选择手动方向后拉伸窗口不再自动切换；重开漫画仍保留选择。


## Android 原生同步导航与隔离远端验收

使用官方 `scripts/build-android.py debug` 产物及独立 `.dev` 身份，安装仍用官方 `install` 命令。先核对设备和两个包的身份，不降级或覆盖设备上更高的正式版本。用户处理系统权限弹窗、解锁并置 Debug 于前台后，在**新装且没有旧 GitHub 授权凭据**的 Debug 实例运行：

```powershell
python scripts/android-sync-native-acceptance.py --serial <本次确认设备> --fresh-debug
```

`--fresh-debug` 是调用者核验前置，不是脚本清数据或判定账号的手段。MAIN 未连接不能证明不存在旧凭据；已有凭据时 BeginSetup 可能访问远端，所以不能默认在既有安装上重跑。普通只读检查使用 `--inspect`；正式包仅允许只读检查。工具路径限于入口、MAIN、设置、系统 Back、登录说明页、Back、关闭；不点击授权、密码或同步。输出只含固定导航语义和几何信息，不保留层级、文本、密码或设备标识。

每次输入前须重新确认锁屏、真实前台窗口、控件唯一性及启用状态。Huawei 等设备的系统权限倒计时窗口不是应用界面，必须停止并由用户处理。`uiautomator dump` 退出码 0 仍可能伴随 null-root ERROR；工具要求精确成功标记、随机新文件、大小上限和有效 XML，读后删除，禁止读取旧固定文件回退。临时窄屏/大字号验收必须先保存原 wm override、density、font_scale 与旋转配置，在 finally 恢复并逐项核对。已有 override 不代表本轮脚本设置，不得直接 reset 丢失用户配置。导航通过不能证明密码页布局通过。

真实远端验收应使用用户授权的新私有空仓库，禁止使用已有非空 `mihon-sync`。仅测试版本可选择固定前缀 `mihon-sync-acceptance-...` 的专用仓库：

```powershell
python scripts/build-android.py debug --sync-acceptance-repository mihon-sync-acceptance-<本次隔离目标>
```

构建记录必须包含实际目标及配置摘要，安装前用官方 `verify` 核对该候选。正式构建不允许此参数。Desktop 使用包含同一配置能力的候选，并同时传 `--test-mode --test-profile=<安全绝对目录> --test-sync-repository=<同一目标>`；旧 `--test-profile-dir` 不足以隔离凭据，不能用于启用覆盖。不得以旧候选支持新参数为假设。共享生产链只发现指定隔离库，已有连接或 pending 指向其他仓库时安全拒绝；不会替调用者清旧记录或改连接。切目标前通过正常产品路径处理本地连接，不伪造凭据或写内部状态。

CLI GitHub 登录态不代替应用 OAuth 或 GitHub App 授权；核对实际账号及隔离目标后才继续创建和跨端验收。浏览器自动操作被拒绝时保留拒绝并请求用户完成网页确认，不采集 cookie、不向应用注入 CLI token，也不换入口绕过拒绝。两端实际提交、模式 descriptor、重启恢复和解锁都须各自验证，MockWebServer 或导航成功不能代替真实远端证据。


当前限制：测试仓库覆盖配置不改业务页面的固定帮助文案，安装访问权限页仍可能写默认 `mihon-sync`。隔离验收以 candidate manifest 的 `syncAcceptanceRepository` 为目标，不按默认文案创建或改动既有仓库；测试负责人应向值守用户明确两个隔离名称。登录授权完成后仍须 GitHub App installation 获得测试库访问权，看到“App 已安装但无法看到仓库”后先通过正常权限管理页补充指定库，再回应用重新检查，不能据此判断仓库不存在或密码功能失败。


官方 API 路径须分开核验认证类型：安装列表接口的 403 不等于增加库接口也不可用。用户已授权增加测试库、公开 installation ID 来源可靠且精确库 ID/所有者/私有/admin 条件核对后，才可按 [GitHub add-repository 官方接口](https://docs.github.com/en/rest/apps/installations#add-a-repository-to-an-app-installation) 最小增加；403 时停止，不提取应用 token 或浏览器 cookie。API 成功仍要回真实应用重新检查目标可见性，接口请求不代替生产创建/同步验收。


安卓浏览器验收边界：Windows 启动浏览器的一次审批拒绝不能扩展为安卓浏览器不可操作。手机网页的 UI hierarchy 可能只暴露浏览器外壳、未暴露网页控件；这不足以判断页面为空、登录失败或不能操作。用户授权原生网页操作后，可先通过应用正常管理入口打开页面，核对未锁屏、浏览器前台、目标管理 URL 与无密码输入字段，再用平台外部截图检查实际页面；截图只作临时诊断，读后删除，不写入长期文档或提交。该路径不改变 Desktop Test Mode 禁止读取桌面屏幕像素的规则，不以截图代替生产业务验收。若网页显示 GitHub Confirm access / sudo 身份确认，停止输入，由用户在页面自行完成 GitHub Mobile、验证器或密码确认；不索取、读取、自动填写或记录秘密。用户确认后重新观测权限管理页，才可按已授权的精确测试目标勾选、保存并回真实应用重新检查。

网页仓库选择菜单打开后，自动滚动、软键盘和焦点可能使旧坐标失效；层级中的 focused=false 与实际页面焦点也可能不一致。本次搜索尝试进入了 GitHub 全局搜索，未修改授权，具体原因未证实。发现输入落点不符时立即停止、关闭误入页面并重新观察；目标完整名称已在菜单中可见时直接选择该行，避免不必要的搜索。保存前核对原授权与本次两个目标、保持 Only select repositories，保存后重新加载核对集合，再回应用真实重新检查。嵌套可点击节点触发保护停止时，重新观察确切按钮及其边界，不放宽整个页面的点击保护。

测试仓库与 Debug 包身份隔离不等于本地数据为空。远端创建前检查实际 Debug 书架；发现已有用户内容时，不清应用数据、不注入内部状态，也不以“测试库”名义擅自上传。保留数据，先完成不提交的模式切换、风险说明及按钮状态检查；当前内容上传或数据替换须有明确范围。长期证据只记录状态与合成目标名称，不记录用户书架内容或账号。

### 设备授权、输入与材料核对

2026-10-01 的正常 GitHub device 页包含八个独立单字符输入框。核对精确 HTTPS origin 与 `/login/device` 路径，按 `urlsplit` 解析 hostname、scheme 及无 userinfo；不通过文本子串推断来源。每个字符输入前重新观测字段及边界，完整组合在内存核对与本次应用授权码一致后再继续，确认实际产品授权页及最终成功，再回应用读状态。授权码、账号、链接和凭据不写长期记录。未获用户明确授权时，身份确认或密码输入交由用户完成；保存的网页登录态本身不是授权。用户明确授权使用浏览器保存的密码、默认账号和普通确认时，可以在精确 GitHub 来源和本次流程中使用浏览器原生自动填充与确认，不读取密码字段值、不提取密码、cookies 或 token。默认账号仍需在内存核对与本次测试目标所属账号一致；需要额外设备或身份验证且浏览器不能完成时，保留页面交给用户。

Huawei 安全输入法可能遮挡应用，而普通 `dumpsys input_method` 标志未报告键盘显示；本次 `screencap` 退出码 0 但输出 0 字节，也不能作为截图成功。应用 XML 的控件坐标与 `mCurrentFocus` 不能独自证明点击未落在键盘上。发现这种状态后停止按旧几何点击；本次通过真实 Tab 离开密码输入、核对确认行/提交按钮的实际 focused、enabled、checked，再用 Space/Enter 操作。它只证明这些原生键盘事件，不把硬件 Enter 自动等同于软键盘 IME Done。

密码验收使用专门生成的测试秘密，并在 Git 外以系统保护方式保留。创建时应在**最终提交前**核对当前实际字段与测试材料一致；之后发生编辑、指针点击或键盘事件，先前的值核对不能沿用。受授权的测试值只在内存比较，不输出字段值、不保存完整层级、不显示含明文的截图；用户登录秘密不得读取。原材料无法加入时保留失败与原 descriptor，先核对本次输入及材料，不删除、覆盖或降级空间。需要新的受控夹具时使用新隔离库，保留原库及原授权，重新验证整个创建/正确加入链。

窄屏/主题切换造成 Activity 重建时，面板关闭及未提交秘密清理与同一 Compose composition 内重绘保持是不同验证范围。记录实际生命周期，不以系统设置切换证明草稿应跨 Activity 保存。

### Android 模拟器续验的隔离边界

可将厂商输入法造成的自动化阻塞与共享密码能力分开：使用已有 SDK/system image 创建独立 AVD、独立 userdata，明确本次 serial，只安装统一入口已 verify 的 Debug 或正式候选，不复用其他 AVD 数据、不对实体设备发送模拟器操作。Debug 的测试目标覆盖不进入正式 APK；模拟器正式运行不能冒充实体升级后既有数据保留检查。仅配置本次模拟器，先读回代理键及显示、字号、旋转和主题基线，收尾恢复已知基线或精确清除本次引入键，并只关闭本次进程。未读取的初值不能声称已逐值恢复。

本轮仅设置 Android `http_proxy` 后，应用真实 OAuth 请求超时；加入 emulator 启动代理后取得 code，但后续 poll 失败；补充 Android global proxy host/port 后，实际 requestCode、poll、授权与仓库发现成功。这是不同配置下的运行观察，未取得代理握手/TLS/HTTP 分段诊断，不单独归因某个键，也不以 TCP/curl 或独立客户端代替应用成功。合成密码仅在内存比较；软件键盘 Done 必须实际执行，硬件 Enter 结果单独记录。

API 36 LatinIME 的实际 `mInputShown` 与 editor action 6 可以表明键盘已显示，而 UI hierarchy 仍没有键盘节点。必要时用平台外部临时截图定位真实 Done，并同时核对窗口、尺寸、字段几何和前台；不保留含秘密像素。显隐两态的 XML 仍可能都标记 `password=true`，不可据此否定切换；以真实 Show/Hide 语义和测试值的内存比较核对。最终字段值比较后直接执行已核定的软件 Done，若发生编辑或重新聚焦则重新比较。

已连接实例使用“更换空间”补验 UNLOCK 时，先核对当前生产动作是否只进入 discovery，而非断开或清凭据；正常确认和同目标发现仍不能当作正确密码已验证。返回 MAIN 显示“继续连接”可能只由未完成的 setupStep 控制，不能单凭文案判定旧绑定丢失；核对实际启用连接、目标和密码状态，再用正常同 profile 冷启动验证持久状态，不写内部状态消除文案。需要证明密码解锁时仍须真实提交、错误反馈以及正确提交后的准备/完成转换。启动过渡期前台检查失败时，等待该已启动实例稳定后重新观测，不重复 start 或清数据。

### Windows 原生输入的实际前台

正式 EXE 的只读 `/test/sync/ui` 可给出 AWT/Compose 焦点及本次 PID，但 AWT 的局部 `focused=true` 不能替代 Win32 `GetForegroundWindow` 的实际所属 PID。本次其他程序处于前台时，键盘工具在任何输入前拒绝，即使激活请求返回成功。每次事件重查实际前台、锁屏、实例身份和命中；测试窗口真正前台后才继续，不向其他程序发送测试秘密。缺少密码控件的白名单 tag 时，不能猜坐标或扩大 HTTP 接口来提交密码；使用外部原生可访问性类型、焦点与几何证据，缺失时保留该门槛。

主代理与子代理的工具执行上下文可能不同。本次子代理的 CIM/Python/GUI 调用被拒绝，而主代理在当前图形会话中可以执行；不能把一处拒绝扩大为整台机器不可用。由原平台代理保留场景和证据职责，主代理执行其已完整核对的外部工具即可，不因此增加代理或重复产品实现。执行前冻结脚本及依赖并核对哈希；交付给执行者后不得修改同一文件。确需修改时另交新版本，重新核对后再执行，避免读到的源码与实际输入工具不一致。

同一活动 AWT 窗口中的背景 Compose owner 可以保留局部 FOCUSED。密码页已聚焦时，本次公共树同时报告密码字段和背景工具栏按钮 focused；旧工具将全部公共 focused 节点都要求落在面板，因而在首次 Tab 前停止。局部标记不能独自证明背景获得键盘事件：结合当前面板公共祖先、白名单控件的 ownerFocused、真实字段焦点及完整正反 Tab 结果判断作用域。出现多个标记时先只读诊断，不改产品焦点或伪造观测；也不能直接删除作用域与反向回环验收。

默认 Material DragHandle 的实际公共焦点可能是 `groupbox` 父包装器。本次原角色摘要只列 password/push button，因此到达把手时再次误判没有面板焦点；原始有界公共树实际包含面板祖先内的 focused/focusable groupbox，宽度、中心及高度与把手匹配。按源码 `SyncHandleAccessibility` 的宽度/中心容差和本次面板祖先验证该节点，不猜角色、不扩大到所有 groupbox，也不仅凭 HTTP tag 判定把手获得焦点。

同步结果的可选字段须按当前 serializer 及实际响应核验。`/test/sync` 的 `lastExchange` 来自临时 notice，可能不存在，不能以默认空对象加 `uploaded>=1` 作为每次真实同步的强制判据。本次仅运行一次真实标未读与同步，旧工具因该假设等待超时；随后没有重新派发，而以同隔离 profile 的只读 production run store 确认 MANUAL/SUCCEEDED/COMPLETE、uploaded/confirmed 各 1、failed 0，再由另一端真实接收读回确认。读取数据库仅限已核定的隔离 fixture、`mode=ro` 和必要计数，不写 SQL、凭据或内部状态。验收工具超时与业务失败分开记录。

`/test/navigate/LibraryTab` 的 success/currentScreen 可以只表示请求的 Tab 已更新，不能证明外层漫画详情 Navigator 已 pop。本次仍挂载详情时 `/test/sync/ui` 为 ready=false；不按标签假称已经回书架，也不反复 POST 同一路由。用真实详情返回控件退出外层页面，再核对实际挂载的同步入口。HTTP 导航恢复不能计作原生返回、帮助或还焦证据。

日常跨端验收先核对协议支持的字段。当前 `SyncField` 包含收藏、关注、已读、阅读位置和阅读摘要；章节书签只更新本地数据库，bookmark-only 不追加同步操作。一次真实 Mac 标已读并加书签后，Android 收到已读而仍未加书签，符合现有协议边界。保留这次观察，不把“本地操作成功”当成“该字段支持同步”，也不为密码验收新增书签协议或把它报告为已修复的产品 bug。
