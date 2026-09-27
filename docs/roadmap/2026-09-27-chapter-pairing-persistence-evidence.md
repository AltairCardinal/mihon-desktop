# 章节双页调整持久化：实施与验收证据

日期：2026-09-27。对应 [需求](../2026-09-27-chapter-pairing-persistence-requirements.md)与 [Roadmap](2026-09-27-chapter-pairing-persistence-roadmap.md)。CP-01、CP-02 已提交；CP-03 的正式产物已构建，macOS 按钮验收已补齐。Android 全量复验及 A5 正式运行仍未通过，因此保持未勾选。

## 产物与环境

| 平台 | 正式产物 | 已核验 |
| --- | --- | --- |
| Windows | [未打包 EXE](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.62.81be8f8-unpacked/Mihon%20Desktop.exe)、[ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.62.81be8f8-windows.zip) | `scripts/build-desktop.sh build-only` 的 `Final unpacked EXE:` 指向上述实际存在文件；ZIP SHA-256 `c1ec73755b4f0448c5fea5fde8fa0276fc2ae477456f90e803bf9b108ff8c9dd`。 |
| macOS | [应用包 ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.62.81be8f8-macos.zip) | 独立工作树运行 `scripts/build-desktop.sh build-only`，生成 `Mihon Desktop 0.11.19.62.81be8f8`；ZIP SHA-256 `d62480b73fa29a1f000762f366d05ae45f81cb5d63b550513193184fce4fbe22`，与 Mac 生成包一致。原有 Mac 工作树未改动。 |
| Android | [通用签名 APK](D:/Shell/Github/mihon/app/artifacts/android/Mihon-0.19.4-aex.13-pairing-81be8f8-universal.apk) | `:app:assembleRelease` 成功；包名 `app.mihon.desktop.fork`、versionCode 31、versionName `0.19.4-aex.13`，v2/v3 签名及预期证书校验通过，SHA-256 `5030957cc15918b0de29cb4ce451c77acaf6012ff52df65219767e60abed8ea4`。同一次构建的 x86_64 签名 APK 安装到 API 36 隔离 AVD 完成运行验收。 |

Windows、macOS 均使用正式应用的隔离 Test Mode profile、真实 SQLDelight 数据库与 production Reader。Windows 在实际窗口中点击调整按钮；macOS 获得辅助功能输入权限后，`CGPreflightPostEventAccess()` 返回 `true`，通过真实 CGEvent 点击“调整跨页”，并在退出、重启后核对数据库及当前显示页。此后 Mac 图形会话锁屏，后续在线场景没有窗口，不能把这些启动当作 GUI 验收。Android 使用新建的 API 36 x86_64 AVD、本地测试漫画与正式签名 APK，在实际界面操作；测试期间没有清除原有 AVD 的数据。

## 验收矩阵

页码叙述为用户可见的 1 基页码。Mac Test Mode 的 `OPEN_PAGE` 事件是 0 基索引，报告已换算。

| ID | 自动化与正式运行证据 | 状态 |
| --- | --- | --- |
| A1 | Desktop Compose 实际按钮→文件库重开→新 Screen，Android 实际按钮→仓库→新 ViewModel/viewer 的 focused 测试通过。Windows 实际按钮写入后重启，首个当前组合为 `[5,6]`。Android 实际按钮写入并再次翻到 `[5,6]` 后，库为 schema 40、边界 3、`last_page_read=5`（第 6 页）；强制结束并重启，显式打开这个已读章节后翻到续读页仍显示 `[5,6]`。Mac 正式窗口真实点击后数据库新增边界 3，重启 production 首组由默认 `[4,5]` 变为 `[5,6]`。 | Windows/Android/Mac 保存与恢复通过。 |
| A2 | Windows 经真实点击保存两个边界 `{1,3}`，A→B→另一漫画同名 C→A 后仅 A 保留。Android 实际 A 调整后，B 和另一漫画同名 C 均保持默认组合。Mac production A 的首组为 `[5,6]`，B/C 首组均为默认 `[4,5]`；Mac 仅准备一个边界。 | Windows 完整通过；Android/Mac 章隔离通过。 |
| A3 | Windows 点击撤销两个边界后记录与边界表为空，重启为默认；Android 点击撤销后两表为空，重启图像为默认 `[4,5]`。Mac 正式窗口再次真实点击后配对与边界表为空，修订号保持 1；重启 production 首组恢复默认 `[4,5]`。 | Windows/Android/Mac 按钮清除与重启恢复通过。 |
| A4 | Windows 在正式窗口切换双页→单页→双页，记录保留且双页恢复；Android 实际切换 RTL 双页→RTL 单页→RTL 双页，恢复 `[5,6]`。Mac 以单页和双页参数分别打开真实 Reader，记录未删除，返回双页后首组为 `[5,6]`。方向、窗口与 Activity 重建另由平台 focused 测试覆盖。 | Windows/Android 运行通过；Mac 模式重开通过。 |
| A5 | Android `AndroidChapterPairingPersistenceWiringTest` 覆盖宽图延迟回调；Desktop 配对/呈现测试覆盖手动边界与自动匹配。Windows 正式应用中下载目录 fixture 能呈现，但在线 fixture 仅产生 `PAGE_LIST_READY`，没有 production `OPEN_PAGE` 或在线图片请求；单页和双页均复现。回环图片 HTTP 端点可返回 JPEG，但该独立请求不能代替应用业务链路。 | 已有 focused 测试通过；在线→下载及自动匹配的正式运行仍待补验。 |
| A6 | `DatabaseMigrationCompatibilityTest` 用真实旧库执行 39→40 迁移；`ChapterPairingRepositoryIntegrationTest` 覆盖文件库重开、事务故障回滚。Android 隔离 AVD 中将含 2 部漫画、3 章及原进度的 v39 数据库交给已签名发布 APK 启动；运行后 schema 为 40，原章节与进度不变，新配对表为空，`PRAGMA integrity_check=ok`。 | 共享文件库及 Android 发布版旧库迁移通过；事务故障回滚由集成测试覆盖。 |
| A7 | Desktop Model 与 Android 平台 wiring 的 focused 测试覆盖延迟读取、立即退出、跨章晚结果和同章串行修改。 | focused 测试通过。 |
| A8 | 共享记录契约、文件库集成及双端 Model/Viewer 测试覆盖页数与格式失效、非法边界、I/O 错误后的重试及本次默认。 | focused 测试通过。 |
| A9 | 文件库集成测试覆盖章节删除级联；Android `DualPageProgressProductionWiringTest` 与 Desktop Model 测试覆盖布局调整不冒充阅读进度。Windows 真实按钮调整期间既有 `last_page_read=5` 保留；Android 在调整后立即退出的库中 `last_page_read=4`，没有把新出现的第 6 页直接记成已读。Desktop `DesktopChapterPairingComposePersistenceTest` 新增真实下载目录删除后关闭重开文件数据库的集成用例，配对边界与进度保持。 | 进度、下载删除保留及章节删除级联通过自动化验证。 |
| A10 | 两端真实调整按钮、共享仓库与 DI 的集成测试通过；Windows/Android/Mac 正式窗口点击实际按钮并检查库与重启恢复。Desktop 临时文件保持会话限定提示。 | Windows/Android/Mac 按钮运行通过。 |

