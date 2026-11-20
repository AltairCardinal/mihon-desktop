# 审定详情按钮原型的原生实现证据

本次按 `docs/prototypes/manga-detail-actions/` 修订10落地 Android / Desktop，不表示用户已经验收。
源码基线为本地 main `1b4ff9fe59c5dd147c6d5cd4a64f838a1518d15a`，在独立工作区整合旧功能提交及原型交互。
主干含数据库迁移46，保留现有阅读完成捕获、资源释放和关闭阅读器后清理边界，不恢复旧分支立即删除逻辑。

## 失败与最小修正

- 原型工作区 `detail-prototype-red`：真实共享规则不允许收藏保留所选位置；修正成员规范化。
- 原型工作区 `detail-ui-red`：真实 Desktop 指针拖动仍替换业务行；改为滚轮分页及固定单槽入口。
- 真实 Compose 回归发现业务 Row 被视口约束压缩及入口点击宽度不同；改为无界内容宽和相等槽位。
- 当前主干工作区 `detail-main-deletion-red`：真实阅读器关闭矩阵中，作品开启不能覆盖全局关闭；
  仅在原完成计划入口解析作品模式，保留后续主干安全过滤。
- `detail-narrow-red`：320dp / 200% 字号时定制选项固定两列，真实选项宽度断言失败；
  Desktop 改为随宽度及字号自适应网格，Android 单列不变。
- 首次主干定向运行中 Desktop 测试编译调用已移除的旧 helper；测试改为核验真实共享偏好接线，未恢复旧 API。

## 最终定向证据

本轮日志位于自有工作区 `.gradle-coordinator/`，XML 位于对应模块 `build/test-results/`。
以未受后续改动影响的范围组合，不声称最终源码全仓或完整模块通过：

| 日志键 | 真实范围 | 结果 |
| --- | --- | --- |
| detail-focused-green | Domain 契约4；Android 详情交互5、真实 ReaderViewModel 完成接线2 | 11通过；整轮因旧 Desktop 测试编译引用失败，不能称整轮绿 |
| detail-desktop-green | Desktop 手势状态3、真实组件12、偏好重建3、真实关闭清理矩阵1、production DI 1；Android production DI 2、真实下载队列保护2 | 24通过，整轮绿 |
| detail-layout-green | 自适应弹窗修改后的 Desktop 真实组件12、Android 真实组件5 | 17通过，整轮绿；与上行交互用例不重复计数 |

共35个不同相关用例。关闭清理的一个用例内覆盖全局保留0–4章、作品三态、书签、分类、占用、替换和迟到下载等矩阵。
移动端同指显隐、隐藏后整按钮宽缓冲由真实 Android 指针事件覆盖；共享手势状态另验证1x/2x密度、尺寸变化及300ms输入段。
收藏排序执行真实长按拖动并验证保存／取消。双端配置入口使用同一原子布局偏好；旧偏好可读取。

真实 Compose 截图核对320dp / 200%字号，窄窗口选项已变为单列，保存／取消可达，选项列表可滚动。
截图位于 `app-desktop/.test-tmp/detail-actions-visual/`，属于过程产物，不代表原生窗口或真机验收。

## 审阅与交付边界

当前主干删除链由一名未实施该部分的代理独立只读审阅，通过；确认 Android DownloadManager、Desktop
ReaderProgressTracker 保留主干实现，作品设置保存只写偏好，跟随全局精确保留章节数。
本批超过通常文件估算，因为同一共享组件必须同时接双端 UI、资源、偏好、DI、阅读完成入口及其真实测试；不拆成无法独立编译的交付。

Scoped Spotless 使用项目既有 lint convention 和绝对文件路径 hook，Desktop 经临时 init 应用相同 convention；
不修改仓库格式规则，不运行无关文件检查。产物构建完成状态以独立候选清单及完成回执为准。
Android 候选分配 versionCode158 / 0.19.4-aex.140，沿用现有 fork applicationId 和证书。
Windows 使用 preview，不分配正式桌面版本。未安装到设备、未推送远端、未更新公共能力承诺。
历史 `manga-detail-actions-20261113-validation.md` 只记录旧基线证据及当时缺口，不能代替本轮主干证据。

## 后续四项调整（基线992a1a2a75）

