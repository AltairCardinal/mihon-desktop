# 章节双页调整持久化：实施与验收证据

日期：2026-09-27。对应 [需求](../2026-09-27-chapter-pairing-persistence-requirements.md)与 [Roadmap](2026-09-27-chapter-pairing-persistence-roadmap.md)。CP-01、CP-02 已提交；CP-03 的正式产物已构建，仍有下述未通过的全量测试和 macOS 按钮运行验收，因此保持未勾选。

## 产物与环境

| 平台 | 正式产物 | 已核验 |
| --- | --- | --- |
| Windows | [未打包 EXE](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.62.81be8f8-unpacked/Mihon%20Desktop.exe)、[ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.62.81be8f8-windows.zip) | `scripts/build-desktop.sh build-only` 的 `Final unpacked EXE:` 指向上述实际存在文件；ZIP SHA-256 `c1ec73755b4f0448c5fea5fde8fa0276fc2ae477456f90e803bf9b108ff8c9dd`。 |
| macOS | [应用包 ZIP](D:/Shell/Github/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.62.81be8f8-macos.zip) | 独立工作树运行 `scripts/build-desktop.sh build-only`，生成 `Mihon Desktop 0.11.19.62.81be8f8`；ZIP SHA-256 `d62480b73fa29a1f000762f366d05ae45f81cb5d63b550513193184fce4fbe22`，与 Mac 生成包一致。原有 Mac 工作树未改动。 |
| Android | [通用签名 APK](D:/Shell/Github/mihon/app/artifacts/android/Mihon-0.19.4-aex.13-pairing-81be8f8-universal.apk) | `:app:assembleRelease` 成功；包名 `app.mihon.desktop.fork`、versionCode 31、versionName `0.19.4-aex.13`，v2/v3 签名及预期证书校验通过，SHA-256 `5030957cc15918b0de29cb4ce451c77acaf6012ff52df65219767e60abed8ea4`。同一次构建的 x86_64 签名 APK 安装到 API 36 隔离 AVD 完成运行验收。 |

Windows、macOS 均使用正式应用的隔离 Test Mode profile、真实 SQLDelight 数据库与 production Reader。Windows 在实际窗口中点击调整按钮；macOS 远程会话的系统辅助功能拒绝事件输入，`CGPreflightPostEventAccess()` 返回 `false`，因此仅从隔离库准备有效记录，验证 production 读取和呈现，不把数据库准备当作 Mac UI 写入。Android 使用新建的 API 36 x86_64 AVD、本地测试漫画与正式签名 APK，在实际界面操作；测试期间没有清除原有 AVD 的数据。

## 验收矩阵

页码叙述为用户可见的 1 基页码。Mac Test Mode 的 `OPEN_PAGE` 事件是 0 基索引，报告已换算。

| ID | 自动化与正式运行证据 | 状态 |
| --- | --- | --- |
| A1 | Desktop Compose 实际按钮→文件库重开→新 Screen，Android 实际按钮→仓库→新 ViewModel/viewer 的 focused 测试通过。Windows 实际按钮写入后重启，首个当前组合为 `[5,6]`。Android 实际按钮写入并再次翻到 `[5,6]` 后，库为 schema 40、边界 3、`last_page_read=5`（第 6 页）；强制结束并重启，显式打开这个已读章节后翻到续读页仍显示 `[5,6]`。Mac 有效记录下首次 production 当前组合为 `[5,6]`，但记录由隔离库准备。 | Windows/Android 保存与恢复通过；Mac 恢复通过，按钮写入待验。 |
| A2 | Windows 经真实点击保存两个边界 `{1,3}`，A→B→另一漫画同名 C→A 后仅 A 保留。Android 实际 A 调整后，B 和另一漫画同名 C 均保持默认组合。Mac production A 的首组为 `[5,6]`，B/C 首组均为默认 `[4,5]`；Mac 仅准备一个边界。 | Windows 完整通过；Android/Mac 章隔离通过。 |
| A3 | Windows 点击撤销两个边界后记录与边界表为空，重启为默认；Android 点击撤销后两表为空，重启图像为默认 `[4,5]`。Mac 隔离库删除配对、边界和修订号后，重启 production 首组为默认 `[4,5]`。 | Windows/Android 运行通过；Mac 读取通过，按钮清除待验。 |
| A4 | Windows 在正式窗口切换双页→单页→双页，记录保留且双页恢复；Android 实际切换 RTL 双页→RTL 单页→RTL 双页，恢复 `[5,6]`。Mac 以单页和双页参数分别打开真实 Reader，记录未删除，返回双页后首组为 `[5,6]`。方向、窗口与 Activity 重建另由平台 focused 测试覆盖。 | Windows/Android 运行通过；Mac 模式重开通过。 |
| A5 | Android `AndroidChapterPairingPersistenceWiringTest` 覆盖宽图延迟回调；Desktop 配对/呈现测试覆盖手动边界与自动匹配的组合。本轮正式运行仅使用本地漫画和 Test Mode 图片 fixture。 | 已有 focused 测试通过；在线→下载切换与自动匹配在正式运行中未逐项复核。 |
| A6 | `DatabaseMigrationCompatibilityTest` 用真实旧库执行 39→40 迁移；`ChapterPairingRepositoryIntegrationTest` 覆盖文件库重开、事务故障回滚。Android 全新正式运行库为 schema 40。 | 共享文件库测试通过；Android 旧库的发布版迁移未独立跑通。 |
| A7 | Desktop Model 与 Android 平台 wiring 的 focused 测试覆盖延迟读取、立即退出、跨章晚结果和同章串行修改。 | focused 测试通过。 |
| A8 | 共享记录契约、文件库集成及双端 Model/Viewer 测试覆盖页数与格式失效、非法边界、I/O 错误后的重试及本次默认。 | focused 测试通过。 |
| A9 | 文件库集成测试覆盖章节删除级联；Android `DualPageProgressProductionWiringTest` 与 Desktop Model 测试覆盖布局调整不冒充阅读进度。Windows 真实按钮调整期间既有 `last_page_read=5` 保留；Android 在调整后立即退出的库中 `last_page_read=4`，没有把新出现的第 6 页直接记成已读。 | 进度与章节删除有验证；下载删除未在正式运行中单独复核。 |
| A10 | 两端真实调整按钮、共享仓库与 DI 的集成测试通过；Windows/Android 正式窗口点击实际按钮并检查库与重启恢复。Desktop 临时文件保持会话限定提示。 | Windows/Android 通过；Mac 按钮运行待验。 |