## 构建与测试结果

- CP-01 提交 `b1a107c6209a1b98c90628aa693b94300150ee72`：相关 `spotlessCheck`、domain 1/1、真实 SQLite 23/23、Desktop 97/97 通过。CP-02 提交 `81be8f82a577f821aa3d4d5f6b54b9e201a51bb3`：Android 相关 57/57、SQLite 6/6、Desktop 30/30，过渡页修复后 Android 16/16 及相邻 viewer 6/6，格式检查通过。详细 TDD 与审查记录见 Roadmap。
- CP-03 一次完整 Android/共享验证：`python scripts/gradle-coordinator.py run --key cp03-android-full -- .\gradlew.bat --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck`。`domain:jvmTest`、`:test-desktop:test` 和格式检查通过；`:app:testReleaseUnitTest` 297 项中 16 失败、4 跳过，其中旧同步迁移测试的 12 项在 Windows release JVM 启动时因 sqlite3x JNI 无法加载失败，另外 4 项为 `DualPagerPageHolderLayoutTest` 的超时/内存与时序失败。该类单独 focused 复跑通过；旧迁移测试在 debug JVM focused 4/4 通过。`:data:jvmTest` 752 项中 1 项因既有测试把 schema 版本写死为 39 失败，测试改为校验不低于历史版本后 focused 10/10 通过。没有第二次全量执行。
- CP-03 一次完整 Desktop 验证：`scripts/build-desktop.sh full-tests`，3208 项中 1 项失败、3 跳过。失败的 `DesktopProductCapabilityContractTest` 是 parity manifest 的 9 处证据行号过期；修正后该契约 focused 通过，另有 Test Mode JSON/导航 focused 红→绿及 `spotlessCheck` 通过。没有第二次全量执行。
- 获得继续验收授权后，第二次完整 Desktop `scripts/build-desktop.sh full-tests` 成功，`BUILD SUCCESSFUL in 4m 59s`。第二次 Android/共享命令 `--no-parallel --max-workers=2 --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck` 中，domain、data 与 Desktop 测试通过；Android release JVM 的旧迁移用例因 sqlite3x 仅能在 Android 运行而跳过，debug JVM focused 4/4 通过，发布 APK 的真实 v39→40 迁移已另行通过。双页布局类首次运行有 2 项超时、1 项 512 MiB 堆溢出，三项重试均通过，但 Gradle 全量仍判失败；格式检查还报 1 处 import 顺序，已修正。
- 对 release JVM 测试类设置 1 GiB 堆与每类独立进程后，双页布局 focused 30/30 与格式检查通过。Desktop 下载删除文件库用例及 `spotlessCheck` 通过。尚需一次 Android 完整复验，不能以 focused 结果代替。
- 发布构建：`scripts/build-desktop.sh build-only` 在 Windows 和 macOS 均成功；Android `:app:assembleRelease` 成功，签名与包身份通过。Android SDK 的 `android-36/android.jar`、`36.0.0/aapt2.exe`、`platform-tools/adb.exe` 均存在。正式产物链接见上表。

## 剩余边界

CP-03 不能勾选：Android 第二次完整测试仍失败，修正资源隔离后的第三次全量超出已声明预算，须另获批准；A5 在线→下载的正式运行因在线 Test Mode 夹具没有进入图片读取链而缺证，原因尚未证实。Mac 辅助功能权限已有效，按钮写入/清除已验证；后续锁屏期间没有窗口的启动不计为 GUI 验收。原有 AVD 的版本 32 数据还缺少历史迁移本应加入的 `sync_runtime_runs.uploaded` 列，发布 APK 在该不一致旧库上启动失败；保留原数据后改用另一份结构完整的 v39 隔离库完成发布版迁移，不能据此断言任意正常版本 32 数据均迁移失败。

本功能仅保存在当前设备的本机数据库，不进入同步或备份。没有数据库章节身份的 Desktop 临时文件只在本次阅读有效。相同页数的内容重排不能自动发现，用户须再次使用现有调整按钮。Android 进程在事务提交前被强杀时，只保证上次已成功保存的状态。