用户后续要求优先于原型修订10：定制弹窗双端单列、只排序；主按钮长按直接排序；作品读完即删开启时增加
既有保留位置及允许删除书签设置，并移除面板说明文字。主按钮首屏按完整顺序前四个可用动作展示，不再按旧成员偏好隐藏。

- `detail-sort-red`：Desktop真实长按仍打开弹窗、定制仍有勾选，目标行为测试失败；Android首轮因测试API引用错误未执行，不算行为红。
- `detail-options-red`：真实关闭阅读器清理矩阵不能按作品独立保留位置删除，断言应删除实际保留。
- `detail-delete-ui-red2`：真实Android面板缺保留位置控件，控件断言失败；前一次被测试API导入问题拦住，不算行为红。
- `detail-revision-green`：除长按不移动松手误点击外均通过；新增长按测试识别真实事件消费问题，后续修正为排序手势期间阻止业务点击。
- 独立只读删除安全审阅指出模式与选项分两次读取可能混合新旧快照；`detail-settings-snapshot-red` 通过真实
  DesktopPreferenceStore的底层读取钩子，在首次读取后保存新配置，确定性复现两份完整设置均禁止书签但混合值允许删除。
  修正共享书签许可及Android完成计划为一次读取完整设置；原finding独立复审通过。

扩展使用原作品appState键的一次字符串写入保存 `MODE|slots|bool`，兼容旧枚举、保留旧模式投影API。
书签覆盖限定于阅读完成清理；手动删除和标记已读的全局策略保持原有边界，分类排除仍然生效。
Android待删队列不保留bookmark/read属于既有边界，未在本批重构队列格式。
本轮由一名代理实施排序UI、一名未实施者审阅删除边界；主代理实现删除面板、偏好及production接线，并统一串行验证。
原拟分配159；安装前发现平板已有160且另一个阅读器候选161随后完成安装，因此改为versionCode162 / 0.19.4-aex.144。
为保留已安装能力，有界整合阅读器提交 `c2e4871a47`、`a01925d0b5`、`2743706914` 的功能、测试与内部说明，
不导入其旧分支相对main的其它差异，不回退当前数据库迁移及读完清理链。候选、安装和用户验收分别核验，不沿用旧版APK的运行结论。

最终 `detail-revision-green2` 整轮通过，45个不同用例：Domain契约5；Desktop手势3、真实详情组件16、
偏好持久化／快照5、真实关闭阅读器清理矩阵1、DI接线1；Android真实详情组件7、删除面板1、
ReaderViewModel接线2、下载队列书签／分类保护2、DI接线2。未运行全仓或完整模块测试。
定制弹窗320dp／200%字号真实Compose截图已核对，单列无勾选，排序项及保存／取消可达。

### 保留平板161阅读器的组合证据

平板安装前读回的APK为 `2743706914` 对应161候选，证书与fork一致，实际SHA-256为
`463698fb931fa2f4719eb59510d1090b9a4c2fd0334f905ccbb3f847afd85566`，与该候选清单一致。
组合后运行 `detail-installed-reader-integration`，Domain19项通过；Desktop45项中一项旧配对夹具失败；
Android25次执行中有3次首次失败、重试后通过，不能把XML中首轮失败隐藏为一次全绿。

唯一仍失败的Desktop用例为 `DualPagedPresentationTest.spread forced single edge match and parity change grouping without changing page identity`。
在平板已装161的干净源码工作区运行相同精确用例，`detail-reader-baseline-control` 同样得到
期望 `[[0],[1,2],[3],[4],[5,6]]`、实际 `[[0],[1,2],[3],[4,5],[6]]`。
同次基线对照的Android三项单独执行均通过；组合工作区的这三项也已在正常测试重试中通过。
该对照不等于故障原因已经排清，不把已有断言失败认定为本次排序／删除设置回归，也不改既有算法或放宽断言。
本批仍只称候选，未执行完整模块／全仓或完整发布矩阵；保留此基线测试缺口供阅读器任务处理。

## 删除面板的设置页控件对齐（基线e53092eeaf）

用户截图指出删除位置的按钮式下拉及书签行与设置页不一致，且展开内容出现在“关闭”之后。
`deletion-panel-order-red` 在真实挂载面板中验证控件相对位置，确认原实现失败；随后将宿主设置控件
作为必选插槽传入共享面板，仅在“开启”选项之后插入，位于“关闭”之前。
Android直接复用ListPreferenceWidget／SwitchPreferenceWidget；Desktop直接复用现有删除设置的
列表摘要行、单选弹窗及SwitchSettingsItem。Android新增modifier参数默认值不改变原设置页调用。
设置子项保持外层草稿，不改变删除策略、全局偏好或保存边界。

