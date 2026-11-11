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
- `reader-sections-desktop-final`：Desktop 51 项中 49 项通过，包含新增过渡行为与原 first-composition/变换所有权回归；两个失败为新增指针夹具未指定 Primary 按钮、导航顺序断言仍按旧 Desktop 顺序。修正真实事件参数与 Android 顺序断言后，`reader-sections-close` 的 16 项全部通过。组合共 Desktop 51、Android 13、共享 6，即 70 项有效 focused 证据；不是最终代码全模块/全量重跑。
- `reader-inherited-single-close` 补验继承全局单页/自动切换，原值保留且 Config 随有效默认立即改变。`reader-eink-late-identity-red` 通过实际像素复现晚到 display-unit 身份提前清除闪烁；将翻页事件识别与遮罩计时分开后由 `reader-eink-late-identity-green` 同范围关闭，实际新翻页仍取消旧遮罩。
- 新原生绘图用例验证白→黑→清除的 E-Ink 遮罩；实际 Skia 像素验证正反 90°旋转；真实指针验证偏好更新后的区域反转；真实 PreferenceStore 重建覆盖全部新增值。旧变换 lease 与 first-composition 集成测试覆盖相关生命周期边界。
- 定向 ktlint 覆盖本次 Kotlin 文件，git diff --check 作为文本检查。中间编译/格式错误均在本轮关闭，不将编译成功冒充功能验收。

## 视觉与交付

查看实际 Compose 离屏图：`.gradle-coordinator/reader-settings-native/general-parity.png`、`general-options-enabled.png`、`split-checkbox-restored.png`、`webtoon-extensions.png`；Android 原生绘图沿 `app/build/reader-settings-parity/`。这些不是物理设备或 Windows 原生窗口验收，不宣称逐像素一致。

本批跨约 40 个文件，是设置入口、既有偏好/状态适配、图片/输入/章节呈现消费、双端生产接线测试及内部边界说明组成的同一能力；没有无关重构。Windows 使用 preview，不分配正式版本；Android 递增正式签名候选版本。两端产物来源和安装检查由各自清单记录，用户体验待验收。没有推送、更新网站公共能力声明或运行完整模块/全量测试。
