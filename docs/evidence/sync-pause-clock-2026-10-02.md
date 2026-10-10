# 暂停同步已用时修复

基线fa6740514d，工作树启动干净。用户报告暂停后已用时继续增加。root直接实施，复用原审查代理进行一轮独立审查；只修复本地计时，不改同步确认、远端协议或进度总数。

## 原因与实现

原展示会话使用起始时间加单调时钟，无暂停扣除。暂停15秒、推进60秒的真实投影红测返回75而非15。仅冻结视图会在恢复/重开后跳回含暂停的时长，所以schema41保存运行的暂停起点与累计暂停毫秒。SQLite状态触发器与状态变更原子执行，重复pause与被拒绝owner更新不改钟，resume/cancel/finish一次关闭暂停区间。UI仅在未暂停时安排秒刷新，终态同样扣除累计暂停。

旧版本已结束的暂停时段没有记录，无法重建；迁移为旧当前暂停任务保留updated_at停止时间。从升级后起完整记录暂停。PAUSING尚在安全保存，计时到实际PAUSED_USER为止；网络与系统等待计时语义不变。

## 验证

- 红：实际projection预期15秒实际75秒；共享存储暂停起点缺失；migration预期schema41实际40。
- 绿：projection暂停/继续/重开/终态，JVM和Android共享storage多次暂停/重复暂停/拒绝owner，历史schema兼容与文件迁移重开均通过。相关JVM测试92项、presentation完整测试108项通过。
- 迁移夹具首轮FK级联失败，随后按生产Desktop的Properties foreign_keys=true构造，新增PRAGMA==1并保留级联断言；不修改生产删除语义。
- 原生Controller→Compose以真实PauseSync，推进60秒并关闭重开，检查已用时仍00:10，通过。macOS原生focused共14项通过。
- Android相关测试首次70/71通过：既有diagnostic snapshot身份切换测试预期INCONSISTENT、实际OK；未修改该测试，单独重跑通过。不能据此宣称其原因已修复，最终完整数据测试另行记录。
- 一轮独立审查PASS，检查数据库迁移、原子暂停区间、共享展示与真实Controller链路；无远端协议变更。
- 最终完整数据测试在Windows执行；唯一一次完整Desktop测试在隔离macOS工作树执行，生产代码相同，两个主机独立并行。两端正式构建另作运行验收。

- 最终数据完整回归 `sync-pause-final-data`：18m39s，JVM 786项（785通过、1跳过），Android 372项全部通过，模块格式检查通过；前述diagnostic测试本次完整运行通过。
- 唯一Desktop完整回归 `sync-pause-desktop-full`：7m22s，3239项（3229通过、9跳过、1失败）。唯一失败为原有macOS真实系统凭据后端测试；同会话`security show-keychain-info`返回`User interaction is not allowed`。不解锁用户钥匙串、不改安全存储、不宣称该Mac能力已验收。Windows对应专项`sync-pause-windows-credential` 4/4通过（42秒），使用真实Windows系统凭据后端。

## 交付与验收

正式Windows/Android产物校验通过，macOS构建与运行验收通过但安装包传回失败。Windows调用仓库`build-windows.ps1 -SkipTests`；因当前同一功能diff已执行完整Desktop及Windows平台focused，避免重复全量。macOS调用`build-desktop.sh build-only`，部署到本任务独立目录，不覆盖用户现有安装。两端版本0.11.19.72.fa67405；该hash是构建基线，修复包含在本次未提交diff中。Android候选版本0.19.4-aex.21/versionCode39。

正式桌面运行验收使用隔离profile、实际发布EXE/app及production同步面板open/close/open/setup链路。该验收证明正式入口/DI/运行链路，不代替真实账号同步；暂停计时由真实Controller、共享展示、数据库及迁移契约测试覆盖。

- [ ] 书架→同步→开始同步→暂停，记录已用时；等待至少15秒，显示不变。
- [ ] 继续同步，计时从暂停值继续增长，不加上暂停等待时间。
- [ ] 暂停后关闭重开同步面板或重启应用，显示保持原暂停时长。
- [ ] 连续暂停/继续两次，完成后的已用时排除两段暂停。

真实账号和用户实体Android由用户验收，构建不包含安装或操作实体设备。

### 构建证据与产物

- Windows `sync-pause-windows-build` 通过，脚本实际发布路径：
  [Mihon Desktop.exe](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.72.fa67405-unpacked/Mihon%20Desktop.exe)。正式运行验收`sync-pause-windows-runtime.json`为PASSED。
- Android `android-candidate` 3m26s通过，独立`build-android.py verify`通过，R8与资源压缩开启，原正式证书连续：
  [正式APK](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.21-vc39-fa6740514d-release/Mihon-Fork-0.19.4-aex.21-vc39-release-universal.apk)。SHA-256：`c5390ef5bd219828f2f8e6e128567f5721e51ba806f3b7a72488588f2c58841c`。
- macOS隔离worktree `/Users/altair/github/mihon-sync-pause-20261002` 的官方build-only通过（39秒），产物`app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.72.fa67405-unpacked.app`，原生同步面板正式运行验收PASSED；同目录macos-x64.zip远端SHA-256为`deeb3384b8e0032ee6d4906380121e9ec70acf1ab2771b18fad5f5962a2f8a12`。SCP首次及一次重试均Connection reset，本地不完整包已删除，不提供其本地交付链接。不把SSH钥匙串限制说成已通过。
- 过程日志保留在忽略目录`.gradle-coordinator/`，报告仅保留必要结论。超过8个文件的原因是同一个持久化暂停时钟需要共享数据模型、schema迁移、两端契约、真实UI链路、版本与文档共同交付；主要风险是旧库兼容，已由文件迁移及18项兼容测试覆盖，不引入独立新能力。

### 验证命令

重型命令经`python scripts/gradle-coordinator.py run --key <唯一名称> -- <命令>`串行执行：

- `gradlew.bat --offline :data:jvmTest :data:testDebugUnitTest :data:spotlessCheck :presentation-sync:spotlessCheck --continue`
- `gradlew.bat --offline :presentation-sync:jvmTest`（完整108项结果来自`sync-pause-related`；后续focused不会视为重复全量）
- macOS `bash scripts/build-desktop.sh full-tests`（本轮唯一Desktop全量，限制见上）
- Windows `gradlew.bat --offline :app-desktop:jvmTest --tests mihon.desktop.sync.DesktopSyncSecureStoreTest`
- `python scripts/build-android.py verify --artifact <上述APK绝对路径>`

最终报告文档在候选构建后补充结果；没有再次变更production输入。