验证按组合证据收口：Desktop详情16项、Android详情7项继续通过；删除面板原位保存／取消1项通过，
共24个不同相关用例。`deletion-native-widgets-final` 再次通过Desktop选择弹窗、位置、书签、保存
和Android同路径；格式使用scoped项目Spotless。Robolectric的captureToImage首次因窗口重绘等待超时，
改用真实Dialog窗口绘入原生Bitmap，未修改production绘制链。中文840×600深色主题截图已实际查看，
两项控件的标题／摘要层级、行间距、整行点击及“开启—子项—关闭”顺序符合目标。
截图仅是过程产物，不等同平板原生窗口验收；APK163仍需用户实际体验。

## 2026-11-17 审定平铺单选的双端落地

用户确认 `manga-deletion-panel` 第三版HTML，并要求双端交互一致；本节取代上节“开启—子项—关闭”的布局要求。
三态集中在上方，按原版阅读模式的图标圆角按钮语言自适应排列，只有开启时在整个选项区下方显示配置。

Android-only的 `presentation-core` 不能供Desktop直接依赖，因此在既有KMP `presentation-manga` 中提供
`DetailDeletionModeChoices` / `DetailDeletionOptions`，两端宿主只提供既有本地化资源；没有复制两套平台UI。
共用Material3圆角按钮、语义主题色、128dp最小网格槽、48dp最小选项高度、4/8dp网格间距，
放大字体时按fontScale扩大最小槽宽。外层最大480dp、内层选择弹窗最大400dp；窄屏自然换行。
这组CSS转Compose尺寸是本批设计选择，不能称为上游所有页面的统一常量。

两端使用同一设置行／摘要／开关、透明背景、12dp分区间距和文字按钮，移除平台ListItem带来的背景差异。
三态是互斥选择，重复选择不取消；跟随全局和关闭都收起子项；切回开启保留未保存的章节位置和书签草稿。
删除时机仍为既有0–4五项；点击当前项保留内层弹窗，选择其他项只修改外层草稿，内层取消或Escape不提交。
只有外层保存写入作品设置，取消或关闭丢弃；实际删除、全局设置、数据库及阅读器链路未变。
本地偏好写入失败仍显示既有错误并保留草稿，未更换持久化和错误处理链。

固定验收：双端真实详情入口打开面板，三态位于所有配置之前；宽屏同排、窄屏换行；展开／收起互斥；
内层取消和重复选择；外层保存／取消及作品隔离；320dp窄屏配置及保存可达。

- `deletion-tile-red`：Android及Desktop真实挂载面板的同排布局断言都失败；旧版仍为纵向RadioButton且关闭在子项下方。
- `deletion-tile-green`：Desktop真实详情16项、Android详情7项及删除面板1项通过，共24项；原生Bitmap截图确认平铺布局。
- `deletion-tile-final` / `deletion-tile-narrow-observe`：初始窄屏断言错误要求一列；真实两端截图和尺寸确认320dp原生容器实际有272dp内容宽，132dp两列合法，已改为验证末项换行、无溢出及所有操作可达。没有改变已定的自适应网格要求。
- 同次截图发现英文Follow global换行后被IconToggleButton的固定内容高度裁切；`deletion-tile-label-red`只验证总尺寸未捕获，`deletion-tile-label-red2`通过真实TextLayoutResult.didOverflowHeight稳定失败。随后使用Material3 FilledTonalButton承载相同图标、圆角和选中语义色，让文字自然增高，并使同排按钮等高。该adapter避免将原阅读模式48dp固定高度直接复制到长文字。
- 最终结果及截图见本节后续记录。未运行全模块／全仓测试，未打包、安装或推送；Compose渲染测试不等于真机／原生窗口验收。

`deletion-tile-final2`：修改后的5项focused测试全部通过（Desktop布局／嵌套单选／窄屏／保存隔离3项，
Android宽屏及320dp完整保存／取消2项）。与未受后续局部面板调整影响的首轮按钮排序／手势测试组合，
共26个不同相关用例通过，不声称最终代码重新运行了全部26项。
最终截图已读取核验：Android 840dp深色及320dp深色、Desktop 600dp及320dp浅色；英文两行标签无裁切，
模式始终先于所有设置，同排等高；320dp中文两列且书签标题自然换行，底部取消／保存可达。
过程图片位于 `.test-tmp/detail-actions-visual/` 和 `app-desktop/.test-tmp/detail-actions-visual/`，不提交。
格式由项目scoped Spotless处理，只覆盖本任务6个Kotlin文件；末次变更只有import排序，不改变测试行为。

