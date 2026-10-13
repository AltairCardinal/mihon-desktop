# 自有更新与问题反馈：实施与验证记录

基线：`e0b8c6a0ae`。隔离工作分支：`codex/update-feedback-20261013`。
原工作区的用户未提交改动未纳入本批。具体候选尚未获得用户验收。

## 范围

共享更新索引、版本和成功缓存；Android 校验下载与系统安装入口；Desktop 自动检查和手动发布页；
双端关于页的更新偏好与显式安全摘要反馈；GitHub Pages 索引与发布条目校验工具。
复用既有网络、偏好、通知、导航和安装校验链路，不增加服务器或自动遥测。

本批跨共享模块、双端适配、发布工具和维护说明，超过 8 个文件/400 行。
这些变更共同构成同一更新协议的生产消费和发布输入，不能按文件拆为可独立验收的功能。
主要风险是签名/包身份验证、缓存隔离、生命周期取消和通知去重；通过真实集成测试及独立审查覆盖。

## 红阶段与配置检查

以下均是明确限定类/用例的 focused 验证，没有执行完整模块或发布矩阵。
Gradle 由同一协调器串行执行，日志保存在本工作树忽略的 `.gradle-coordinator/`。

| 批次 | 结果与证据边界 |
| --- | --- |
| `update-feedback-red` | Windows 参数中的管道符导致命令启动失败；不作为行为红证据 |
| `update-feedback-red2` | 共享 domain/data 的未实现契约产生预期失败；Desktop 编译内存不足，未执行 UI 测试 |
| `update-feedback-desktop-red` | 共享更新 domain 4、data 6 通过；安全摘要 4、Desktop 反馈 1、Desktop 更新接线 4 出现预期失败；Android 自有构建开关检查失败 |
| `update-feedback-integration-red` | Android 自有更新开关配置检查通过；原生 Preferences 缓存 4 个预期失败；Android 18 个测试中 11 个预期失败，覆盖缺少入口/偏好/DI、错误下载成功、版本身份验证和重复提示；Desktop 测试代码编译错误导致本轮未执行，不能复用旧 XML 冒充本轮结果 |
| `update-feedback-final-red` | 共享更新 4、原生缓存 4、安全摘要 4 通过。Desktop 更新 10 项中旧 4 项通过，新 6 项正确红；About 反馈通过、偏好开关正确红。Android 下载 5、投递 4 均正确红；兼容 6 项中 3 项正确红；About 开关通过，反馈入口滚动查询失败待诊断。本轮两端均编译并实际执行 |

缓存失败揭示真实桌面 Preferences 的键/值长度限制及旧身份/架构缓存复用，不能以内存偏好测试代替。
Android 下载失败揭示原下载 worker 吞掉异常后返回成功，以及未校验文件即提供安装操作的问题。
Desktop 新增取消、通知订阅时序与停止/重启清理的回归测试，后续结果在本记录内补齐。

`update-feedback-ui-red` 的 Desktop 部分已实际执行：更新生产接线 10/10、既有 About 更新操作
6/6、控制器 8/8 通过，About 反馈和开关通过；第三项大字号/键盘测试在滚动断言处失败待关闭。
本轮包含一次新的 Android 界面接线红阶段，不把整条命令称为全绿。

`update-feedback-ui-red2` 关闭了 Android UI 测试的 WorkManager mock 配置错误后，
实际得到三项正确红：Main 展示未记已提示、下载按钮未携带完整元数据、Debug 边界缺说明。
Desktop 大字号测试的滚动失败定位为测试帧时间始终为零；改用递增真实纳秒帧时间，
保留滚动范围、按钮边界、键盘循环和关闭恢复焦点断言，不删除验收要求。

`update-feedback-focused-green` 的已完成范围（2026-10-13 18:40–18:44 CST）：

| 范围 | 实际结果 |
| --- | --- |
| Android About / 更新 UI / DI / 下载 / 旧兼容 / Recovery | 3 + 3 + 3 + 5 + 6 + 4 项通过 |
| Android Delivery | 原 5 项通过；新增在途渠道/开关变化及展示渠道归属 2 项正确红，重试不计为额外用例 |
| domain JVM | 更新契约 4、原生 Preferences 4、安全摘要 4、既有上游对齐 4 通过 |
| data JVM | 自有 HTTP 协议 6、既有资产选择/错误回归 10 通过 |
| domain Android | 同一更新契约 4、安全摘要契约 4 通过 |
| data Android | 同一自有 HTTP 协议 6 通过 |
| Desktop | 更新生产接线 10、反馈 3、既有 About 无障碍 5、runtime 关闭 updater 1 通过 |
| 格式 | 仓库 `spotlessCheck` 通过；没有执行产品完整模块测试 |

Android 最后两项竞态修复仅修改 checker：返回结果前复核自动开关和请求渠道，
展示去重使用发现时的渠道。Worker、Recovery 及共享模块未受该修改影响，通过证据可复用。

`update-feedback-repair-and-notes-red` 于 18:54:10 CST 产出修复后的 Android 结果：
Delivery 7/7、Compatibility 6/6、DI Wiring 3/3、UI Wiring 3/3 全部通过；
`:app:spotlessCheck` 通过。该命令整体失败仅因其中先执行的 Desktop U1 正确红：
实际页面未显示 release notes。Android 相关失败已关闭，不需重复未受影响的下载与签名验证。

