# Mihon 双端原生界面同步演示任务契约

- 当前状态：实现与独立验收通过，随本批次提交；本轮取代上一版独立展示台的视觉方案，需求草案仍待确认。
- active-task：无；本批次双端原型及退出阅读修复已验收。
- 用户要求：HTML demo 应当还原现有两端界面；不接受重新设计的展示网站。
- 模型：主模型负责现状取证和验收；GPT-5.6 Luna / xhigh 实现。用量统计由统一插件负责，不另行采集或报告。
- 保留：原演示真实调用的操作同步模型、已通过的 12 项模型测试。
- 不做：真实 Git、应用功能代码、发布构建、外部托管或完整后台服务。

## 文件所有权与流程

| 单元 | 输入及前置 | 文件所有权与步骤 | 交付 |
| --- | --- | --- | --- |
| Luna 双端还原 | 本文证据、需求草案、现有模型 | 仅 docs/prototypes/multi-device-sync/；相关行为先补失败测试，随后重写界面与样式、连接模型并自测 | 两种独立平台界面、必要资源、测试、README |
| 主模型取证与验收 | 已核实源码、可用发布产物及 Android 模拟器 | 只修改本文；并行获取隔离运行的原界面或离屏证据，主审 1 轮，必要修复复审 1 轮 | 实际 UI 对照与验证结论 |

原工作树中的 extension、source-api、roadmap 等改动不属于本任务，禁止回滚或提交。只更新现有契约，不额外创建计划/报告。运行证据位于工作区外的本任务可视化目录。HTML 仍可通过 file URL 离线打开，无框架安装与 CDN。

本批次虽然超过 400 行，但构成一个双端界面原型，入口、渲染和行为模型需要一起验收。主要风险是 CSS 与原生 Compose 的几何差异、平台导航被错误复用、界面按钮未接入模型。以实际浏览器 DOM、交互测试和截图对照控制这些风险；不因此拆成多个无法独立使用的文件任务。

## 已核实的界面权威

以下路径均相对于仓库根目录，是还原依据，不能凭常见 Mihon 截图猜测：

| 方面 | 权威源码 | 具体要求 |
| --- | --- | --- |
| Windows 窗口 | app-desktop/src/main/kotlin/mihon/desktop/Main.kt:245 | 系统窗口标题 Mihon Desktop + 版本，初始 1024×768；应用内容填满窗口，不能套营销页大卡片。 |
| Windows 导航 | app-desktop/src/main/kotlin/mihon/desktop/ui/home/HomeScreen.kt:196 | Material3 NavigationBar：书架、更新、历史、浏览、作者、更多，共六项；选中图标有 secondaryContainer 指示胶囊。 |
| Android 导航 | app/src/main/java/eu/kanade/tachiyomi/ui/home/HomeScreen.kt:74 | 手机底部：书架、更新、历史、浏览、更多，共五项。不要把 Desktop 作者项直接加到 Android 底栏。 |
| Android 作者入口 | app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt:78 | 浏览内页签：图源、作者、扩展、迁移；作者关注从作者列表/详情触发。 |
| Windows 浏览入口 | app-desktop/src/main/kotlin/mihon/desktop/ui/browse/BrowseTab.kt:396 | BrowseSectionTabs 只有图源、插件两项；作者从底栏进入，迁移从更多进入，不能复用 Android 的四项页签。 |
| Android 底栏 | presentation-core/src/main/java/tachiyomi/presentation/core/components/material/NavigationBar.kt | 内容高度 80dp，系统手势区另计。 |
| Windows 更新页 | app-desktop/src/main/kotlin/mihon/desktop/ui/updates/UpdatesTab.kt:171 | 紧凑标题“最近更新”，titleMedium 16sp；筛选、日历、全部已读、刷新图标；水平16/垂直4内边距；列表水平12/垂直4，行间4。 |
| Windows 更新条目 | 同文件 UpdateItem，约448行 | M3 Card，内部8dp，间距12dp，封面48×68、圆角4；titleSmall14、章节bodySmall12、扫描组/时间labelSmall11；下载与已读图标。 |
| Android 更新页 | app/src/main/java/eu/kanade/presentation/updates/UpdatesScreen.kt | 标题“更新”，M3 TopAppBar；筛选、日历、刷新（不加 Desktop 的全部已读图标）。 |
| Android 更新条目 | app/src/main/java/eu/kanade/presentation/updates/UpdatesUiItem.kt | 上次更新提示、日期分组、56dp行高、水平16dp、44×44封面（行内上下6dp）；标题14、章节12、未读点及下载指示；不套 Desktop 卡片。 |
| 原生工具栏 | app/src/main/java/eu/kanade/presentation/components/AppBar.kt | M3 TopAppBar；返回/动作图标、标题和弹出菜单按现有页面使用。 |
| Windows 书架/漫画 | app-desktop/src/main/kotlin/mihon/desktop/ui/library/LibraryTab.kt、MangaDetailScreen.kt、MangaDetailComponents.kt | 从书架进入漫画详情，详情收藏/取消和继续阅读真实改变演示模型。 |
| Windows 作者 | app-desktop/src/main/kotlin/mihon/desktop/ui/authors/AuthorsTab.kt | 标题、搜索框、已关注与其他作者列表、作者详情及关注入口；按实际源码取结构。 |
| 默认主题 | presentation-theme/src/commonMain/kotlin/eu/kanade/presentation/theme/colorscheme/TachiyomiColorScheme.kt | 采用固定默认主题而非臆造紫色。浅色 primary #0058CA、surface #FEFBFF、onSurface #1B1B1F、secondaryContainer #D9E2FF、surfaceContainer #F3EDF7。深色 primary #B0C6FF、surface #1B1B1F、onSurface #E3E2E6、secondaryContainer #00429B、surfaceContainer #211F26。其余颜色直接参照源码。 |
| 主题边界 | DesktopTheme.kt、TachiyomiTheme.kt、BaseColorScheme.kt | Desktop MaterialTheme，Android MaterialExpressiveTheme；本原型使用默认浅/深色，不宣称覆盖 Android 随壁纸变化的 Monet 或所有用户自定义主题。 |