`deletion-tile-format-clean`通过，末次scoped检查为IS CLEAN／UP-TO-DATE，无剩余格式差异；`git diff --check`通过。

## 2026-11-17 双端体验包与平板升级输入

用户要求双端体验包并安装到平板，自行验收。预检已确认当前平板安装164／0.19.4-aex.146，APK SHA-256
`4e7c3ff114c7b88e129b53e9ac5668c00da96923779cc834b9981431adb8de0e`，
精确匹配另一隔离工作区 `54bc58d738488cc4b6944530da6a04c1802d0ea7` 候选。
为保留实际已安装的章节区间选择／撤销能力，有界整合该提交除版本属性外的功能、图标、资源、测试及内部说明，
不整合其分支外其它提交；原读完即删共享面板、161阅读器修正、主干schema47及删除链保留。
跨工作树核对164已占用，分配165／0.19.4-aex.147并保留原应用身份与证书。
整合后复验共享区间契约、真实Android phone/tablet按钮接线及本次双端删除面板；不运行全仓／全模块测试。
Windows走preview，不分配正式桌面版本、不冒充主干包；构建清单和实际安装状态分别报告。

`deletion-package-integrated`通过，共21项相关用例；合入的功能／测试／图标／资源与已安装164来源逐文件比对一致（仅版本属性另行分配）。整合只增加兼容基线，不改写这些既有实现。

## 2026-11-17 读完即删改为阅读设置式即时偏好

用户要求移除保存，使用阅读器中阅读设置的窗口方式，修改立刻生效。本节取代前面此面板的草稿／保存／取消约定。
范围限定作品详情的读完即删面板；按钮排序弹窗仍沿用其既有保存／取消，不改变实际删除策略或全局偏好。

SOURCE：Android `ReaderSettingsDialog` → `TabbedDialog` → `AdaptiveSheet`，手机底部、平板居中；
Desktop `ReaderSettingsPanel` 使用Material3 AlertDialog，滚动设置加关闭文字按钮，设置回调即时应用。
Android复用同一个AdaptiveSheet及TabbedDialogPaddings，单节设置不增加虚构标签页，无保存／取消栏。
其75%高度限制在Dialog内有限约束中计算，防止详情滚动容器的无限高度传入；标题固定、设置区域可滚动。
Desktop沿用阅读设置的AlertDialog标题／内容／关闭模式，不添加确认写入步骤。

共享 `DetailActionStrip` 通过必选的 `deletionDialog(title, onDismiss, content)` 插槽消费各端容器；
两端生产入口及Android测试挂载均传入实际adapter，公共层不依赖Android-only的presentation-core。
选择模式、保留位置和书签开关分别调用同一完整设置偏好的原子写入，值从既有changes流驱动；不保留外层草稿。
收起设置不会抹掉其值，关闭、遮罩、返回或Escape不回滚。内层保留位置选择仍按原设置列表规则工作：
选其它位置立即写入并收起内层，点击当前项保持内层，取消内层不改值，不撤销其它已提交操作。

写入失败保留原持久化值和原选择，当前面板显示既有错误；下一次成功修改清除错误，允许直接重试。
CancellationException继续向上抛出；关闭窗口不触发额外保存。无新持久化格式、数据库、DI或删除链变化。
本批9个文件均服务同一变更：公共状态／说明、两端容器与调用、真实UI回归及本记录，不拆成独立能力。

- `deletion-immediate-red`：双端真实面板点击开启后立即检查偏好，仍为INHERIT，确认旧实现必须保存才写入。
- `deletion-immediate-green`：Desktop布局／窄屏／即时写入及关闭持久化／失败重试4项，Android面板宽窄屏2项、详情动作7项，共13项通过。
- 已读取Android840dp平板居中、320dp手机底部以及Desktop600dp设置弹窗的实际渲染图片；无保存栏，手机底部、平板居中，Desktop只有关闭。
- 格式及最后Android位置／即时写入断言的结果以本节后续记录为准。图片为离屏Compose/Robolectric过程证据，不代替原生窗口或真机验收。