该集中运行于 18:47:42 CST 结束，耗时 13 分 7 秒；整体失败仅因上文两项正确红，
不把命令名字中的 green 当作成功证据。Desktop 四张浅深主题、320 px/200% 字号图位于
`app-desktop/build/reports/feedback-visual/`，经实际查看确认上下滚动及按钮可达。
这些是 Compose 渲染/事件测试，不等同 Windows 原生窗口或系统剪贴板验收。

## 独立审查

主代理核对未由自己实施的共享协议、缓存信任边界及 Android 下载/安装接线；
反馈实施代理核对未由自己实施的发布工具和 Desktop 生命周期。没有新增第三个代理。
一轮审查及必要修复复审发现并处理：发布工具对序列化后大小/整数范围校验不足、
Desktop 重启前旧任务清理及自动检查覆盖安装状态、Android 在途请求的偏好变化和展示渠道归属。
各项均以具体行为红测推动最小修复；最后 Android 两项已完成静态复核和限定补验。
低风险反馈 UI 由实施者按实际事件、四张渲染图自查，主代理另查看代表图及关键接线。
U1 的 Desktop 页内更新说明在最终契约核对时发现遗漏，作为原范围补齐并单独执行红绿测试。

## 发布工具与网站

- `python scripts/tests/update-feed-test.py`：13 项通过，包含真实 CLI、发布响应校验和原子索引写入。
  新增行为均先观察对应失败，再修复；严格数字和序列化后字节数限制也经红绿验证。
- `python scripts/tests/build-android-test.py AndroidEntryTest`：9 项通过；实际构建回执缺失/错误的
  `forkUpdater` 字段会拒绝候选，旧上游 updater 和遥测仍关闭。
- `AndroidSdkArtifactTest` 中 4 个相关用例通过：真实 SDK 解析/签名校验、旧产物配置兼容、
  信任根不可由产物元数据替换、错误身份/版本/未签名/篡改拒绝。
  只使用隔离虚构 APK/临时测试密钥，没有安装设备或替换正式密钥。
- `npm ci --no-audit --no-fund` 与 `npm run docs:build` 通过；已读取构建产物
  `website/.vitepress/dist/updates/v1.json`，内容为合法空索引。

## 交付与未执行边界

`update-feedback-notes-green` 于 18:58:36 CST 成功结束：AboutUpdate 7、DesktopForkUpdate 10、
AboutAccessibility 5 共 22 项通过，补齐 U1 且保留既有行为。之后只有本记录和计划状态更新，
没有产品代码变化。上述组合证据覆盖本批影响范围，不表述为“最终代码重新全量通过”。

`update-feedback-preview` 使用 `scripts/build-desktop.sh preview` 成功完成。
版本仍为 `0.11.19.91.e0b8c6a`，没有递增；基线加本批未提交源码的真实指纹记录在 manifest，
不能将版本尾部 hash 误认为已经包含本批实现的提交。
实际产物是：

- `app-desktop/artifacts/preview/windows/20261013T105924Z-9a2c17af/Mihon-Desktop-0.11.19.91.e0b8c6a-unpacked/Mihon Desktop.exe`
- 同级上层 `preview-manifest.json`：build / sourceIntegrity / productionRuntime 均 PASS；
  nativeInteraction 为 NOT_RUN。运行时 PASS 只覆盖现有真实扩展安装链及版本检查，
  不能充当本次反馈剪贴板、外部浏览器或系统通知的原生窗口验收。
- 构建前后 production input digest：`f026c8d5587aeeb599f0731209dfa4addba38a5a212d98689420e31c81bc0f9c`。

复验命令范围均可从同名 `.gradle-coordinator/<key>.json` 读取；关键末轮可复现为：

```text
:app:testReleaseUnitTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateDeliveryTest --tests eu.kanade.tachiyomi.data.updater.AppUpdateCheckerCompatibilityTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateWiringTest --tests eu.kanade.tachiyomi.data.updater.ForkAppUpdateUiWiringTest :app:spotlessCheck
:app-desktop:jvmTest --tests mihon.desktop.ui.settings.AboutUpdateWiringTest --tests mihon.desktop.ui.settings.DesktopForkUpdateWiringTest --tests mihon.desktop.ui.settings.DesktopSettingsAboutExtensionAccessibilityTest
```

这些 Gradle 参数仍应通过项目协调器串行执行。更早共享、下载、反馈和控制器的通过证据按上文复用。
原始工作区后续由其他工作推进的 reader/tutorial 变更不包含在此隔离候选中，也未被覆盖。

没有分配正式版本，没有创建/上传 Release，没有部署 Pages，没有宣称线上更新已生效。
空索引有意不发布虚构候选；首次实际分发仍需要验收过的签名安装包和发布条目。
公共功能说明按项目规则等待用户对具体候选的验收后更新。

远端 main 的只读核验确认必须通过 `Build & Test App` 与 `Desktop JVM Tests`，并要求分支最新。
现有工作流会运行完整 Android 单元测试/未签名候选构建及完整 Desktop JVM 测试。
本轮没有通过创建 PR 偷跑该矩阵，也没有绕过分支保护；上线合入须另获该额外验证范围授权。