平台图标从已有矢量资源或对应 Material 图标路径复用，不能用字符、emoji 或随意画的象形符号替代。必要时复制仓库中的应用图标作为本地资源，记录来源。字体优先平台可用字体，不能加载远端字体；Android 字体的可用参考由主模型继续核对。

## 产品与演示交互范围

- 顶层仅保留很小的应用外预览工具条：Windows / Android、浅色 / 深色、演示控制入口、重置。设备切换与平台切换对应：手机 A 是 Android，电脑 B 是 Windows。
- Windows 以原应用窗口呈现；Android 在大屏显示手机视口，手机宽度下填充可用宽度。不要把“手机模式”仅做成 Desktop 页面缩窄。
- 默认打开 Windows 的更新 → 同步，已有待确认示例。原更新页可切换对照，不擅自删掉现有筛选、日期、下载入口等明显特征。
- 同步是新增拟议界面：采用平台原有标题栏、页签、列表、按钮、AlertDialog/Snackbar 的语言，禁止上一版宣传标题、四宫格统计卡片、面包屑和设备实验台占据产品页面。
- 同步相关路径需真正可点击：书架/漫画详情收藏、章节或简化阅读器记录位置、作者列表/详情关注、更新/同步处理取消与失败。同步模型复用，不重新实现操作系统。
- 历史、浏览、更多的导航不可是死按钮或只弹“占位”提示；展示与源码相符的页面结构和样本项目。无真实网络的动作明确给出离线样本边界，不虚报下载或授权成功。
- 关闭、最小化等系统窗口控件若作为演示外壳显示，应明确是窗口示意或提供在原型内可恢复的效果，不能关闭用户浏览器。
- 三种同步入口、设备模式独立、离线待发送、取消确认/忽略与冲突继续使用已有行为；模拟启动/周期在收起的应用外控制中触发。真实产品同步页保留手动同步按钮与设置入口。
- 新增同步页没有现成原应用截图；不得把设计稿称为现有产品。完整还原指本轮相关页面的现有壳层、组件与导航，真实服务、全部设置页业务和阅读图片内容仍为模拟边界。

## 冻结验收

