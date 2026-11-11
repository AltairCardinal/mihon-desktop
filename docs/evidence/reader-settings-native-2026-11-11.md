# 双端阅读设置原生实现验证

日期：2026-11-11。设计依据为用户已通过的 [v3 原型](../prototypes/reader-mode-default/README.md)，维护契约见 [双端阅读模式设置](../architecture/reader-settings.md)。本记录不是用户对原生安装包的体验验收。

## 源码与范围

- 原生实现提交：`b69311e5cb2ca1dba8c01e75c2198bbc660f28df`。
- 随本记录整合固定主干 `ad07de4cca5055eb1748df4f9ad95a312bbf1ce6`，保留该主干的启动优化与其他已有改动。
- 工作区：`D:/Codex/worktrees/mihon-reader-settings-chips-20261108`；分支 `codex/reader-settings-chips-20261108`。未修改其他工作区的未提交内容，未推送或分配本轮发布版本。
- Android：作品设置、阅读器快捷入口与全局默认共用分组控件；实际 VM、作品 flags、SQLite 保存和 viewer/config 接线。
- Desktop：作品与全局控件、启动/完整模型的统一设置投影、真实 VerticalPager、点击/上下键、连续页面间隙、全局搜索锚点与作品保存/重开。
- 超过 8 文件的原因是两套原生视图 adapter、共享持久化契约、实际阅读器接线及对应测试需要共同交付；没有引入新数据库列、下载机制或配对算法。

## 红绿证据

- 共享 AUTO LTR 首次 flags 往返失败；修复为兼容 Int 备份的模式 9 编码。旧 Desktop 仅 layout 标记的继承判定亦先失败后修复。
- Android 原控件缺少跟随/定制与纵向滚动分组，且不识别模式 9，初始行为测试失败。真实 VM 的旧双页标记用例出现 expected 6 / actual 7，接入共享有效设置后通过。
- Desktop 初始测试复现纵向 flags 被降级、默认切换丢失定制、缺少竖向 pager 和锁定 webtoon hint；修复后原范围复验。实际连续列表间隙也有旧 spacing 0 的失败证据。
- 主干合流后，新增测试复现启动投影丢失完整设置、纵向误用横向启动视图；统一投影并排除纵向后通过。原有 early 输入和图像交接测试保留。

## 验证结果

所有 Gradle 均通过项目协调器，限定 `--tests`，使用本机 JDK 21、已有 SDK 与缓存，`--offline --max-workers=2`。未运行完整模块/全量测试。

| 范围 | 结果与证据 |
| --- | --- |
| 共享 flags/设置契约 | 8 项通过，含旧数据、隐藏值、默认恢复、无关 flags、Int 往返；`reader-settings-shared-final-2`，后续 `reader-settings-desktop-green` 再次通过两契约类 |
| Android 设置与真实 VM/native config | 25 项组合通过；`reader-settings-android-legacy-green` 中 24 项通过，末例更正夹具预期后由 `reader-settings-android-chips-complete` 通过 |
| Android 生产入口与存储 | 1 项通过，`reader-settings-android-factory`：实际 Activity factory、AppBars/设置控件动作、真实 VM、SetMangaViewerFlags、MangaRepositoryImpl/SQLite、manga/events 订阅及新 LTR 双页 viewer/config；与前行共 26 项有通过证据 |
| Desktop 合流 focused 范围 | `reader-settings-merge-green` 执行 148 项，145 通过；一项来源夹具的 Flow/List 同名 getter MockK 冲突改为真实 SourceManager 对象后，`reader-settings-merge-loading-2` 通过。组合为 146 项通过、2 项已有基线失败 |
| Desktop 最终核心补验 | `reader-settings-merge-final-2` 的 3 项通过：完整初始设置投影、纵向排除横向 startup、真实 Loading UI 修改全部设置字段/作用域并保存，页表到达与第二次打开后仍保留 |
| 格式与资源 | Android 与 Desktop 任务改动的 focused ktlint 检查通过；Desktop 最后 8 接触文件再次通过；7 个相关 XML 解析通过；本任务相对固定主干的 `git diff --check` 通过 |

完整实际命令、退出码、日志保存在工作区忽略目录 `.gradle-coordinator/<上述 key>.json/.log`。这些记录是分阶段组合证据，不表示最终代码重新运行了整个模块或发布矩阵。

合流时保留主干已有第三方 `windows-miniz/miniz/LICENSE` 与校准 properties 夹具的原始 CRLF。相对旧分支的整份合流差异检查会报告这些既有行尾空白；本任务不改写它们。交付检查以 `git diff --cached --check ad07de4cca5055eb1748df4f9ad95a312bbf1ce6` 限定实际新增/修改内容。

