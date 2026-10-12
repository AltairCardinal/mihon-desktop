# Windows 作者页与 Android 对齐

日期：2026-10-12。基线：`49c25db9ba`，开始时工作区无未提交改动。用户要求 Windows 作者页的界面与功能对齐当前 Android。实现、focused 验证、独立审查和体验构建已完成；用户随后明确报告人工验收通过并要求合入主干，本批验收收口。主代理原生工具异常保留为历史事实，不改写为工具自动验收通过。此计划只跟踪本轮作者列表和详情；不修改历史计划状态，不触发完整发布矩阵或正式版本分配。

## 权威、复用和边界

- **SOURCE**：Android `ui/browse/author/AuthorsTab.kt`、`CreatorIdentityHeader.kt`、`CreatorSettingsDialog.kt` 为本轮界面和可操作路径基准。Desktop 对应页面继续使用现有 Voyager、ScreenModel、Injekt 与共享作者归档、身份编辑、设置、作品分组/筛选链路。
- **PROJECT_POLICY**：本次作者页封面明确采用 Android Book 的 2:3；不修改其他 Desktop 页面的 7:10。布局、字体层级、入口、列表/网格及来源选择行为按当前 Android 对齐。平台图片加载保留 Desktop adapter，必须保持自定义封面、来源请求、缓存版本和准确漫画导航。
- 共用作者 UI 尺寸放在现有双端已依赖的 `presentation-theme/commonMain`，Android 与 Desktop 同时消费当前 Android 的原值，避免日后分别修改常量。`presentation-core` 在当前仓库是 Android 专用，不能为本轮改造其构建机制。Android 接入是等值机械替换，补跑作者卡及归档共享契约的 focused 验证。
- 不新增 UI 模型、作者数据库、模糊归并或同步协议。不把 Android 未暴露的历史校正入口重新加入本轮。移除的“拆分作者身份”只是现有不可执行占位入口，不删除底层能力。
- Desktop 独有的索引进度、后台发现取消与失败反馈保留现有生产链路；这些反馈不改变共享作者、作品和来源的业务含义。窗口外壳及鼠标/键盘 adapter 不冒充 Android 系统 UI。
- Android 的 `cover_error.xml` layer-list 不能由 JVM 图片加载器直接消费。Desktop 作者图片 adapter 保留原灰色 `#1F888888`、120×120 intrinsic size 与中间 24×24 BrokenImage 图形，沿用 Crop；代表作/来源使用主题 extraSmall 圆角，作品卡内封面使用矩形。占位和错误通过实际绘制像素验证，真实封面通过 production Coil 与本地 HTTP 图像夹具验证。
- **HTML_ADAPTER**：本轮不制作 HTML 原型，也不以浏览器结果替代 Compose 或 Windows 原生验证。

## 执行预算

一个内聚功能批次：1 名实施子代理负责主要实现和红绿重构；主代理负责范围、独立验收、体验构建和提交。无并行 Gradle，主代理接收协调权后再构建。独立验收 1 轮，必要时限域修复复验 1 轮；仅受影响的 focused 行为、共享契约/集成、格式检查及一次 `preview`。完整模块/全量测试 0 次，正式构建 0 次。

预计 30–60 分钟，主要成本是 Kotlin 编译、挂载 UI 测试和体验打包。若发现数据迁移、安全或共享协议变更，或需要增加代理/审查/显著成本，先说明具体风险和替代方案再扩大。环境受阻时记录实际错误，继续不依赖该环境的验收；不得把未运行项标为通过。

## 唯一交付批次

- [x] WA-01：作者列表、详情及来源选择与当前 Android 对齐；真实 production UI 和既有链路回归通过，用户人工验收确认通过，必要文档及本批提交完成。

此勾选只表示本批承诺完成，不表示完整发布矩阵通过。

## 实现前固定验收

