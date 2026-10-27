# Desktop 自动化、原生验收与桌面归还

本页是[根规则](../../AGENTS.md)按动作加载的项目约束。仅阅读本次动作适用章节；代码命令与普通路径以仓库根为基准，Markdown 链接按本页位置解析。

准备 Desktop E2E/Test Mode、跨会话流程移交、macOS 验收或会占用桌面的原生/Computer Use 操作与故障诊断前使用。沿用下文既有 automation 文档及统一入口，不另建验收系统。

## 桌面端自动化测试

Mihon Desktop 包含完整 E2E 自动化测试系统。

### 快速命令

跨会话的流程迁移只接入规则、工具和必要验证，不自动启动目标会话、产品开发或原生验收。目标会话的继续开发由用户在原应用会话中启动；本次流程移交不得通过 `codex exec resume` 代启动。CLI 的 Git 或桌面隔离失败不能外推成原应用会话也不可用。

```bash
# 当前变更的定向测试；替换为实际受影响的测试类
python scripts/gradle-coordinator.py run --key desktop-focused -- ./gradlew :app-desktop:jvmTest --tests "<测试类>"

# 日常体验构建；不隐含完整测试或正式版本分配
python scripts/gradle-coordinator.py run --key desktop-preview -- bash scripts/build-desktop.sh preview
```

完整模块命令仅在显式里程碑或已批准的具体扩大验证中使用。Windows 的 Bash 应取本机 Git Bash 实际路径，不能把 WindowsApps/WSL 启动器当成本机 Bash。

### macOS 验收经验入口

执行 macOS 正式应用、安全存储、窗口或原生交互验收前，必须阅读
[macOS 验收经验与维护约束](../../docs/automation/MACOS_ACCEPTANCE.md)，并核对本次环境与产物。
Mac 原生验收统一从 `python3 scripts/mac-acceptance.py` 进入；它在 Mac 上运行，可通过已有 SSH 入口调用，负责有界亮屏复核、LaunchServices 启动、精确实例检查、原生场景和隔离关停。新场景复用 `mac_acceptance_session.py` 的会话保护，不另写锁屏推断或绕过它。只读 `acceptance-preflight.py` 的 `NOT_RUN` 不是最终原生验收结论，应继续执行已授权的统一入口；不得因 SSH、息屏或锁定字段缺失直接要求用户解锁。只有恢复后的具体失败证据才能报告对应阻塞。
统一入口或新规则在当前分支缺失时，核对这两个文件及经验文档的 Git 历史并定向整合已验证版本；不得把旧文档中的历史路径/PID当成本次默认值，也不得在没有验证的情况下重建一套临时验收脚本。此次修正规则、共享保护、执行入口和回归须随同一提交传播。
统一入口已在 macOS 14.8.4 经 SSH 使用两个全新 profile 完成真实原生场景及精确关停，其中一次从自然息屏恢复，无需人工解锁。适用范围与证据见 Mac 指南的“当前状态”；后续任务仍须核对本轮候选能力，复用该入口及共享保护，不能把此试点当成其他功能或平台已验收。场景接口缺失、未使用的 JMX 监听缺失，不得解释为锁屏或 SSH 无法操作 GUI。
采用该文档经验的 Agent 若发现新经验、适用边界变化或旧结论失效，
必须在本次任务收口或交接前按文档约束更新，不得只留在聊天或临时日志中。

### 测试文档

- 用户指南：`docs/automation/TEST_GUIDE.md`
- API 参考：`docs/automation/API_REFERENCE.md`
- 进度追踪：`docs/automation/TASK_TRACKER.md`

### 关键文件

| 文件 | 说明 |
|---|---|
| `app-desktop/src/main/kotlin/mihon/desktop/test/` | 测试基础设施：TestMode、TestState、HTTP Server |
| `test-desktop/src/main/kotlin/mihon/test/desktop/` | 测试客户端库：Robot 模式、HTTP 客户端 |
| `app-desktop/src/test/kotlin/mihon/desktop/smoke/` | 冒烟测试套件 |

### 测试模式启动

Windows 使用本轮日志 `Final unpacked EXE:` 的实际路径，先核对空闲端口和独立 profile：

```powershell
& "<本轮 Final unpacked EXE 的绝对路径>" --test-mode --test-profile="<本轮独立目录>" --test-http-port=49163 --test-jmx-port=49164
```

macOS 本机或通过 `ssh mbp` / `ssh mbp-lan`，按下列经验入口通过 LaunchServices 启动本轮精确应用包：

```bash
open -n -W -a "<本轮 Final macOS app 的绝对路径>" --args --test-mode --test-profile="<本轮独立目录>" --test-http-port=49163 --test-jmx-port=49164
```

纯 HTTP 验证可加 `--headless`；原生输入和窗口验收必须保留图形界面。占位符须替换为本次已核对的路径和端口，不能照搬历史安装目录或连接旧实例。

测试模式提供 HTTP API：