## 构建与测试结果

- CP-01 提交 `b1a107c6209a1b98c90628aa693b94300150ee72`：相关 `spotlessCheck`、domain 1/1、真实 SQLite 23/23、Desktop 97/97 通过。CP-02 提交 `81be8f82a577f821aa3d4d5f6b54b9e201a51bb3`：Android 相关 57/57、SQLite 6/6、Desktop 30/30，过渡页修复后 Android 16/16 及相邻 viewer 6/6，格式检查通过。详细 TDD 与审查记录见 Roadmap。
- CP-03 一次完整 Android/共享验证：`python scripts/gradle-coordinator.py run --key cp03-android-full -- .\gradlew.bat --continue testReleaseUnitTest :domain:jvmTest :data:jvmTest :test-desktop:test spotlessCheck`。`domain:jvmTest`、`:test-desktop:test` 和格式检查通过；`:app:testReleaseUnitTest` 297 项中 16 失败、4 跳过，其中旧同步迁移测试的 12 项在 Windows release JVM 启动时因 sqlite3x JNI 无法加载失败，另外 4 项为 `DualPagerPageHolderLayoutTest` 的超时/内存与时序失败。该类单独 focused 复跑通过；旧迁移测试在 debug JVM focused 4/4 通过。`:data:jvmTest` 752 项中 1 项因既有测试把 schema 版本写死为 39 失败，测试改为校验不低于历史版本后 focused 10/10 通过。没有第二次全量执行。
- CP-03 一次完整 Desktop 验证：`scripts/build-desktop.sh full-tests`，3208 项中 1 项失败、3 跳过。失败的 `DesktopProductCapabilityContractTest` 是 parity manifest 的 9 处证据行号过期；修正后该契约 focused 通过，另有 Test Mode JSON/导航 focused 红→绿及 `spotlessCheck` 通过。没有第二次全量执行。
- 发布构建：`scripts/build-desktop.sh build-only` 在 Windows 和 macOS 均成功；Android `:app:assembleRelease` 成功，签名与包身份通过。Android SDK 的 `android-36/android.jar`、`36.0.0/aapt2.exe`、`platform-tools/adb.exe` 均存在。正式产物链接见上表。

## 剩余边界

CP-03 不能勾选：完整 Android/Desktop 测试命令各有失败，虽已修正确定的测试数据/证据问题并通过相关 focused 测试，流程预算限定本轮只运行一次全量；macOS 的真实按钮写入与撤销因远程会话没有辅助功能事件权限未验证。原有 AVD 的版本 32 数据还缺少历史迁移本应加入的 `sync_runtime_runs.uploaded` 列，发布 APK 在该不一致旧库上启动失败；保留原数据后改用全新隔离 AVD 完成上述产品验收，不能据此断言任意正常版本 32 数据均迁移失败。

本功能仅保存在当前设备的本机数据库，不进入同步或备份。没有数据库章节身份的 Desktop 临时文件只在本次阅读有效。相同页数的内容重排不能自动发现，用户须再次使用现有调整按钮。Android 进程在事务提交前被强杀时，只保证上次已成功保存的状态。