| ID | 真实操作与预期 | 验证层 |
|---|---|---|
| WA-L1 | 浏览 → 作者：搜索在关注/全部之前；作者卡标题、数量、别名、新作、关注状态及最多三部代表作，尺寸和文字层级与 Android 一致 | 挂载 production root UI；位置/尺寸与内容断言 |
| WA-L2 | 搜索、切换关注/全部、进入作者并返回：结果正确、滚动恢复、分页和重试保留 | 现有真实仓库/ScreenModel + root UI 回归 |
| WA-D1 | 作者详情：身份/别名后同排“关注/取消关注”和“检查新作”；显示方式菜单仍在顶栏；无不可执行拆分入口、多余总数及旧分区标题 | 挂载 detail UI；真实点击与确认/取消 |
| WA-D2 | 搜索作品或筛选图源：采用共享分组与筛选，作品数反馈和空状态与 Android 一致；来源窗口显示完整当前组 | 共享契约 + 真实归档/挂载 UI |
| WA-D3 | 列表、舒适网格、紧凑网格：136/112dp 自适应网格、2:3 作者封面；列表显示图源、网格隐藏图源；收藏16dp标记、新作和日期正确 | 真实布局/语义断言与深浅主题离屏图 |
| WA-D4 | 窄卡片日期：完整标签放不下时显示完整纯日期；标题、日期及标记不被裁切 | 小尺寸 Compose UI/离屏视觉 |
| WA-S1 | 点击作品 → 来源窗口 → 点击指定来源：进入准确漫画，成功导航后标记已查看；缺源、失败、取消和还焦保留 | 真实点击 → production effect/导航/持久化 wiring |
| WA-S2 | 自动聚合多源窗口：版本卡可点击，分开显示在卡外；打开作品期间分开显示禁用；取消无写入，分开显示及撤销保留 | 真实 mounted UI + 既有归档持久化回归 |
| WA-I1 | 别名添加、选择显示名、作者设置保存/取消：复用两端相同 editor、确认和反馈，不改变身份或设置存储 | 现有 identity/settings 集成回归；源码核对界面 |
| WA-P1 | 作者封面：使用当前漫画身份、自定义封面、sourceId和封面版本，保留认证请求及占位/失败处理 | production 图片请求链与现有封面集成测试 |
| WA-W1 | 使用本轮真实 preview EXE、全新隔离 profile 打开作者路径，观察并操作作者界面；仅关停本轮实例 | Windows 原生路径，实际工具错误独立记录 |

本轮 Windows 原生操作集中放在末尾，遵循 `docs/automation/TEST_GUIDE.md`。原生前完成实现、测试、审查、构建、夹具、工具文档读取与提交准备；原生后仅关停、短证据和已准备好的提交，再结束当前轮。

## 验证证据

preview 自带扩展 production runtime 验收不等于作者页原生验收，manifest 的 `nativeInteraction=NOT_RUN` 不回改；原生结果单列。本批跨双端作者 adapter、共享 UI 常量、图片 adapter、真实 UI/仓库测试及文档，超过 8 文件/400 行提示，但均服务同一作者浏览路径；主要风险为图片身份/缓存、异步状态隔离、来源打开和滚动/导航，使用对应 focused 集成测试核验。

