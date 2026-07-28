# Mihon Android 与 Mihon Desktop 最终实现差异

## 权威口径

本报告替代 2026-07-12 以移动中 `app/` 为“原版”的历史评分快照。最终比较只使用以下角色：

- 固定原版 Mihon：`main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8`；
- 当前 Android 构建版：本分支 `app/`，仅作为迁移后 consumer；
- 共享实现：当前 `commonMain` 生产代码，必须由固定原版 fixture 证明；
- Desktop JVM：`app-desktop/` 的 consumer 与必要平台 adapter；
- 机器权威：`app-desktop/src/test/resources/parity/parity-manifest.json`。

历史“原版更优 / Desktop 更优 / 不相上下”评分不再维护，因为它会复制并漂移 64 项状态。

## 最终结果

| 项目群 | Capability IDs | 终态 |
|---|---|---|
| 共享架构、状态与模块边界 | 3、4、7、12、93、95、96 | 7 `VERIFIED` |
| 网络、后台任务与通知 | 8、10、11、61 | 4 `VERIFIED` |
| 备份与跨端兼容 | 71–74 | 4 `VERIFIED` |
| 下载、更新与历史 | 53、56、57、59、62、64 | 6 `VERIFIED` |
| 书库与漫画详情 | 16、17、19、22、24、26、66 | 7 `VERIFIED` |
| 迁移与追踪 | 67–70 | 4 `VERIFIED` |
| 阅读器核心 | 9、43–45、47、49、51、54 | 8 `VERIFIED` |
| 源、扩展与挑战 | 28–40、87 | 13 `VERIFIED` |
| 系统集成、隐私与发布 | 81–84、86、92 | 6 `VERIFIED`；ID 85 `EXEMPT` |
| 设置、外观、无障碍与合规 | 88、90、91、94 | 4 `VERIFIED` |

总计 64 项：63 `VERIFIED`、1 `EXEMPT`、0 非终态。这里的 `VERIFIED` 表示用户入口、反馈、
production wiring、失败边界和保护测试均成立，不表示 Android 与 Desktop 使用相同平台 API。

## 固定原版对齐方式

- 平台无关业务规则进入 shared core，由当前 Android 与 Desktop 同时消费。
- WorkManager、Intent、PackageInstaller、Android View/WebView 等平台 API 不机械复制；
  Desktop 使用任务、URI、安装、窗口、凭据、文件和分享 adapter。
- 固定原版 provenance 由 path/symbol/line/blob 契约校验；当前 Android 或 shared 产物不能
  反向冒充原版。
- 备份、偏好、下载队列、阅读进度、源状态、追踪同步等迁移链保留真实历史 fixture 与失败反馈。
- Desktop Android shim 仅保留真实扩展 fixture 仍会链接或执行的 ABI surface；WebView 保持
  明确 Unsupported 边界。

## 有意保留的跨平台修正

以下差异不是“未对齐”，而是由 manifest 明确分类并有两端或 Desktop protection test：

- library membership、阅读进度、下载恢复、迁移与追踪队列采用事务/恢复增强，避免部分写入；
- 阅读器相邻 portrait 配对和双页可靠性修正保留为显式产品选项，不冒充固定原版默认；
- extension repository 与安装链增加 artifact authenticity、事务回滚和 fail-closed 安全检查；
- 设置搜索、主题和许可共享模型修复 blank/first-result、稳定排序及迁移边界；
- 平台错误统一为 Supported/Limited/Unsupported/Failed，避免把“没有 OS 能力”显示为成功。

精确 ID、分类、说明和 protection tests 只在 manifest 中维护，本报告不复制第二份逐项表。

## Desktop 产品差异

以下永久能力继续保留，且由 final runner 的 5/5 protection suite 或独立产品测试保护：

- Authors 独立入口与跨作品整理；
- Upcoming 预测页；
- 阅读器双页、edge matching 与 Webtoon 自动滚动；
- APK-to-JAR 与 consumer-driven Android ABI compatibility；
- 桌面键鼠、宽屏、普通文件系统、剪贴板/系统分享和目录入口；
- headless Test Mode HTTP 控制面。

### Test Mode 屏幕录制权限边界

Desktop 已移除 `POST /test/screenshot`、AWT Robot 屏幕捕获、Robot 截图快捷方法和未启用的视觉
回归客户端。旧端点返回 404，旧 `--screenshot-dir` 参数被忽略。macOS 平台验收也不再调用
系统 `screencapture`；URI 与 capture-affinity 使用进程、窗口几何、production state/action
和 adapter 结果验证。

这些变化不影响最终 13/13 场景、5/5 永久保护或 64/64 capability。Windows 窗口隐私仍由外部
验收宿主捕获目标窗口，截图能力不进入 Mihon bundle。应用仍没有麦克风或系统音频采集代码。

## 唯一平台豁免

ID 85 Android Widget 是唯一 `EXEMPT`。Windows/macOS 没有统一等价的 Android AppWidget
宿主，Desktop 不创建无真实系统能力的伪 Widget；用户批准、UI Unsupported 边界和保护测试
均记录在 manifest 与最终验证报告中。

Linux 仅保留防御性 Unsupported fallback，不是发行或验收平台。正式签名、公证和发布安装
交接属于 release operations，不改变 repository-local parity 结论。

## 最终证据

- 验证版本：`0.11.14.51.19a55d7`
- Windows/macOS source commit：`19a55d7c27e854a9a5b8baa27871b6d8e1c3608c`
- 最终运行：13/13 families、5/5 permanent protections、64/64 capabilities、`unmapped=0`
- 完整报告：`docs/superpowers/reports/2026-07-23-mihon-desktop-final-parity-verify.md`