## 两项已有失败

**后续核对已关闭（2026-11-11）：** 用户随后要求区分测试过时与产品缺陷。按当前契约补齐真实像素、指针、曝光和进度正反对照后，确认两项均为过时测试，生产代码未改；最终 5 项 focused 验证通过。详见[契约与实际行为核对](reader-pairing-test-contract-audit-2026-11-11.md)。下文保留本轮最初发现时的原始失败记录。

以下两个断言在未含本轮原生改动的独立 `c6ac535359` 快照中均复现相同失败；合流后的现象也相同：

1. `AdaptiveReaderViewportTest.production reader resize preserves page and progress and real next tap resumes reporting`：期望 `[2, 3]`，实际 `[1, 2]`。
2. `ReaderScreenModelTest.invalid sync resume confirms the drawn partner while logical target is still loading`：partner `prepare` 期望 true，实际 false。

不能据此宣称全绿，也未将其归因于本次设置实现。临时夹具尝试未关闭它们，已撤回；本轮不修改生产配对算法或放宽原断言。基线日志、退出码和两份 XML 保留在 `.gradle-coordinator/reader-settings-native/baseline/`；临时基线工作区已清理。

## 视觉与测试边界

### Android 拆分双页恢复原版布尔区位置

用户后续要求安卓版「拆分双页」移出模式卡片，回到阅读模式下方原版布尔设置区。对照修改前历史实现的顺序，恢复为平移导航 → 拆分双页及交换顺序 → 宽页旋转及反转。保留椭圆开关、指定说明、原偏好与双向旋转互斥，不改 Windows 或条漫独立处理。`android-pager-split-placement-red` 先复现原位缺少「拆分双页」以及快捷模式 sheet 仍含旧入口；最小移动后 `android-pager-split-placement-green` 通过 14 项 Android focused 验证：原位几何顺序、偏好保存、单双页与默认切换、旋转互斥、快捷入口移除、共享设置及真实 Activity→SQLite→viewer 接线。`app/build/reader-settings-parity/pager-boolean-split.png` 是实际 NATIVE 控件渲染，核对拆页项位于三项原版布尔项之后；长页面仍可继续滚动查看下方交换顺序与旋转。未运行全量测试；Release 安装不等于用户体验验收。


### 默认阅读模式设置的入口收纳

用户要求设置 → 阅读内的模式组收纳为标题、一行当前值以及点击打开的完整窗口。双端通过共享 `DefaultReaderModePreference` 实现，继续调用原有平台偏好写入入口。`default-reader-dialog-red` 的 Desktop 入口用例先失败，`default-reader-android-red` 复现旧模式仍直接展开。`default-reader-dialog-final` 的 Desktop 10 项通过（角色/实际偏好、搜索导航与锚点、共享控件回归），Android 其余 8 项通过；新增全局窗口用例因夹具对关闭按钮调用了不存在的父级滚动而失败，改为点击可见关闭按钮后由 `default-reader-android-verified` 通过。后续补验仅覆盖全局入口最后的外边距和保存/重开；组合共 19 项定向证据，未运行完整模块或全量测试。Android NATIVE 图片 `global-summary.png`、`global-dialog.png`、`global-summary-updated.png` 核对收起入口及真实 Dialog decorView；同时通过 TextLayoutResult 的实际 lineCount=1 验证摘要单行。仅修改全局模式入口及其搜索定位，作品弹层保持原样；新行为待用户验收，未更新公共功能承诺。

### Android 对齐已验收 Windows 的共享控件

用户于 2026-11-11 验收 Windows `0.11.24.10.c95438e`，并要求移动端一致后构建 APK。本轮基线为 `c95438e394e0e6b2a3ac1d7d1e1c9260cd6a2534`。