本轮没有版本分配、打包、安装或推送；已交付165包不因源码修改而自动更新，不把旧包描述为本轮结果。

`deletion-immediate-final`新增位置断言首次使用合并语义树，未找到已被AdaptiveSheet点击容器合并的子标签；
失败消息明确指出未合并树存在该标签。测试改为useUnmergedTree读取真实子布局，未改production来迁就断言。
`deletion-immediate-final2`两项Android测试通过，确认平板中心坐标／手机底部位置及修改瞬间的持久化值。
Scoped Spotless覆盖本批8个Kotlin文件，最终IS CLEAN／UP-TO-DATE；`git diff --check`通过。

## 2026-11-17 即时设置体验包的166版本输入

用户明确要求构建并安装，继续使用无线调试。已连接平板的165包SHA-256与上一轮候选完全相同，
确认从本工作区既有功能基线升级，无额外功能整合、数据库或签名变化。
跨工作树核对最高分配165，分配166／0.19.4-aex.148；本次除版本属性外没有新增产品逻辑，
复用 `5322c21abc` 的13项相关测试及最后位置／即时写入补验，不运行全量测试。
Android使用candidate签名和实际安装校验，Windows使用preview及其运行时检查；各包的来源、哈希和分层状态以独立产物清单为准。

## 2026-11-17 移动端底部留白落地与167体验包

用户审核HTML第四版后要求实现、构建和安装。仅修改Android作品详情读完即删面板的底部间距，
不改变Desktop、共用AdaptiveSheet、删除策略或偏好。手机底部和平板居中容器继续复用阅读设置链路；
原滚动内容底部8dp替换为面板固定32dp留白，上侧8dp和水平24dp沿用原值。
留白放在75%高度约束内部、内容滚动区外；系统导航／状态栏边距仍由既有AdaptiveSheet处理，不重复叠加。

`deletion-bottom-red`：真实生产面板及动作入口在840×600平板与320×640手机中均失败，
书签设置行底边到面板底边实际8dp，目标32dp。`deletion-bottom-green`命令参数被PowerShell拆分，
Gradle尚未运行测试即拒绝任务路径；完整引号修正后的`deletion-bottom-green2`两项真实UI测试通过。
覆盖32dp几何、平板居中／手机贴底、子设置可达、三态切换、即时持久化和返回重开。
实际查看两份Robolectric渲染图；它们是离屏视觉证据，不替代真机人工验收。
`deletion-bottom-format`两份Kotlin文件IS CLEAN；git diff --check通过。没有扩大测试范围。

本轮无线重新连接并核对PCE-W30，已装166的APK SHA-256为
9587dd4b0c6826083ec9d94b59f0b51e4d8956e83f3b6aadb4b9a7d4a3b231ef，与上一轮交付一致。
跨注册工作树核对最高分配166，分配167／0.19.4-aex.149，签名、包名和schema不变。
构建使用candidate；安装由用户授权，保留数据升级，并以安装器实际回读核验结果为准。
本节不代表安装或真机视觉验收已经通过；各层结果分别记录在候选清单和安装日志。

## 2026-11-17 编辑按钮轻强调样式落地与168体验包

用户审核HTML修订12后要求实现、安装。共用DetailActionRail入口改为Outlined.Edit；简中“编辑按钮”、默认英文“Edit buttons”，
同一标签继续用于入口、无障碍动作和排序弹窗标题。显隐、宽度、手势、排序及保存链路不改。
样式消费用户当前MaterialTheme而非强制草莓主题：primary前景；surfaceContainerLow到primary的8%混色背景，
outlineVariant到primary的24%混色1dp描边，保留13dp圆角；原生按压／焦点沿既有combinedClickable反馈。
不改全局主题、其他业务按钮、删除面板32dp底部留白、数据或schema。

`edit-style-red`挂载真实Android MangaActionRow，滑动显示入口并采样实际Bitmap：旧背景通道85，目标47，确认整块primaryContainer不符。
`edit-style-green`既有7项Android交互及Desktop滚轮返回／固定入口1项通过；新增样式用例的颜色、宽度和按钮文案通过，
打开弹窗后同名文本匹配到背景入口与弹窗标题两项而失败。仅修正测试为弹窗祖先范围，不修改production规避问题。
`edit-style-final`新增2项通过，分别检查草莓及默认蓝色各自深浅色的真实背景像素、普通按钮等宽、入口文案、点击和弹窗标题。
复用未受测试定位修改影响的7项Android及1项Desktop通过证据，总计10项相关用例；不是全模块测试。
实际读取红色深浅色离屏截图；真机观感由用户验收，不将Robolectric视作实体设备截图。
`edit-style-format`两份Kotlin文件IS CLEAN，git diff --check通过。