- `GET /test/state`：获取应用状态
- `POST /test/navigate/{screen}`：导航
- `POST /test/action/{action}`：执行动作

Test Mode 自身不提供桌面截图 API，也不得通过 Test Mode 接口读取桌面屏幕像素；视觉验证可使用不需要系统录屏权限的离屏测试。经授权的外部 Computer Use 属于独立工具链，可按下述工具规则观察精确隔离候选窗口。

外部 Computer Use 与 Test Mode 是不同的验收能力。只有任务已授权且当前工具规则允许时，才可用外部工具观察本次精确隔离的候选窗口。先查看当前会话的技能/插件说明，并在实际 Computer Use Node REPL 中对 `@oai/sky` 做只读健康探测；不能仅凭当前 shell 无法导入、没有独立 `computer` 工具名或旧 `cua_repl` 不可用就认定该工具不可用。若导入和只读窗口枚举健康，使用同一已加载插件的文档：提供 `sky.documentation` 时可调用；该方法缺失时直接读取该插件目录下 `docs/guidance.md` 与小写的 `docs/api.md`。缺少文档方法表示接口差异，不表示 Computer Use 不可用。记录实际探测证据和影响；某个平台受阻时保留其缺口并继续独立可完成项。

**Computer Use 集中执行与及时释放**：按 [集中操作与归还桌面](../../docs/automation/TEST_GUIDE.md#computer-use-集中操作与归还桌面) 执行，适用于 Windows、本机操作远程 Mac 窗口及模拟器等会占用用户桌面的原生操作。

- 先完成代码、构建、测试数据、可离线完成的预检与审查，并准备报告和提交内容，将当前授权范围内已就绪、可连续执行的原生验收项集中安排在本轮末尾；不为凑批次增加测试、等待整个 roadmap 或破坏场景隔离。
- 开始前简短告知操作范围和预计占用时间；同一桌面只允许一个执行者派发输入。集中执行仍须逐动作观察，沿用最新目标、截图和焦点保护，不批量盲发输入。
- 前台操作期间只做必要交互及即时结果核对。需要修改代码、构建、长等待、请求用户输入或深入分析时，先按指南收尾并归还桌面；当前 Windows 版本须随即用 `final` 结束本轮，后续工作留到下一轮。用户接管或停止 Computer Use 时立即停止输入，不抢回焦点。
- 成功、失败或中断均须收尾：停止本批后续输入、截图及自动激活，精确关停本次测试实例及自建辅助进程；用户要求留下的体验应用可保持打开，但不得继续控制。**本机对照已确认 `node_repl.js_reset` 后控制提示仍保留，直到根会话最终回复出现才消失；reset 只能作为可选的执行端清理，不能作为主动释放桌面的办法。** 不调用私有协议，不全局杀进程，也不假定子代理结束能释放根会话控制。
- 最后一次原生观察后只做必要关停、简短证据落盘与已准备好的本批提交，立即输出 `final`；不要在这一阶段继续编译、查资料、写长报告或等待代理。报告说明实际完成项及未完成项，不把“停止输入”宣称为“桌面已释放”。再次进入 Computer Use 时重新告知并核对目标。

Windows 输入桌面、活动显示拓扑与实际捕获/输入须分层核对。`display-topology` 的 `NO_ACTIVE_DISPLAY_PATH` 不代表锁屏或未安装 Computer Use；正常输入桌面及正的路径容量也不证明可捕获。实际捕获失败时按 [Test Guide](../../docs/automation/TEST_GUIDE.md) 做一次有界恢复和具体诊断，保留错误码；显示环境没有改变时，不重复捕获、重建产品或扩大测试来替代缺失的显示输出。不得仅凭 `WinDisc` 名称或历史渲染错误判定原因。

Windows 条件恢复后按 [原生小场景操作顺序](../../docs/automation/TEST_GUIDE.md#windows-原生小场景与正常关停) 使用全新 profile 续验。一次输入后若截图仍为动画过渡帧，按指南最多补两次观察，仍不稳定则记未确认，不重复派发输入；原生结果单独记录，不回改 preview manifest 的 `nativeInteraction=NOT_RUN`。

macOS 验收中，SSH 连通性、显示器息屏、图形会话锁屏和 Keychain 可用性是独立观察项，不以其中一项推断其他项。锁屏时停止原生输入，等待用户解锁后重新核对；不代改系统权限或锁屏设置。Keychain 以本次正式应用实际读写为准。

`scripts/acceptance-preflight.py` 只读检查精确候选、设备或会话，不启动应用、不安装、不发送输入。状态 `PASS`、`ENV_BLOCKED`、`TOOL_FAIL`、`NOT_RUN` 表示预检本身的结果；`PRODUCT_FAIL` 只用于真实产品行为断言失败，不能由环境/工具前置检查代报。每个平台、原生输入和视觉层分别记录；静态候选检查不证明签名、完整 provenance 或产品行为，未实际执行的层级保持 `NOT_RUN`。

详见 `docs/automation/TEST_GUIDE.md`。
