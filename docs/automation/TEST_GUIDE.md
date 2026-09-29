# Mihon Desktop 自动化测试指南

需要反复查看 Android 与 Windows 的真实 Compose 界面时，参见[双端原生 UI 审阅流程](NATIVE_UI_REVIEW.md)。

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