- 将已验收的 Compose 控件及 24 网格图标提取到 `presentation-theme/src/commonMain/kotlin/tachiyomi/presentation/reader/settings/`。该 KMP 模块原本已依赖 Compose、domain 和 i18n；未增加模块或构建依赖。Windows 保留薄资源 adapter；Android 的作品设置、快捷 sheet 和全局设置共用相同真实控件，避免两套视觉副本。
- Android 单页分割开关使用原 `dualPageSplitPaged()`；不新增作品 flags。保留原拆页反转和条漫独立处理，移除分页设置中的重复拆页复选框。分页拆分与宽页旋转互斥，保留反转偏好，不修改图片分切算法、章节加载或保存权威。
- 红测 `reader-android-parity-red` 复现三项差异：分行默认摘要缺失、模式/方向卡尺寸与分段布局缺失、单页分割开关缺失。`reader-android-rotation-red` 使用原旋转入口复现开启旋转后拆页仍开启；恢复互斥回调后同一用例通过。旧摘要断言已按真实分行键值和滚动定位更新，没有放宽保存/状态断言。
- `reader-parity-both-green`：Desktop 13 项通过，包含实际弹层、指针与键盘、模式显隐、保存重开及 runtime/repository；后续 production 改动仅为 Android 旋转回调，未改共享控件或 Windows adapter。`reader-parity-final`：共享 flags/设置契约 8 项、Android 控件与入口 12 项、Activity factory→真实 VM→SQLite→viewer 重建 1 项通过。组合共 34 项定向证据，不表示全模块或最终代码重新全量通过。
- Android 图像来自 Robolectric SDK35 的 NATIVE 绘图后端和真实 rootView：`app/build/reader-settings-parity/` 下横向自动/单页/双页、纵向连续/逐页、横纵默认摘要，以及 320dp/1.6 字号浅色图。430dp 深色核心控件由独立只读核对，结构、文字、图标、开关和显隐与 Windows 对应；Android 旋转/拆页反转等扩展继续保留。窄屏末尾说明由追加的 `narrow-large-text-scrolled.png` 核对完整文字及滚动可达，不声称整个长页面同时处于一个视口内。
- Activity 集成沿用已验证的未附着 viewer 容器与 foreground-busy 夹具，验证真实配置/保存和重建，不把 Windows JVM 缺少原图采样 JNI 的边界伪装成图片解码或实机翻页通过。未安装或操作用户设备；NATIVE 绘图图像不是物理设备截图，也不是用户对新 APK 的验收。
- 对已有明确 Windows 验收同步更新双语功能说明和 changelog，固定候选与源码，只宣传 Windows 已验收范围。网站使用已有离线缓存安装依赖，`npm --prefix website run docs:build` 通过；未推送、部署网站或分配产品版本。Android 交付为独立 `.dev` Debug 测试身份，正式版数据与签名身份不变；APK 的本轮来源、哈希、身份与检查结果由 `app/artifacts/android/` 的 `artifact.json` 记录，不把调试构建等同 Release/R8/升级验收。

本次超过八文件的变化来自共享视图提取、两端资源/偏好 adapter、对应真实行为测试及用户验收后的四份双语公共说明；没有引入第二套设置存储、UI 框架或后台服务。全部重型 Gradle 使用同一 worktree 的协调器串行执行，限定测试类/方法、离线缓存和两个 worker。

### 后续 Windows 原型视觉补齐

用户提供实际 Windows 截图后，确认此前控件仅还原了设置结构，缺少原型的分段底槽、模式单选标记、单双页/方向图标、开关说明卡和分行默认摘要。原先截图核对不能作为原型完整视觉还原的通过证据。

- 基线为 `f6bb2143fc371afade9c5fec33a9b66c6d2b2563`，只调整 Desktop 视图和对应中英文资源；不改共享设置算法、作品 flags、持久化或 Android 视图，不分配正式发布版本。
- `ReaderModePrototypeVisualTest` 首次执行 4 项，3 项按预期失败：模式卡/方向卡尺寸和分段结构、默认摘要、间隙说明；日志 `reader-settings-visual-red`。新增真实渲染、指针及几何断言，不以源码扫描代替行为。
- 补齐同一 production 控件后，`reader-settings-visual-final` 通过 11 项：原型视觉/交互 5 项、实际设置弹层选择与重开 4 项、真实 reader→runtime→repository 保存与重开 1 项、全局设置入口及偏好重建 1 项。包含 Enter/空格完整按下/释放、单选与开关仅触发一次、隐藏值保留，以及自动/单页/双页、纵向连续/逐页和默认摘要。
- 初次组合命令的参数拆分、ktlint 长行分别由后续命令和格式修正关闭；最终定向 ktlint 与测试通过，不把这些工具阶段错误归因于产品行为。全程使用协调器、JDK21、离线缓存与最多两个 Gradle worker；未运行完整模块或全量测试。
- `.gradle-coordinator/reader-settings-visual/` 保存直接调用真实 Compose 控件的 Skia 图片：`horizontal-auto/single/double.png`、`vertical-continuous/paged.png`、`default-summary.png`、`vertical-default-paged.png` 和窄窗主题图。默认主题的深色、浅色、AMOLED，360px / 1.6 字号检查通过；图标实际像素、柔和选中底色、控件几何与键盘均有定向断言。
- 独立只读核对所提供的八张离屏图，未发现阻塞视觉偏差。后续补图覆盖横向单页及纵向逐页的默认摘要；这些均为 production Compose 组件离屏图，不是完整 Windows 原生窗口或用户验收。Windows `preview` 体验包由本轮最终提交构建，源码身份与实际地址以对应 `preview-manifest.json` 和 `Final unpacked EXE:` 为准；用户验收仍待执行。