当次确认无线PCE-W30已装167／0.19.4-aex.149，实际APK SHA-256
0a4f6f7af7855779a214d93eeca92ac2597675b1c3404bccf41af83058697a86与上一轮交付一致。
已核注册worktree最高分配167，本轮分配168／0.19.4-aex.150，原包名、证书和schema保持连续。
使用candidate构建并经独立install保留数据升级；实际结果以产物清单与安装回读日志为准。
共用源码同时更新Desktop，但本轮不构建桌面包、不推送远端；安装不隐式操作用户应用或改变主题。

## 2026-11-17 编辑面板预览交互与左端手势资格

用户要求双端删去“收藏也可移动”，顶部预览支持拖动／滚动，并禁止从后排开始的同次拖动显示编辑入口。
共用简中说明改为“长按拖动调整顺序”，英文删除Library can also be moved短句，收藏仍可排序。

原预览只画order.take(4)静态单元。本次直接复用DetailActionRail绘制完整草稿序列，前四项决定可视槽宽，
手机短拖连续浏览、Desktop滚轮仍按一段一页及300ms分段；长按拖动复用原排序／边缘滚动。
预览点击不执行业务回调，不嵌套编辑入口或其无障碍动作。预览拖动只修改弹窗order草稿，保存仍走原持久化，取消丢弃。
列表排序与预览共享同一order，不新增偏好格式、数据库、DI或平台专属排序实现。

DetailActionMotionState在beginDrag固定dragAtStart（左端容差沿用1px）；起始不在左端，本次即使回到左端、
额外右拖或反向后再次右拖，也不能显示编辑入口。松手后从左端开始的新手势才有资格。
从左端开始的手势仍支持同次显隐及隐藏后一个按钮宽度缓冲，原冷却／Desktop滚轮边界不变。
预览构造状态时关闭编辑显隐，仍复用相同浏览和排序状态链路。

- `edit-preview-red`确认旧提示和非左端拖动行为失败；`edit-preview-scroll-red`进一步确认双端静态预览在真实拖动／滚轮后不移动。
- `edit-preview-green`：共享状态4项通过，Android10项、Desktop18项通过；两个新预览断言仍试图读取已被裁剪／移除的首个单元而失败。
  修正为断言后排删除按钮完整进入实际视口；Android触摸从当前可见追踪按钮开始，避免将整条超宽track视作触摸可视区。
- `edit-preview-final`：Android预览浏览1项及Desktop预览滚轮／预览拖动草稿保存取消2项全部通过。
  复用未受测试定位修正影响的通过证据，共35项相关用例覆盖两平台入口、显隐、缓冲、排序、持久化／失败重试及编辑面板。
  不是最终代码重新跑全模块或全仓；没有扩大验证范围。
- scoped Spotless首轮发现长行，缩短testTag表达式并格式化；最终`edit-preview-format-clean`为IS CLEAN／UP-TO-DATE，git diff --check通过。
  该整理仅提取相同的标签前缀与换行，不改变已验证手势或保存行为。

本轮只更新双端源码，未更新HTML、未分配版本、未构建／安装或推送；平板现有168不会自动获得这批修改。

## 2026-11-17 编辑预览交互169体验包输入

用户要求构建、安装，使用无线调试。重新连接并确认PCE-W30已装168，实际APK SHA-256为
a3e1358b105d9e6dd896f4dc0fc470d3ed3f362c28414b740127bd0700d32b25，与上一轮交付一致。
本轮复用0f5c354668的35项相关行为／接线和scoped格式验证；只修改版本元数据，没有追加产品逻辑或全量验证。
已核注册工作树最高版本168，分配169／0.19.4-aex.151；包名、证书和schema不变。
候选从本工作树提交后的源码构建，使用candidate --offline进行Release压缩、签名及完整性验证，
再独立install保留数据升级并回读安装身份／签名／哈希；实际结果分别以产物清单和安装日志为准。
不启动或代操作用户应用，真机交互由用户验收；本轮不构建Desktop或推送远端。
