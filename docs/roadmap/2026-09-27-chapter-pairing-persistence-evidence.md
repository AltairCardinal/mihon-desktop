# 章节双页调整持久化：实施与验收证据

日期：2026-09-27，真机升级补验于 2026-09-28。对应 [需求](../2026-09-27-chapter-pairing-persistence-requirements.md)与 [Roadmap](2026-09-27-chapter-pairing-persistence-roadmap.md)。CP-01、CP-02 已提交；CP-03 的 Desktop 正式产物、Windows/macOS A1–A5 运行验收、当前源码 Android/共享全量测试及 Android release 构建均已完成。Android fork 正式签名升级包已在用户真机覆盖安装并启动；用户选择自行验收阅读器内的实际结果。

## 产物与环境

| 平台 | 正式产物 | 已核验 |
| --- | --- | --- |
| Windows | [未打包 EXE](D:/Shell/Github/mihon-cp03-final-20260927/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.64.7c5320a-unpacked/Mihon%20Desktop.exe)、[ZIP](D:/Shell/Github/mihon-cp03-final-20260927/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.64.7c5320a-windows.zip) | `scripts/build-desktop.sh build-only` 的 `Final unpacked EXE:` 指向上述实际存在文件；ZIP SHA-256 `16a44abea41e6fdf66116ad1919248b9d672ec99998c6651e2e4840e1b77e5c2`。正式产物的 Test Mode 与 production 图片链已验收。 |
| macOS | [应用包 ZIP](D:/Shell/Github/mihon-cp03-final-20260927/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.64.7c5320a-macos.zip) | 独立工作树运行 `scripts/build-desktop.sh build-only`，正式应用部署到 `/Applications/Mihon Desktop.app`；ZIP SHA-256 `d1587fb001e7c675cb5c20b983a708895fbebbfa26cc7e7d7d0af5bec6e027ef`，与 Mac 生成包一致。原有 Mac 工作树未改动。 |
| Android | [当前 fork 正式签名升级 APK](D:/Shell/Github/mihon/app/artifacts/android/Mihon-0.19.4-aex.14-cp03-9bf1e86-universal.apk)、[既有 aex.13 APK](D:/Shell/Github/mihon/app/artifacts/android/Mihon-0.19.4-aex.13-pairing-81be8f8-universal.apk)、[默认身份的当前源码测试 APK](D:/Shell/Github/mihon-cp03-final-20260927/app/artifacts/android/Mihon-0.19.4-cp03-7c5320a-universal-test-signed.apk) | `aex.14` 用当前 CP-03 production 源码、fork 身份和 R8 正式配置构建；`app.mihon.desktop.fork`、versionCode 32、versionName `0.19.4-aex.14`，沿用 aex.13 证书 SHA-256 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，v2/v3 签名校验通过，APK SHA-256 `ddf9319a8351056bbae636bea8ed9fcd074d7a63af9a9171a27dbcea81f8cd43`。用户真机旧包与此处 aex.13 APK 的 SHA-256 相同；`adb install -r` 升级成功，UID `10358`、数据目录与首次安装时间保持不变，MainActivity 启动后进程持续运行，启动日志未见致命异常。默认身份测试 APK 的签名和包名不同，仍只用于隔离 AVD 验收。 |

Windows、macOS 均使用正式应用的隔离 Test Mode profile、真实 SQLDelight 数据库与 production Reader。Windows 调整按钮由用户在实际窗口点击；macOS 的 `CGPreflightPostEventAccess()` 返回 `true`，通过真实 CGEvent 点击、滑块选页、清除及重启核对数据库。第一次 Mac 新 profile 缺少测试漫画与章节，实际 UI 显示配对读取失败；补入既有隔离测试漫画/章节后重新验收，不能把缺数据的启动计入 A5。Android 使用新建的 API 36 x86_64 AVD、本地测试漫画与正式签名 APK，曾在实际界面完成 A1–A4；测试期间没有清除原有 AVD 数据。

## 验收矩阵

页码叙述为用户可见的 1 基页码。Mac Test Mode 的 `OPEN_PAGE` 事件是 0 基索引，报告已换算。矩阵保留早期版本的正式运行记录；新版 `0.11.19.64.7c5320a` 的补验另见矩阵后段落，边界页序可能因测试时选中的位置不同。