### 初次实现的历史检查范围

补充（Windows 分割宽页开关统一，基线 `790cf83ca7d040860299932f0f6029499669a9e3`）：`reader-split-switch-red` 先确认现有分割控件不具备 Switch 语义（预期 Switch，实际 null）。改为直接复用匹配/间隙使用的 `ReaderModeSwitch`，移除先前仅服务该选项的复选框说明适配，位置、文案和保存入口不变。`reader-split-switch-green` 的 13 项 focused tests 通过，包括真实指针点击右侧开关、checked 状态、原偏好保存、其他模式隐藏和重开；同名截图已核对右侧椭圆开关及长说明。此为 Compose 离屏与 production 接线证据，实际 Windows 包仍由用户验收。

补充（Windows 分割宽页位置，基线 `d34ec21a8087b7ec72c24ae9fa52c31d5f15a8c3`）：`reader-single-split-red` 先复现默认继承时仍出现独立分割控件。移入横向单页分支并加入用户指定说明后，`reader-single-split-verified-2` 的 13 项 focused tests 通过，覆盖实际弹层的显隐、选中值、原偏好保存及重开，并复验原型组件和 reader/runtime/repository 接线。中间的重开断言曾按「关闭」文案点到导航模式的同名 RadioButton，夹具改为明确选择弹层的 Button 角色后关闭和重开通过；未为此改变生产关闭逻辑。单页集成截图为 `.gradle-coordinator/reader-settings-native/single-page-split.png` 和 `single-page-split-restored.png`，系真实 Compose 控件离屏渲染，不替代用户对新 Windows 体验包的验收。

- 人工核对实际 Compose 组件的默认、横向、纵向三种状态截图：`.gradle-coordinator/reader-settings-native/default.png`、`reading-mode.png`、`vertical.png`。中文、900×1600 离屏场景、测试 MaterialTheme；不是操作系统窗口或用户当前主题的验收。图标、方向等宽、说明、开关无是/否文字可见；截图推进动画时钟至稳定帧，纵向截图保留鼠标悬停反馈。
- Android 覆盖 320dp/1.6 字号、单选组语义、真实点击及持久化。补充生产入口测试使用已验证的 Dialog host，消费 Activity 与实际 overlay 共用的 factory；native viewer 容器未附着。synthetic PageLoader 按既有前台资源策略暂停 optional 原图采样，以隔离 Windows JVM 缺少 imagedecoder JNI 的边界；它验证 viewer/config 接线，不验证 JNI 采样、图像绘制或动画。
- 原 overlay 尝试的取消、Looper/动画/JNI 失败日志保留；最终 factory 用例没有未捕获异常。测试线程夹具修正不改变产品线程策略。
- 未启动真实设备/模拟器或完整 GUI 应用窗口，未生成本轮 APK/安装包，未做正式发布验收。原生功能待用户体验验收后再更新公共功能说明。


### 双端拆分双页恢复勾选框

基线 `7941860cb991ee9d389dd89d3dcdbda2f0cc3fcb`。按用户新要求，双端统一使用「拆分双页」无说明勾选框；Desktop 移到章节跳过布尔项之后、背景之前，Android 保持原版平移导航与宽页旋转之间。保留原偏好、模式切换后的保存值及 Android 拆页/旋转互斥。

- `split-checkbox-red` 与 `split-checkbox-android-red` 先复现 Desktop 下方入口缺失、Android 仍为 Switch；测试点击真实 production 控件并读取既有偏好，不以源码扫描替代行为。
- `split-checkbox-green` 的 Desktop 13 项通过。Android 初次改回原生 CheckboxItem 后暴露父级状态语义未合并到可点击文字；为拆页提供单一、带 checked 和点击动作的辅助技术节点后，`split-checkbox-android-green2` 的 Android 14 项通过（控件/入口 10、共享布局接线 3、Activity→VM→SQLite→viewer 重建 1）。组合 27 项 focused 证据，不表示全模块或全量通过。格式检查覆盖本次五个 Kotlin 文件。
- 查看 `.gradle-coordinator/reader-settings-native/split-checkbox-lower.png`、`split-checkbox-restored.png` 与 `app/build/reader-settings-parity/pager-boolean-split.png`：勾选框、无说明及下方位置符合要求。这些是实际 Compose 离屏/NATIVE 绘图，不等同物理设备或 Windows 原生窗口验收。
- 交付 Windows preview 与 Android 正式签名候选；构建来源、签名和真实产物路径以各自产物清单为准，平板保留数据升级结果与用户体验验收分别记录。