| ID | 操作 / 输入 | 可观察预期与验证方式 | 状态 |
| --- | --- | --- | --- |
| C1 | 1440×1000 浏览器下选择 Windows | 独立窗口外壳；六项原有底栏，图标/选中胶囊/尺寸/标题与源码及可用运行证据对照 | 已验收 |
| C2 | 选择 Android，390×844 与大屏预览 | 手机状态栏、AppBar、80dp五项底栏、手势区；浏览内有作者入口；不出现 Desktop 独立作者底栏 | 已验收 |
| C3 | 两端进入更新页并切浅/深色 | Desktop 卡片与48×68封面；Android56dp行与44px方封面；标题/动作/日期结构正确；颜色与默认主题相符；实际截图目视和DOM尺寸验证 | 已验收 |
| C4 | 从书架进入漫画收藏/阅读；从正确作者入口关注 | 同用模型状态改变；其他底栏展示真实结构的对应页面；不能依赖实验台才能操作 | 已验收 |
| C5 | 更新/同步切页，执行手动同步和设置 | 平级页签；新增同步UI与平台组件一致；无展示网站式装饰或产品内实验台；截图/实际点击 | 已验收 |
| C6 | 取消漫画或作者→来源端同步→接收端同步→确认/忽略→再次同步 | 保留接收确认规则、忽略不回传和去重；UI与模型测试 | 已验收 |
| C7 | 不同设备模式与阅读位置、自动开关、离线操作及重试 | 原12项模型测试保持通过；新界面按钮真实调用它们；执行中不阻断切页或切平台 | 已验收 |
| C8 | 正常打开与展开演示控制 | 预览工具条在应用外；真实产品首屏不暴露操作模拟控制；收起后不占内容区 | 已验收 |
| C9 | 鼠标、Tab、Enter、Escape、窄屏与弹窗 | 可达、可读、焦点可见、对话框可关闭；无裁掉的主操作或内容横向溢出 | 已验收 |
| C10 | 执行相关测试、检查源码与README | 新行为先正确失败再通过；真实浏览器UI集成验证；引用双端源码与模拟边界，拒绝仅扫描源码字符串代替行为测试 | 已验收 |

主模型验收前子代理结果只记 self-tested。最终只运行一次演示完整测试；修正后只复验失败及受影响项。缺少原运行截图时如实记录，不能凭一个高层“像原版”的描述判定完全一致。

## 七行 GOAL

```text
结果：将现有同步HTML演示重做为忠实还原仓库Windows Desktop和Android手机界面的双端原型，在真实应用布局与操作路径中展示新增同步能力。
证据与上下文：D:/Shell/Github/mihon/docs/prototypes/multi-device-sync-plan.md为本轮冻结契约，包含已核实的双端Home、Updates、Theme源码证据；原演示提交2d1c3c563的独立展示台不符合用户要求。
范围：仅修改docs/prototypes/multi-device-sync/中的演示、测试、说明及必要本地资源；复用现有同步模型；Windows六项底部导航、Android五项底部导航及浏览内作者入口，补齐同步相关漫画与作者操作页面。
约束与授权：用户授权本地实现及测试，不接真实Git、不发布、不改产品代码或其他用户改动；中文UTF-8；无外网依赖；行为改变先红后绿；不新增子代理、不提交、不采集或报告用量。
完成标准：(C1) Windows外壳与六项导航准确；(C2) Android外壳与五项导航及浏览作者入口准确；(C3) 双端更新列表的标题栏、图标、尺寸与默认浅深主题符合源码；(C4) 收藏阅读关注从对应产品页面触发且所有导航可用；(C5) 更新与同步为平级页签，同步页面沿用各端原生组件语言；(C6) 收藏与作者取消确认、忽略和去重保留；(C7) 阅读位置与设备模式独立，三种触发和离线重试保留；(C8) 演示控制收于应用外，不污染真实界面；(C9) 桌面与手机尺寸键盘和弹窗交互可用；(C10) 行为测试执行界面同用实现并交付源码对照、真实验证与边界说明。
正当阻塞项：仅不可替代的运行证据或工具缺失；主模型并行获取原应用参考，缺少截图时标明验证边界，不能把近似当完全一致；goal隔离失败时报告但可继续授权实现。
最终交付：双端HTML原型及本地资源、测试和README；逐条C项回执、goal实际子线程及objective原文或哈希，主模型独立对照验收与提交。
```

## 执行记录

### 原应用运行证据

