# Android 双页布局修复与跨端显示契约

- 状态：COMPLETE（布局修复、审查、正式包与实机验收完成）
- active-task：无（全部完成）
- 范围：修复 Android 双页顶端错位、单侧异常放大/裁切；保护 Desktop 既有双页行为。
- 本计划是独立执行计划；不推进其他 reader roadmap 或改写 parity manifest 的既有完成证据。

## 问题与证据

2026-09-16 三星 SM-S9280 实机，包名 `app.mihon.desktop.fork`，版本 `0.19.4-aex.1`，
安装 APK SHA-256 `12f83d907ac6866c9fc6a321025bb78cb4945b8e1077f0d0274b007bff74671d`，与 rc9 正式包相同。

- 大小不一致：1440×3120 视口，左图片视图 720×1024，右图片视图 1125×1600，右侧父容器仅宽 720。
- 顶部错位：两个图片视图均 1125×1600，顶部 y=0；左槽占宽 1125、右槽剩宽 315。
- `DualPagerPageHolder.setImage` 先 `onDimensionsDecoded` 布局，后创建当前 sub-holder；新建侧保留 WRAP_CONTENT。
- 布局在视口宽高为零时返回，之后没有测量完成/尺寸变化补算。顶部错位实测与该路径吻合，具体先后顺序由 DP-01 红测试验证。
- Android 现有 `DualPageViewerAdapterTest` 实测对象为纯 PairingState，不能覆盖真实 View 测量。

## 产品约定与复用边界

1. 双页使用完整阅读视口、固定物理左右半屏槽；默认 Fit 保留完整图片、保持宽高比、垂直居中、向书脊对齐。
2. 同宽高比页面显示尺寸一致；不同宽高比允许自然留白与尺寸差异，不通过拉伸/隐式裁切实现强制等高。
3. Loading/Ready/Error/Retry、左右加载先后、缓存命中和视口 resize 不改变物理槽位，不因内容就绪重置整组视图。
4. 双页手势使用一致的组级缩放/平移，不能由单侧图片私自缩放造成大小分裂；单页行为保留。
5. 复用 `ReaderPairingState`、`ReaderPageImageView`、Desktop `DualPageDisplayUnitFrame` 与既有测试工具；不另建 session、下载器或解码器。
6. Android View 与 Desktop Compose 是平台 adapter；不移植 Desktop UI，不把像素/窗口几何加入 ReaderSessionCore。共享验收向量/显示约定，分别验证真实 production renderer。
7. 保留 Desktop 封面槽、手动配对、宽图等既有能力；本轮不统一历史上明确允许的平台独有封面策略。

## 执行批次

### DP-01：稳定双页显示与跨端回归（一个内聚功能批次）

- [x] 先以真实 Android holder/image view 测量测试复现左右加载顺序、测量前就绪、晚到第二页与 resize；确认失败原因后实现固定槽位及正确图片对齐。
- [x] 覆盖双页缩放/重置、单页与错误/重试边界；只修复与本问题有关的生产接线。
- [x] 使用同组几何验收向量覆盖 Android 与 Desktop renderer，保护默认 Fit、左右物理槽和居中语义；不以源码扫描或复制 production 算法替代测试。
- [x] 独立审查真实 diff 与红绿证据，修复阻塞项；相关 focused、集成与格式检查通过。
- [x] 更新架构文档中的显示契约与测试边界，提交本批次实现、测试及状态。

实施子代理承担 Android production 与测试及必要 Desktop 回归测试；主代理负责接口约束、发布脚本与验收。
若超过 8 文件/400 行，以真实 View fixture、手势及跨端契约同属该行为说明内聚性，不机械拆分。

### DP-02：正式 Android 更新与实机验收（依赖 DP-01）

- [x] 沿用 fork 包名与原发布证书，增加版本号；同步 release identity 校验，完成受影响测试及正式 R8 APK 构建签名。
- [x] 保留用户数据原位升级已连接三星实机，核验 APK 哈希/证书/版本和真实 ReaderActivity。
- [x] 在原问题章节核验双页居中、等比例同尺寸、翻页/返回与旋转或等价视口变化；自动化补充可控图片与加载时序证据。
- [x] 记录真实结果、产物路径、限制；提交发布配置和最终验收状态。未通过不得勾选。

