# 双端阅读器扩展与常规设置对齐

基线：`55a411686e8b8534afee8eaebc4ed97d557b4cb8`。用户要求移动端四项宽图处理只在显式横向单页显示并生效；桌面补齐移动端阅读模式/常规选项，移动背景、移除章节导航及旧常规布局；两端 E-Ink 分组一致。

## 行为契约与实现边界

- SOURCE：Android ReadingModePage、GeneralSettingsPage、PagerConfig、WebtoonConfig 及原偏好定义；Desktop 原 ReaderSettingsPanel、ReaderPreferences、ReaderScreenModel 与实际输入/呈现实现。
- PROJECT_POLICY：共享 `singlePageExtrasEnabled` 只接受 HORIZONTAL/SINGLE；AUTO 即使实际单页仍关闭四项扩展。隐藏值保留，切回恢复，不新增作品存储或迁移。Desktop 下方按 Android 顺序提供六种点按区域、反转、六种缩放、缩放位置及勾选框；连续纵向使用独立点按、0–25% 留白滑杆和缩放限制。
- Desktop 常规页补齐页码、全屏、常亮、触摸长按菜单、章节过渡、翻页动画及 E-Ink 样式/时长/间隔。翻页动画复用原 page_turn_animation 偏好；新增选项由原 ScreenModel/PreferenceStore 保存，实际 reader 消费。屏幕挖孔仅 Android 硬件适用；Windows/macOS 有常亮 adapter，Linux 入口禁用。既有右键菜单、全局章节跳过等桌面能力保留。
- 章节过渡开启时，真实 keyboard/display-unit 选择先停在过渡，再跨章；邻章信息由当前 ReaderNavigator 传入。旋转复用现有位图变换 lease 与内存权威；旋转位图不叠加未旋转源坐标 tile，未增加图像/配对所有者。
- 新扩展偏好不为轻量首开伪造投影；非默认选项使用已有完整 reader 路径。没有改变配对算法、数据库 schema、flags 编码、网络或源身份。

## 红绿与定向验证

- `reader-sections-red`：真实双端 UI 先失败，复现 Desktop 旧背景/分组和缺失入口、Android 非单页仍显示宽图项。
- `reader-single-runtime-red*` 的首个夹具曾错误使用每次返回新对象的 InMemoryPreferenceStore，随后改用真实 AndroidPreferenceStore；AUTO_LTR=9 是语义编号，不是可直接写入 viewerFlags 的原始位值，最终使用 ReaderModeFlags.write 产生真实保存格式。保留失败日志，不将夹具错误当作产品原因。
- `reader-sections-focused3`：Android 13 项通过（11 控件/入口、1 production config 跨模式、1 Activity→VM→SQLite→viewer 重建）；共享 ReaderSettingsContractTest 6 项通过。Desktop 48 项中旧键盘断言需要区分“关闭过渡直接跨章”和“开启过渡先选过渡”。
- `reader-sections-desktop-final`：Desktop 51 项中 49 项通过，包含新增过渡行为与原 first-composition/变换所有权回归；两个失败为新增指针夹具未指定 Primary 按钮、导航顺序断言仍按旧 Desktop 顺序。修正真实事件参数与 Android 顺序断言后，`reader-sections-close` 的 16 项全部通过。组合共 Desktop 51、Android 13、共享 6，加上后续图片设置专项 2 项，即 72 项有效 focused 证据；不是最终代码全模块/全量重跑。
- `reader-inherited-single-close` 补验继承全局单页/自动切换，原值保留且 Config 随有效默认立即改变。`reader-eink-late-identity-red` 通过实际像素复现晚到 display-unit 身份提前清除闪烁；将翻页事件识别与遮罩计时分开后由 `reader-eink-late-identity-green` 同范围关闭，实际新翻页仍取消旧遮罩。
- `reader-vertical-inversion-red` 的真实指针复现逐页纵向沿旧坐标转置将物理左右区域识别成菜单；改为保持物理坐标、默认采用 Android L 形后，`reader-vertical-inversion-green` 补验横向、纵向、反转和键盘范围。首次 Windows 预览 08a2880 因这一收尾问题不作为最终交付；最终产物另由修复提交构建。
- `reader-image-options-red` 通过真实绘图消费的 production helper 复现缩放位置误受单页限制、宽图自动放大覆盖手动缩放。`reader-image-options-green` 覆盖这些情形：自动/双页/纵向逐页的缩放位置、条漫不消费该选项、只有适应屏幕才自动放大宽图、宽视口支持及非单页关闭。Android 候选源为 2d5e68a；后续仅 Desktop 局部修正，不重签相同 Android 输入的候选。
- 新原生绘图用例验证白→黑→清除的 E-Ink 遮罩；实际 Skia 像素验证正反 90°旋转；真实指针验证偏好更新后的区域反转；真实 PreferenceStore 重建覆盖全部新增值。旧变换 lease 与 first-composition 集成测试覆盖相关生命周期边界。
- 定向 ktlint 覆盖本次 Kotlin 文件，git diff --check 作为文本检查。中间编译/格式错误均在本轮关闭，不将编译成功冒充功能验收。