- 红：`.gradle-coordinator/author-parity-red-focused.log`，6 个目标方法在旧实现因代表作尺寸、拆分占位、来源卡点击、检查入口、搜索顺序和网格宽度失败。首次整类尝试因测试等待循环无挂起点不能超时，保留 `author-parity-red-run.log`，只取消该次进程并给测试补挂起点；不作为产品失败或通过证据。
- 补红：`author-parity-leaf-red.log`，3 项分别证明日期不回退、封面未接漫画身份/自定义封面/缓存版本，以及扫描阻止已有作品导航。`author-parity-separate-red.log` 证明分开显示提交期间仍可重复操作。
- 独立验收：主代理核对 Android 来源、Desktop production UI 与真实截图，发现加载/失败占位、封面圆角/代表作间距、root 错误重试等残余差异。原实施者限域修复；`author-parity-review-red.log` 中真实占位像素和错误文字 inset 两项先失败。未加载的空白截图未记为图片通过，修正为有界等待 production Coil 真实蓝色样本像素。
- Desktop 最终功能/重构范围：`author-parity-refactor-check.log`，7 个作者类共 46 项通过，失败/错误/跳过均为 0；本次 8 个 Kotlin 文件的 3 模块定向 `spotlessKotlinCheck` 实际执行通过。Desktop 未应用仓库 lint 插件，使用仅本轮临时 Gradle init 接入既有 formatter 并限定目标；未改持久构建配置。中途配置失败/SKIPPED 不作格式证据。
- 主代理核对真实 XML：`AuthorsProductionWiringTest` 24、`AuthorCardProductionWiringTest` 3、`AuthorsScreenModelsTest` 5、`AuthorDetailBehaviorTest` 8、`AuthorFollowSyncWiringTest` 1、`CreatorFavoriteBadgeIconTest` 1、`AuthorParityUiContractTest` 4。最后两个共有错误 Text 按 Android 改回默认颜色，是纯视觉调整，补 `git diff --check` 与来源核对；不声称这两行调整之后再次执行了全部 46 项。
- Android：`python scripts/gradle-coordinator.py run --key author-parity-android -- gradlew.bat :app:testReleaseUnitTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorWorkCardUiTest --tests eu.kanade.tachiyomi.ui.browse.author.AndroidAuthorArchiveWiringTest --console=plain`。1 分 31 秒通过，共 16 项（作者卡 5、归档共享契约/接线 11），失败/错误/跳过均为 0。
- Windows：`python scripts/gradle-coordinator.py run --key author-parity-preview -- "C:\Program Files\Git\bin\bash.exe" scripts/build-desktop.sh preview` 通过，日志 `author-parity-preview.log`。构建版本 `0.11.19.91.49c25db`；production 扩展安装及运行时版本验收通过，正式版本未递增。实际文件已核验：[Mihon Desktop.exe](<D:/Codex/worktrees/c1b1/mihon/app-desktop/artifacts/preview/windows/20261012T044818Z-645f3656/Mihon-Desktop-0.11.19.91.49c25db-unpacked/Mihon Desktop.exe>)。
- preview 源码指纹 `488e9fd50a8a58142aebdfdcb96426f2347243d88472591ffed0e9be6fdd906e`，基线提交及本轮 production 差异记录在同级 `preview-manifest.json`。构建/扩展运行时 PASS，原生层保持该脚本的 NOT_RUN，不回改 manifest。
- 原生前只读预检：产物形状/身份 PASS，Windows 活动显示路径容量 1、输入桌面正常；这些不是截图或输入通过证据。使用全新隔离 profile `app-desktop/tmp/author-parity-20261012-native`，本轮 59371/59372 启动前空闲。先用该 profile 的 headless 发布运行时执行现有 `author_sync_fixture(add)`，只准备 GA06 虚构作者/别名；health 200，fixture 成功。真实 launcher/runtime 30068/49216 的精确 EXE、profile、父子和监听者已核对，`POST /test/shutdown` 返回 202，两进程自然退出。
- Windows 原生：GUI launcher/runtime 62640/31596，真实 EXE、父子、profile、HTTP 监听者及 health 200 已核对；Node REPL 成功导入 `@oai/sky`，窗口枚举唯一返回该候选（id 27395528，标题含精确版本）。首次 `get_window_state` 返回其他应用画面；一次有界激活/重观察后仍返回相同异常画面。随后只读核对请求/返回窗口及重新枚举，路径/title/id 仍是该候选。没有派发坐标点击、键入或其他业务输入；不能判断工具捕获与产品绘制哪一层导致异常，不能把窗口元数据、HTTP 或离屏图代签原生 PASS。WA-W1 为 NOT_RUN，后续须重新绑定并看到真实 Mihon 内容才能点击。
- 收尾：再次核对精确 PID/EXE/profile/监听者，仅对本轮本地 `/test/shutdown` 发一次 POST，返回 202，launcher/runtime 自然退出。停止后续输入/截图/自动激活，结束当前根会话作为本机 Computer Use 的释放边界；未操作普通用户实例。
- 用户人工验收：2026-10-12，用户明确回复“我验收过了，ok的，合入主干”。按此确认关闭本批 Windows 原生验收缺口；未提供逐步操作记录，不虚构具体点击、截图或主代理自动化 PASS。上文工具异常及 preview manifest 的原生 NOT_RUN 保留，用户确认单列为验收依据。
- 后续只读窗口探查核对了新的隔离实例（profile `app-desktop/tmp/author-parity-native-20261012-1449`，59373/59374，launcher/runtime 31596/59032）：窗口 visible、非最小化、非 cloaked；未进行页面点击，不将该元数据当作原生操作证据。合入前已确认该实例与监听者均退出，无需再次关停；未操作其他用户实例。
- 未运行完整模块测试、全量测试或正式发布矩阵。本批实现、测试及用户验收记录保持同一功能提交，由主干从原基线快进合入；hash 由最终回复报告，不另建纯状态提交。本批收口不表示完整发布矩阵或稳定发布就绪。