本轮默认不修改 Desktop production，因此不做无关桌面版本发布。若实施确需改变 Desktop production，按项目构建脚本完成 Windows 发布与运行验收并明确原因。

## 流程预算与验证

- 最多两个子代理：一个主要实施者、一个独立审查者；主代理不重复实施委派范围。
- 重型 Gradle 由一个协调者串行执行，使用 `scripts/gradle-coordinator.py`；外层超时先查状态。
- 红绿重构仅 focused tests；稳定批次运行相关 Android/跨端集成与格式检查；收口全量测试最多一轮。
- 独立审查一轮、必要修复复审一轮；预计 60–100 分钟，成本主要在测试、R8 构建和实机验收。
- 失败仅补验相关路径；依赖下载遵守代理规则。签名密钥缺失不得替换；设备断开保留构建证据并报告实机阻塞。
- 不清除应用数据、不触碰 `testfile/` 用户未跟踪内容、不改原图或阅读数据库模型。

## 执行证据

checkbox 仅在实现、审查、验证与提交全部完成时勾选。

- DP-01 最终有效 RED：`.gradle-coordinator/dp01-red-final-fixture.log`，`:app:testReleaseUnitTest --tests '*DualPagerPageHolderLayoutTest'` exit 1；恢复原 HEAD 的 holder 并使用修正 UI 线程后的 fixture，真实 SSIV 已成功绑定，首次测量前就绪/晚到图片/Ready collector/共享向量均出现 width=1440、期望720，双击整组 scale=1、期望2。仅替代 JVM 缺失的静态图片格式嗅探原生边界，保留真实尺寸解析、图片绑定和 View 测量。
- 子代理后台协调器启动被自动策略拒绝，未启动进程；改用前台 `foreground` 已成功，未提升权限。
- 早期 fixture 的 DI 缺失、原生格式嗅探缺失、Unconfined dispatcher 在无 Looper 线程创建 View，均不计产品失败。`dp01-red-layout`/`dp01-green-layout` 等早期结果被最终 corrected fixture 取代；新增断言确保正常 setImage 不产生意外错误，防止仅几何通过掩盖绑定错误。最终GREEN、跨端与边界回归证据见后文。
- 内聚性：预计 diff 超过 400 行，主要来自真实 View/事件分发测试夹具、Android/Desktop 共用几何向量及两端断言；production 限于 Android 双页 holder 的布局与组手势，不按测试类或行数拆分提交。
- 独立审查第一轮：CHANGES_REQUESTED，仅一项 P2——`onSizeChanged` 中读取尚未重新 layout 的 child 宽高作为缩放 pivot，放大后 resize 可能偏移。Android `dp01-green-ui-thread` 7/7、Desktop `dp01-desktop-fit` 7/7 经审查者核对 XML；此前测试缺少 zoom+resize 组合。原实施者补该 RED/修复，并补真实双指与 retry 按钮接线；预算内一次修复复审仅核对这些路径，预计额外 5–10 分钟。
- 修复复审：原 pivot 与连续 pinch 坐标反馈问题关闭，`dp01-green-resize-gesture` 9/9；但错误态全域放行触摸会让按钮外的存活 SSIV 再次独立缩放，列为新 P2。原 Retry 测试 `performClick()` 未覆盖事件路由。已要求真实 dispatch RED/修复；2026-09-16 向用户申请追加一次约5分钟定向复审，批准前不启动额外审查或正式发布。
- 最终错误态 RED：`dp01-red-error-touch` 记录存活 SSIV 接收4次触摸（期望0）、组双指不放大；改为仅可见按钮命中范围放行。Retry 使用真实 `dispatchTouchEvent` 坐标点击，经 loader→Ready 清理错误。
- 最终 focused：`dp01-final-related` Android 33/33（holder9、pairing12、adapter8、adjacent4）行为测试通过；该进程随后因测试排版失败，纯排版修复后 `dp01-final-format` 的 `:app:spotlessCheck` exit0。`dp01-desktop-fit` Desktop 10/10（identity7、layout3）通过，实际 renderer/pipeline 共用5组向量。`git diff --check` 通过。最后错误路由修复已由用户追加授权的定向复审确认PASS。
- DP-02 收口全量：`dp02-full-tests` 串行运行 `:app:testReleaseUnitTest :app-desktop:jvmTest spotlessCheck`，exit0，6m42s；Android 424 tests/0 failure/0 skip，Desktop 3070 tests/0 failure/2 skip（macOS JXA 与非release专属假设），即3068通过；全仓格式通过。本轮唯一全量已完成，不重复运行。
- 发布准备：`dp02-release-build` 按 `scripts/android-fork-release.init.gradle` 构建 code20/`0.19.4-aex.2`、R8开启的正式配置 APK，exit0，2m32s；R8有Android Window/jsoup缺失类告警，未调整忽略规则，未阻断构建。沿用原4096位发布密钥签名并验证证书，日志 `.gradle-coordinator/dp02-signing.log`。
- 已验收产物：[Mihon-Fork-0.19.4-aex.2-rc1-universal.apk](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/Mihon-Fork-0.19.4-aex.2-rc1-universal.apk)，SHA-256 `47133d98ae7cef00b94f35fd3e8d0d6f72405bcb4ce10b9241a03ba792b184fa`。包名 `app.mihon.desktop.fork`，versionCode20，versionName `0.19.4-aex.2`。产物构建源为 HEAD `4bea9ad34e` 加本任务当时未提交 diff；同一 production/test diff 已提交为 `9ade703ae`。审查后未更改功能代码，复用同一受测二进制，不重新构建。
- 追加定向复审：用户已明确同意；复用 `dual_layout_review` 确认PASS，错误态按钮矩形路由及真实dispatch测试有效，无剩余审查阻塞。未改动已通过全量与构建的代码，不重复全量；DP-01在本批次提交，DP-02继续原位升级与实机验收。