| ID | 自动化与正式运行证据 | 状态 |
| --- | --- | --- |
| A1 | Desktop Compose 实际按钮→文件库重开→新 Screen，Android 实际按钮→仓库→新 ViewModel/viewer 的 focused 测试通过。Windows 实际按钮写入后重启，首个当前组合为 `[5,6]`。Android 实际按钮写入并再次翻到 `[5,6]` 后，库为 schema 40、边界 3、`last_page_read=5`（第 6 页）；强制结束并重启，显式打开这个已读章节后翻到续读页仍显示 `[5,6]`。Mac 正式窗口真实点击后数据库新增边界 3，重启 production 首组由默认 `[4,5]` 变为 `[5,6]`。 | Windows/Android/Mac 保存与恢复通过。 |
| A2 | Windows 经真实点击保存两个边界 `{1,3}`，A→B→另一漫画同名 C→A 后仅 A 保留。Android 实际 A 调整后，B 和另一漫画同名 C 均保持默认组合。Mac production A 的首组为 `[5,6]`，B/C 首组均为默认 `[4,5]`；Mac 仅准备一个边界。 | Windows 完整通过；Android/Mac 章隔离通过。 |
| A3 | Windows 点击撤销两个边界后记录与边界表为空，重启为默认；Android 点击撤销后两表为空，重启图像为默认 `[4,5]`。Mac 正式窗口再次真实点击后配对与边界表为空，修订号保持 1；重启 production 首组恢复默认 `[4,5]`。 | Windows/Android/Mac 按钮清除与重启恢复通过。 |
| A4 | Windows 在正式窗口切换双页→单页→双页，记录保留且双页恢复；Android 实际切换 RTL 双页→RTL 单页→RTL 双页，恢复 `[5,6]`。Mac 以单页和双页参数分别打开真实 Reader，记录未删除，返回双页后首组为 `[5,6]`。方向、窗口与 Activity 重建另由平台 focused 测试覆盖。 | Windows/Android 运行通过；Mac 模式重开通过。 |
| A5 | Android `AndroidChapterPairingPersistenceWiringTest` 覆盖宽图延迟回调；Desktop `DesktopReaderProductRegressionTest` 覆盖手动边界优先于自动匹配。修复后 Windows/macOS 版本 `0.11.19.64.7c5320a` 的在线 Test Mode 均经 production `OPEN_PAGE`/`DECODE` 呈现首图，各有 6 次真实在线图片请求；实际窗口调整后，切到下载目录的 production Reader 再次呈现，记录未丢失。Windows/macOS 均重启应用后再次打开下载目录，边界仍在。 | 在线→下载正式运行与宽图/自动匹配回归通过。 |
| A6 | `DatabaseMigrationCompatibilityTest` 用真实旧库执行 39→40 迁移；`ChapterPairingRepositoryIntegrationTest` 覆盖文件库重开、事务故障回滚。Android 隔离 AVD 中将含 2 部漫画、3 章及原进度的 v39 数据库交给已签名发布 APK 启动；运行后 schema 为 40，原章节与进度不变，新配对表为空，`PRAGMA integrity_check=ok`。 | 共享文件库及 Android 发布版旧库迁移通过；事务故障回滚由集成测试覆盖。 |
| A7 | Desktop Model 与 Android 平台 wiring 的 focused 测试覆盖延迟读取、立即退出、跨章晚结果和同章串行修改。 | focused 测试通过。 |
| A8 | 共享记录契约、文件库集成及双端 Model/Viewer 测试覆盖页数与格式失效、非法边界、I/O 错误后的重试及本次默认。 | focused 测试通过。 |
| A9 | 文件库集成测试覆盖章节删除级联；Android `DualPageProgressProductionWiringTest` 与 Desktop Model 测试覆盖布局调整不冒充阅读进度。Windows 真实按钮调整期间既有 `last_page_read=5` 保留；Android 在调整后立即退出的库中 `last_page_read=4`，没有把新出现的第 6 页直接记成已读。Desktop `DesktopChapterPairingComposePersistenceTest` 新增真实下载目录删除后关闭重开文件数据库的集成用例，配对边界与进度保持。 | 进度、下载删除保留及章节删除级联通过自动化验证。 |
| A10 | 两端真实调整按钮、共享仓库与 DI 的集成测试通过；Windows/Android/Mac 正式窗口点击实际按钮并检查库与重启恢复。Desktop 临时文件保持会话限定提示。 | Windows/Android/Mac 按钮运行通过。 |