## 视觉与交付

查看实际 Compose 离屏图：`.gradle-coordinator/reader-settings-native/general-parity.png`、`general-options-enabled.png`、`split-checkbox-restored.png`、`webtoon-extensions.png`；Android 原生绘图沿 `app/build/reader-settings-parity/`。这些不是物理设备或 Windows 原生窗口验收，不宣称逐像素一致。

本批跨约 40 个文件，是设置入口、既有偏好/状态适配、图片/输入/章节呈现消费、双端生产接线测试及内部边界说明组成的同一能力；没有无关重构。Windows 使用 preview，不分配正式版本；Android 递增正式签名候选版本。两端产物来源和安装检查由各自清单记录，用户体验待验收。没有推送、更新网站公共能力声明或运行完整模块/全量测试。


## 后续：自动模式实际单页也启用单页图片项

基线 `7d7ab196f672dbbe3d333371659a53c911461cf8`。用户后续修正规则：自动布局实际为单页时，也显示并启用单页图片勾选项；实际变为双页时隐藏、停用并保留原值。本节取代上文历史批次对“显式单页”的限制，原批次测试记录保留。

- 共享规则改为要求实际单双页参数：横向、非显式双页、当前实际非双页时启用。Desktop 的设置 UI、实际拆页状态及图片绘图选项都读取既有 dualPageMode；Android 设置 UI 与 PagerConfig 都读取当前 viewer 类型，纵向仍不启用。复用现有自适应稳定时间及 viewer 切换，不另建宽高比判断或状态权威。
- `automatic-single-extras-red` 的 Android 原生 UI 复现自动实际单页没有宽图勾选项；Desktop 夹具初次引用自适应常量路径错误，改正后 `automatic-single-desktop-red` 复现相同缺口。均先验证真实失败再修改生产规则。
- `automatic-single-extras-green`：共享契约 6、Desktop 设置/持久化/图片策略 17、Android 设置 UI/实际 Config 13，共 36 项通过；该组合唯一剩余失败是 ktlint 的测试函数声明长行。缩短引用并由 `automatic-single-extras-close` 完成定向格式检查和 1 项真实 Activity→VM→SQLite→viewer 集成，组合共 37 项 focused 证据；不表示全量或全模块重跑。
- 新 Desktop 用例执行真实菜单点击、自适应窗口宽窄切换与稳定时间、勾选保存、实际拆页状态停用和恢复；新 Android UI 用例替换实际类型的单/双页 viewer 投影，Config 用例同时校验实际单/双页及默认继承。旋转/拆分互斥保持，隐藏不重置值。
- 查看 `.gradle-coordinator/reader-settings-native/automatic-single-checkboxes.png`：自动模式下宽图四项可见。该图为 Compose 离屏绘图，不是 Windows 实体窗口验收。
- 修改跨共享规则、双端消费与对应测试，超过八文件属于同一显隐/生效修复；没有数据库、flags、配对或分切算法变更。体验包和 Android Release 升级由项目入口构建并检查清单，不更新未验收的公共功能承诺。