## 最终实机验收与交付

- DP-01提交：`9ade703ae`。DP-02发布脚本版本升级与本节验收随本批次提交；共两个内聚提交，无纯状态推进提交。
- 三星 SM-S9280 原位 `adb install -r` Success；核验已安装 `app.mihon.desktop.fork` 的 versionCode20/`0.19.4-aex.2`，设备 `base.apk` SHA与上述签名产物完全一致。未清除用户数据，书架原书与下载章节仍能打开。
- 原问题 Ch.3 双页：视口1440×3120，两侧各720×1024，y=1048，右页不再以1125×1600溢出半屏；[竖屏实机证据](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/portrait.png)。
- Ch.1翻页与双击：实际图片内容切换后仍居中等大；两页一起放大，再次双击恢复。证据：[整组放大](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/group-zoom.png)、[重置](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/group-reset.png)。连续双指与错误态Retry由真实dispatch自动化覆盖；不声称已在实机注入双指或网络故障。
- 横屏视口3120×1440：两侧图片均1012×1440，左右槽各1560宽，图片向中间书脊对齐；[横屏实机证据](D:/Shell/Github/mihon/app/artifacts/android/0.19.4-aex.2-rc1/acceptance/landscape.png)。已返回竖屏，系统user_rotation/accelerometer_rotation及插电亮屏设置均恢复原值0。
- 功能边界：保持比例的Fit；同宽高比图片显示等大，不同宽高比允许自然留白与尺寸差异；Desktop production及平台独有封面策略未改。Desktop仅共用向量验证真实renderer，因此本轮没有无关桌面版本发布。
- 另行记录的导航观察：本次首次进入Ch.1/Ch.3时先显示章节过渡页，向章内返回后正常显示双页；尚未确定该现象产生时间或因果。该配对/章节落点问题不属于本轮固定槽位与组手势修复，不以本次几何PASS声称导航异常已修复；后续应独立定位，不在本任务无授权扩大实现范围。