新版运行复验：Windows 用户在在线章节实际点击后，文件库出现 `chapter_pairings(420101, revision=1, page_count=8)` 与边界 `5`；转下载目录时 `firstPagePresented=true`、production `OPEN_PAGE=5`，重启后边界 `5` 仍在。macOS 新 profile 在在线章节实际点击生成边界 `5`；重启、另一漫画章节、单页→双页重开分别验证恢复、章隔离和模式保留；经实际滑块和按钮逐一撤销边界 `3`、`5` 后，两张配对表为空，重启后仍为空。页码和边界数据库字段均为 0 基；上述章节均为隔离测试资料。

## 构建与测试结果

- CP-01 提交 `b1a107c6209a1b98c90628aa693b94300150ee72`：相关 `spotlessCheck`、domain 1/1、真实 SQLite 23/23、Desktop 97/97 通过。CP-02 提交 `81be8f82a577f821aa3d4d5f6b54b9e201a51bb3`：Android 相关 57/57、SQLite 6/6、Desktop 30/30，过渡页修复后 Android 16/16 及相邻 viewer 6/6，格式检查通过。详细 TDD 与审查记录见 Roadmap。
- CP-03 首次完整 Android/共享验证：`python scripts/gradle-coordinator.py run --key cp03-android-full -- .\gradlew.bat --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck`。`domain:jvmTest`、`:test-desktop:test` 和格式检查通过；`:app:testReleaseUnitTest` 297 项中 16 失败、4 跳过，其中旧同步迁移测试的 12 项在 Windows release JVM 启动时因 sqlite3x JNI 无法加载失败，另外 4 项为 `DualPagerPageHolderLayoutTest` 的超时/内存与时序失败。该类单独 focused 复跑通过；旧迁移测试在 debug JVM focused 4/4 通过。`:data:jvmTest` 752 项中 1 项因既有测试把 schema 版本写死为 39 失败，测试改为校验不低于历史版本后 focused 10/10 通过。当时尚未执行第二次全量；后续结果见下文。
- CP-03 首次完整 Desktop 验证：`scripts/build-desktop.sh full-tests`，3208 项中 1 项失败、3 跳过。失败的 `DesktopProductCapabilityContractTest` 是 parity manifest 的 9 处证据行号过期；修正后该契约 focused 通过，另有 Test Mode JSON/导航 focused 红→绿及 `spotlessCheck` 通过。当时尚未执行第二次全量；后续结果见下文。
- 获得继续验收授权后，第二次完整 Desktop `scripts/build-desktop.sh full-tests` 成功，`BUILD SUCCESSFUL in 4m 59s`。第二次 Android/共享命令 `--no-parallel --max-workers=2 --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck` 中，domain、data 与 Desktop 测试通过；Android release JVM 的旧迁移用例因 sqlite3x 仅能在 Android 运行而跳过，debug JVM focused 4/4 通过，发布 APK 的真实 v39→40 迁移已另行通过。双页布局类首次运行有 2 项超时、1 项 512 MiB 堆溢出，三项重试均通过，但 Gradle 全量仍判失败；格式检查还报 1 处 import 顺序，已修正。
- 对 release JVM 测试类设置 1 GiB 堆与每类独立进程后，双页布局 focused 30/30 与格式检查通过。Desktop 下载删除文件库用例及 `spotlessCheck` 通过。此时仍需 Android 完整复验，不能以 focused 结果代替；后续全量结果见下一项。
- A5 正式 Windows 运行暴露在线章节首次可见页只到 `PAGE_LIST_READY`，没有图片请求。先新增真实在线 MockWebServer、文件数据库重开及 Compose 首图的红测，确认失败，再让 `layoutOnly` 视口调度可见图片且不写阅读进度。初版修复在缩放后重复上报同一页进度，`AdaptiveReaderViewportTest` 红测后拆分布局调度与进度去重身份。共享 `ReaderSessionCoreTest` 及平台 focused 测试通过，新增 Test Mode 网络入口测试执行真实 production 读取链；独立聚焦审查通过，无阻塞项。
- 当前代码的完整 Desktop `scripts/build-desktop.sh full-tests` 通过，`BUILD SUCCESSFUL in 5m 14s`，3211 项、3 跳过、0 失败；此前同一修复中证据行号和重复进度导致 2 项失败，已修正并定向复验。当前代码的 Android/共享全量执行 `--no-parallel --max-workers=2 --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck`：Android 652 项、0 失败、7 跳过；domain 568/568、Test Mode 52/52、格式检查通过。data 首跑 752 项中仅 `SyncS2ContractTest` 因前序同步协程在关闭的 SQLite statement 上报错，属于测试开始前的 `UncaughtExceptionsBeforeTest`；该类随后 focused 6/6 通过，获批追加的完整 `:data:jvmTest` 复跑 752 项、0 失败、1 跳过，`BUILD SUCCESSFUL in 14m 42s`。最终四套模块及格式检查均有当前代码的全量绿色结果。
- 发布构建：`scripts/build-desktop.sh build-only` 在 Windows 和 macOS 均成功，版本 `0.11.19.64.7c5320a`；Windows 构建脚本还通过 Test Mode/production 扩展运行验收。当前代码的 Android `:app:assembleRelease` 成功，产出的通用未签名 APK SHA-256 为 `5d06db98ba903913287a83ae8fab95bfb2af384de970a0271e1373765a045def`；为在隔离 AVD 上做当前代码启动验收，另用本机调试证书签名该 release 代码包。Android SDK 的 `android-36/android.jar`、`36.0.0/aapt2.exe`、`platform-tools/adb.exe` 均存在。正式产物与安装用途见上表。
- 真机升级补验：`scripts/android-fork-release.init.gradle` 与 `scripts/sign-android-fork-release.ps1` 将 fork 正式身份推进到 `aex.14`/code 32，并复用仓库外原发布密钥。第一次构建在独立工作树的 R8 `classes.dex` 遇到文件句柄占用；定位到空闲 Gradle daemon PID 105840 后，用户明确要求结束该进程，旧输出文件随即解锁。改用没有锁定输出的主工作树构建，`FORK_RELEASE_CONFIG_OK` 与 `BUILD SUCCESSFUL in 2m 59s`；生产源码与已通过全量验证的 CP-03 相同，主工作树额外改动仅在测试和文档。签名脚本核对身份、版本、zipalign 和证书；安装前真机 aex.13 APK 哈希与仓库验收包一致。`adb install -r` 成功，真机 aex.14 启动与进程存活通过。阅读器内的人工调整、重启恢复和撤销尚待确认，不能把启动冒充完整实机验收。
- 用户在真机完成一次实际调整并回复“真机已调整”；随后 ADB `force-stop` 和 `am start -W` 冷启动成功，进程 PID 从 15467 变为 4062。用户明确选择自行验收阅读器画面；本报告没有获得其恢复/撤销结果回执，不将该部分标为 agent 已验收，也不再操作真机。

## 剩余边界

CP-03 的实现、独立聚焦审查、当前代码全量测试、Desktop 正式构建和 Windows/macOS A1–A5 运行验收均已完成。当前源码的 Android fork `aex.14` 已在真机同包名、同签名升级并启动；阅读器调整后的恢复与撤销由用户自行验收，结果尚未回报。此前正式签名 aex.13 已在隔离 AVD 完成配对恢复、章隔离、清除、模式切换和 v39→40 迁移的实际运行验收；当前共享会话修复对 Android 仍采用原有 `recordProgress=true` 默认路径，并由当前完整 Android 测试覆盖。原有 AVD 的版本 32 数据缺少历史迁移本应加入的 `sync_runtime_runs.uploaded` 列，该不一致旧库启动失败；保留原数据后改用结构完整的 v39 隔离库完成发布版迁移，不能据此推断任意正常 v32 数据均迁移失败。

本功能仅保存在当前设备的本机数据库，不进入同步或备份。没有数据库章节身份的 Desktop 临时文件只在本次阅读有效。相同页数的内容重排不能自动发现，用户须再次使用现有调整按钮。Android 进程在事务提交前被强杀时，只保证上次已成功保存的状态。
