# align-reader-core 验证报告

日期：2026-07-15
验证区间：`20c56cbc6b62c4607c4d28709734142cc127a8b3..ac118049fa86909020a5beee7e6441d6e8523d28`

## 2026-07-18 authority correction

本报告的 PASS 结论证明了当时共享实现与双端 wiring 一致，但其中“pairing 默认来自 Android 原版”的 provenance 结论不成立。固定 `main@6fbf6dfca203d99d6dd32137f2df97ced40c81b8` 没有相邻 portrait 页配对算法；`ReaderPagePairing` 与当前 Android facade/state 来自 fork 提交 `bef51fc69`，现重分类为 Android/Desktop 共用双页产品增强。本勘误不改动当时历史审查记录，详细证据和当前维护边界见 [Reader authority](../../architecture/reader-authority.md)。

## 结论

PASS。Critical 0、Warning 0、Suggestion 0。实现满足 proposal、OpenSpec delta spec、OpenSpec design 与技术 Design Doc，可进入归档。

## 完整性

- OpenSpec tasks：16/16 完成。
- Requirements：7/7 有 production 实现与测试证据。
- Scenarios：12/12 有 production 路径、自动化或运行时证据。
- Parity：9、43、44、45、47、49、51、54 均有可执行 production wiring 证据。

## 正确性与一致性

- `domain/common` 是页面、章节状态、拆页/配对、导航、跳过、滤镜、预加载和缓存契约的唯一业务语义来源。
- Android 与 Desktop 仅保留 Bitmap/View/Coil 和 Skia/Compose/文件输入等平台 adapter。
- Desktop 的 edge matching、Webtoon 自动滚动、键鼠导航、右键保存继续通过显式产品选项与集中回归门禁保留。
- Android native decoder 只接收满足尺寸上限的 2 次幂 sample；production decoder → native adapter 调用链有可执行测试。
- Library、Manga Detail 与 Android reader 使用相同持久化 Manga filter metadata 与共享 skip policy。
- 未发现 OpenSpec design、技术 Design Doc 与实现之间的漂移。

## 新鲜验证证据

- Comet build guard：`./scripts/build-desktop.sh test-only`，PASS。
- Domain：92/92，0 failure/error/skipped，强制非缓存执行。
- Android reader/Coil：56/56，0 failure/error/skipped，`--no-daemon --rerun-tasks`，正常退出。
- Desktop：1484 tests，0 failure/error，2 个既有条件跳过；Task 6 定向 reader/UI/parity 339/339。
- Test Desktop：15/15，0 failure/error/skipped，强制非缓存执行。
- Spotless：PASS。
- `openspec validate align-reader-core --strict --json`：valid=true，0 issues。
- `git diff --check 20c56cbc6..HEAD`：PASS。
- 完整区间独立代码审查：Critical 0、Important 0、Minor 0。
- 完整 OpenSpec 规格审查：Critical 0、Warning 0、Suggestion 0。

## 三平台运行时验收

### Android

- API 36 x86_64 AVD `MihonTask7Api36`；安装 `app-x86_64-debug.apk`，`app.mihon.dev` / `0.19.4-7722`。
- `MainActivity` 与 `ReaderActivity` 实际启动；本地 2 页漫画验证章节打开、LTR/RTL 选项、滤镜、Boundary 与代表性 1080×2400 页面。
- 损坏页面显示 decoder Error 与 enabled/clickable Retry；恢复有效页面并重新进入章节后正常渲染。
- 本地 `DirectoryPageLoader` 的 Retry 按 Android 原版契约为 no-op；在线 `HttpPageLoader` 的同章 Retry 由 production-chain 集成测试验证，不为本地源新增原版没有的行为。
- logcat 中 FATAL、OOM、SIGSEGV 为 0。

### Windows

- 固定 EXE：`D:\Shell\Github\mihon\app-desktop\tmp\mihon-dist\main\app\Mihon Desktop\Mihon Desktop.exe`。
- mtime：2026-07-15 19:02:14 +08:00；完整窗口标题：`Mihon Desktop 0.11.14.21.9f83cfb`。
- `desktop-smoke-test.sh` 退出码 0；reader runtime/Test Mode 证据通过。

### macOS

- `ssh mbp`：macOS 14.8.4、x86_64；使用安全临时 clone 的当前快照运行 Skia/cache/preloader/ScreenModel 测试，PASS。
- 使用仓库构建脚本部署并启动 `/Applications/Mihon Desktop.app`，版本 `0.11.14.22.9f83cfb`。
- bundle mtime、Info.plist、PID、可执行命令与 CoreGraphics 窗口层均为本轮新构建证据。
- 系统辅助功能/屏幕录制权限限制标题与截图读取；该限制不影响 native 测试、bundle 身份、进程与窗口存在性结论。

## 安全、范围与分支

- 未新增硬编码凭据、危险反序列化或平台无关层对 Android/Skia 类型的依赖。
- SDK、AVD、Gradle cache、构建产物、运行截图和旧 Task 3B 草稿均未进入 tracked 提交。
- 用户已明确选择在当前分支继续；保留 `claude/pensive-vaughan`，不创建 worktree、不合并、不推送、不创建 PR。