- Windows：运行已有正式产物 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.33.892a006-unpacked/Mihon Desktop.exe`。使用独立 APPDATA/LOCALAPPDATA 和临时内存 PreferencesFactory，避免 Java Preferences 访问用户注册表偏好。Test Mode 仅用于导航和健康检查；截图由应用外工具核对 PID、EXE 与窗口句柄后通过 PrintWindow 获取，没有调用不存在的截图 API。
- Windows 更新页参考：本任务可视化目录的 `native-reference/windows-updates.png`，1536×1152 物理像素，对应 Windows 150% 缩放下的 1024×768 窗口。默认深色，中文，六项底栏；空列表不出现“全部已读”图标。该产物只作为现有界面参考，不是本轮新构建。
- Android：只读启动 `mihon-api36-task20` AVD，目标 `emulator-5560`，无快照写入。使用已安装的 `app.mihon.dev`；临时设为中文、DEFAULT 浅色、关闭 AMOLED，获取 `native-reference/android-updates-light.png`（1080×2400，Pixel 7）。空更新页的系统栏、64dp AppBar、五项80dp底栏与手势区已目视核对。
- Android 已安装 APK 的浏览页仍是旧三页签，没有当前源码新增的作者页签；因此作者导航按当前源码复核，不把旧 APK 当作该入口的最终权威。未重建 Android 应用。
- 原应用参考均是隔离空数据页面；填充列表的封面、行高、卡片及详情结构继续以当前源码核对。HTML 的默认固定主题不覆盖 Monet/AMOLED 或系统字体渲染差异。

### 主模型第一轮验收

- 完整演示测试运行一次：`node --test docs/prototypes/multi-device-sync/sync-model.test.cjs docs/prototypes/multi-device-sync/ui-view.test.cjs docs/prototypes/multi-device-sync/ui-browser.test.cjs`，显式指定本机已缓存的 Playwright；17 项通过，0 项跳过。`git diff --check` 通过。没有运行 Gradle：本批次未改变 Kotlin 产品或发布产物。
- 独立浏览器链路通过：1024×768 Windows 窗口与六项底栏、接收端确认及重复交换、忽略仅保留本端、作者取消与新增关注跨端传播、同步时切页、离线保留与重试。
- C3/C9 未通过：Android 更新行实际70px而非56px；开路径图标被全部填充导致黑块/点/横线；窄屏控制条遮挡手机状态栏。详情的系统手势区和部分平台页面结构仍需修正。
- C4 发现：书架绘制全部样本目录，未按当前设备收藏过滤，取消确认后的可见书架结果不正确；更多页和详情结构仍有原生差异。已与上述视觉项合并为唯一修复复审批次。
- 阅读链路最初有一条主模型验证脚本误把 `pendingOutgoing` 当作操作对象数组；源码中该字段是 ID 数组。已修正验证脚本，此项不作为产品故障报告。
- 中文资源复核：`label_extensions` 为“插件”，`pref_incognito_mode` 为“无痕模式”。冻结契约上文“扩展/隐身”是先前的概念称谓，实际界面必须使用资源中的名称。

### Luna 回执与修复复审

- Luna 子目标线程为 `01a09500-95fc-7d41-b24e-cc5897cb2f3e`，与主线程不同，回执状态为 complete。首份回执中的 SHA-256 与主模型计算不一致；经只读 follow-up 返回完整 objective，原文与本文七行契约匹配。以主模型 UTF-8/LF、无尾换行计算的 `17915ebe911c940aef4e6f090b2614b78ff79975db62b59db09ffa4463b2c598` 为核对值，不采用首份错误哈希。
- 子代理确认红测命令为 `node --test docs/prototypes/multi-device-sync/ui-browser.test.cjs`；初始因 Windows 外壳没有固定尺寸、底栏被内容撑出视口而失败，修正后通过。主模型检查实际测试执行 DOM，并非扫描源码文字代替行为测试。
- 修复后只重跑相关视图与浏览器测试：5 项通过、0 项跳过。同步模型文件未改，不重复运行模型全量测试。
- 独立 UI 复验 8 条链路中 7 条通过：双端几何、确认去重、忽略本端保留、作者跨端关注与取消确认、异步切页/离线重试、启动和定期按钮及开关。截图已核对：Windows 1024×768、Android 更新行56px/封面44×44/底栏80px，控制条不遮挡状态栏；窗口使用实际本地应用图标。
- **前轮未通过 C7/C9（已由下述追加修复解决）**：阅读模式切换后按 Escape，或在阅读器中切换平台，旧设备仍可保持 `readingActive=true`。已证实两处调用向三参数 `setReadingActive(state, deviceId, active)` 传入了四个参数，第三个漫画 ID 被当作 true；同时键盘监听在重绘根节点内，重绘失焦后 Escape 的事件可到达根节点之外。
- 上轮浏览器测试覆盖了详情进入/返回，未覆盖“切换模式→失焦→Escape”与“阅读中切换平台”的组合。主模型最后复验发现此遗漏，因此不能把子目标完成或17项测试通过作为整体验收通过。
- 已向用户申请追加一次仅针对上述退出阅读状态的修复及复验，预计5–8分钟；在得到明确答复前冻结实现文件，不追加代理，不重跑全量测试，不提交未达标实现。当前可打开 HTML 预览，但不是最终验收版。

上一版独立展示台验收不计入本轮原生界面还原证据；本轮也不宣称 HTML 与 Compose 的字体栅格化逐像素一致。

### 用户批准的退出阅读修复

用户澄清问题属于 Demo 实现错误后，明确回复“修正”，批准追加一次定向修复及复验。沿用同一个 Luna / xhigh 子代理；只写 app.js 与 ui-browser.test.cjs，必要时补 README；主模型只更新本文并独立验证。无新代理、无全量测试、无新增过程报告。

验收为：双端改模式失焦后 Escape 正常退出；阅读中双向切换平台清除旧设备阅读状态；返回/Enter入口和退出后的远端进度接收正常，阅读模式独立。新测试先红后绿，主模型只复验这些关联行为。预计5–8分钟，完成后与前轮已通过的UI结果一起收口提交。

本次修复七行目标：

```text
结果：修复双端同步HTML Demo退出阅读后仍保持正在阅读状态的问题，使Escape、返回及切换平台正确结束旧设备的阅读会话。
证据与上下文：D:/Shell/Github/mihon/docs/prototypes/multi-device-sync-plan.md记录前轮验收；app.js的switchPlatform及Escape分支误向三参数setReadingActive传四个参数，界面重绘失焦后根节点键盘监听还会漏收Escape；用户已明确回复修正并批准本次追加。
范围：仅修改docs/prototypes/multi-device-sync/app.js与ui-browser.test.cjs，必要时补现有README；复用既有同步模型和当前双端外观，主模型负责计划记录和提交。
约束与授权：用户授权本人创建并执行本修复goal；中文UTF-8，先红后绿；不修改同步规则、模型文件、样式或正式产品代码；不启动新代理、不联网、不提交、不统计用量；仅追加一次定向修复与复验。
完成标准：(C1) 双端切换阅读模式并失焦后按Escape，阅读器退出且原设备readingActive为false；(C2) 阅读中从Windows切Android及反向切换都清除旧设备状态且不把新设备设为阅读中；(C3) 返回按钮与Enter进入详情仍正常，退出后接收远端阅读位置正常应用且各设备阅读模式保持独立；(C4) 新增真实浏览器测试先因正确原因失败再通过，提交精简红绿命令及状态回执供主模型独立复验。
正当阻塞项：仅工具或goal线程隔离缺失；不得把既有样式差异或不相关未提交文件纳入修复；发生缺失先报告并继续可独立推进的已授权实现。
最终交付：限定文件的最小修复及真实DOM回归测试、实际子目标threadId和完整objective原文、红绿证据与结构化回执；主模型完成追加验收并仅提交本任务文件。
```


### 退出阅读修复验收结果

- 新增 DOM 红测：`node --test --test-name-pattern="退出阅读" docs/prototypes/multi-device-sync/ui-browser.test.cjs`，exit 1，明确因改模式失焦后 Escape 没有退出阅读器（`.reader-route` 仍为1）失败；最小修复后同命令 exit 0。
- 只调整 `app.js` 的两处退出状态调用，新增窗口级 Escape 处理；原有根节点 Enter 处理和返回按钮保留。同步模型、样式、正式产品代码没有变化。
- 主模型检查实际代码后运行相关浏览器文件：3项通过、0项跳过。明确使用 `PLAYWRIGHT_CORE_PATH=C:/Users/feeli/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright-core` 与本机 Chrome，未依赖跳过结果。
- 主模型另以6个全新浏览器页面分别执行 Windows/Android × Escape/返回按钮/切换平台。所有场景均先改模式，验证重绘后焦点实际为 BODY，退出后旧设备 `readingActive=false`、阅读器消失，另一设备也未被误设为阅读中。
- 六个场景继续通过真实页面读取远端下一页并手动同步，再切回接收端。Windows接收第19页、Android接收第2页，精确匹配发送端；未产生多余的远端位置暂存提示，重新打开阅读器显示正确页数，各设备阅读模式保持独立。6/6通过，浏览器无脚本错误。
- 上轮7条通过的链路加上本次阅读退出复验覆盖全部既定交互；没有重复模型全量测试。`git diff --check` 通过。无需 Gradle/发布构建：本次交付仍为本地 HTML Demo。
- 本轮实现由同一个 GPT-5.6 Luna / xhigh 子代理完成，主模型独立验收；未新增代理、过程报告或真实 Git 同步实现。

- 修复子目标仍位于独立线程 `01a09500-95fc-7d41-b24e-cc5897cb2f3e`，状态complete。回执末行曾转述为“状态回执”；只读核对工具原文为下发的“结构化回执”，七行目标原文一致，主模型计算SHA-256为 `d628a36060f27f4182b784dba02db3acb5b3c74effa71b060908ad11a333d584`。
