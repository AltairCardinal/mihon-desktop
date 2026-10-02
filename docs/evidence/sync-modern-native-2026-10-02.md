# 同步交互整理：原生实施与验收

基线 `bdb5453c47`，用户批准将完整 HTML 交互改进落到 Android 与 Desktop。复用共享 `presentation-sync`、既有 controller、调度器、确认快照及诊断服务；不更改同步协议、数据计数或数据库。

## 固定契约与实施边界

- SOURCE：`SyncPanelContent`/`SyncProgressPresentation`、`SyncPanelController`、两端 scheduler 是状态及动作的实际来源。下一次定期同步来自 `max(scheduleAnchor,lastAttempt,lastSuccess)+period`；设置频率写入 anchor，重开面板不重置。Android 使用 WorkManager 的 deadline override，网络约束和系统调度可能延后。
- PROJECT_POLICY：沿用原型 README 的 M1–M5：紧凑结果卡及折叠详情；运行仅计数、确定进度、耗时/估时和暂停继续；状态→下次自动同步→待确认→记录；设置分组及诊断技术信息折叠；主题、窄屏和大字体可达。
- HTML_ADAPTER：浏览器的圆角、字级、截图不作为原生尺寸证据。原生使用 Material3 主题及布局，保留真实恢复/失败日志和危险操作确认；未知总数不伪造分母。

## 流程预算与当前状态

一个实施代理负责共享 UI、资源和真实 Compose 行为测试；主代理核对调度、独立审查一次并负责集成和交付。focused 红绿串行经 Gradle coordinator；全部实现后执行一次适用最终完整验证，构建脚本完成正式运行验收，不自动安装 Android 设备。预计 45–75 分钟，主要成本为编译、测试与发布构建。

Android JDK21、SDK36 与原正式签名预检通过。macOS SSH 可达，但仅剩 3.7 GiB，旧隔离树含未提交改动；不覆盖或清理，已向用户询问 macOS 验证安排。Windows/Android 工作继续。

## 实现与独立审查

共享面板使用主题化紧凑结果卡、填充主按钮和折叠队列详情；运行态继续使用既有同一范围计数、确定进度和排除暂停的时钟。下次安排显示本地日期时刻和相对等待，关闭、到期、网络失败分别反馈；所有非运行终态可看到安排，未知总数仍不伪造。

设置按自动同步、账号与设备、更多分组，五档定期选项自动换行。危险操作保留确认；待确认列表的保留操作采用低强调填充，窄屏大字自动分行。诊断采集突出显示，技术信息默认收起，采集/会话操作不会重置展开状态。待确认模型没有来源名称字段，界面只呈现真实类别及来自其他设备的取消语义，不编造来源。

独立审查核对实际动作分派、确认快照、恢复/日志、真实调度来源与原生截图。一轮修复处理定期标签、失败后的安排、重复倒计时标题、诊断主操作和按钮文字对齐，复验通过。无新的协议、数据库或平台调度逻辑；DateFormat 的 Android/JVM 薄适配只负责本地日期格式。

## 验证

- 正确红：首批4项实际 Compose 行为失败，审阅修复3项失败；大字 TextLayout 另揭示约1px舍入裁切，按实际文案测量修正，未恢复旧的大块占位。
- 最终相关验证 `sync-modern-native-freeze`：109/109（UI 88、投影11、真实 controller 恢复/诊断10），0失败/跳过，57秒；共享模块格式检查通过。
- 主代理查看真实离屏截图：320dp/200%中文完成卡及设置自动/账号分组，文字换行、滚动和操作区域正常；自动化同时覆盖320/390/560、中英、深浅主题、期限到期不重置及原运行/恢复路径。截图在忽略目录 `presentation-sync/build/sync-visual/modern-*`，不代替真实账号同步。
- 唯一最终完整验证 `sync-modern-final-tests`：`testReleaseUnitTest :presentation-sync:jvmTest :app-desktop:jvmTest :test-desktop:test spotlessCheck -PincludeIntegrationTests=true -Pmihon.testBuildType=release --continue`，32分41秒。Desktop 3239项（3236通过、3跳过），共享UI 116/116；Android data372、domain473、core10、theme26、widget3、source-api19均通过；test-desktop52项复用相同输入的有效结果。
- Android主模块首轮XML记录681次执行、6次失败、7跳过；6次失败是两个旧诊断路径各执行3次（项目自动重试2次），并非6个不同问题。测试仍直接点击已经移到下方或收起的入口，现已迁移为真实滚动、展开、打开及返回路径，保留平台适配器和系统返回断言。Desktop对应测试也更新真实路径，已包含于上述完整Desktop验证。首轮失败XML与汇总保存在忽略目录。
- 修正后 `sync-modern-android-focused2`：AndroidSyncPanelTest 13项，10通过、3个既有FileProvider宿主条件跳过，0失败，45秒；Android格式检查通过。首轮格式失败来自新增测试文件CRLF，已只统一换行；内容不变。首次focused命令误带Desktop不存在的spotlessCheck，配置阶段退出、没有执行测试；更正后才取得上述结果。未重复整套测试。
- Android全量的7个跳过：3项Unix FileProvider宿主约束，4项Release原生SQLite需要实体Android（当前用Robolectric）；Desktop的3项为macOS原生分享、需图形窗口的Windows隐私及非Release设置场景。未将这些跳过说成通过，也未操作用户实体设备。

超过8文件/400行仍属于同一共享同步界面批次：视图重排、同一动作的回归、资源及日期平台适配共同交付；旧终态七槽布局的移除占主要差异。没有拆出独立状态系统或改变数据同步能力。

## 正式产物与边界

- Windows：[正式未打包程序](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.74.bdb5453-unpacked/Mihon%20Desktop.exe)，[可分发ZIP](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.74.bdb5453-windows.zip)。已按构建日志 `Final unpacked EXE:` 核对文件存在；版本、production扩展安装验收通过。ZIP SHA-256：`8f6120cc12ccd73e66140ad61888ceda4e52d135c6efd2f21a57424b81719e2f`。
- 发布EXE使用独立空profile执行真实同步面板 open→close→open→setup→close，全部通过并正常退出，记录在 `.gradle-coordinator/sync-modern-windows-runtime.json`。没有使用用户账号、书架或配置，不代表真实GitHub同步已人工验收。
- Android：[正式候选APK](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.24-vc42-bdb5453c47-release/Mihon-Fork-0.19.4-aex.24-vc42-release-universal.apk)。`candidate --offline` 构建2分34秒，独立 `verify --artifact` 通过；原证书连续、v2/v3签名、R8及资源压缩开启。SHA-256：`afa2c4aab963018669a1fbf6f75d9989a2e38d9cebefad022a29efb81570f134`。候选仍需用户安装与真实账号验收，没有自动安装或操作设备。
- Android首次构建请求因旧versionCode39已占用而在构建前拒绝。检查全部已登记工作树，40和41已分配，因此本次公开元数据分配42/0.19.4-aex.24，不复用其他分支候选编号。
- Desktop构建通过 `build-desktop.sh build-only` 先分配版本，但Git Bash→PowerShell的后台启动连续失败；直接后台PowerShell也未进入脚本。最终复用此前成功的前台协调方式，执行官方 `build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion 0.11.19.74.bdb5453`，不直接调用Gradle发布、不重复全量。日志 `sync-modern-windows-build4`；构建1分47秒并完成运行验收。未进一步推断宿主启动失败原因。
- 版本中的 `bdb5453` 是构建基线，本次生产改动包含在已测试的未提交diff中，按项目规则在正式构建验收之后统一提交。macOS只有3.7GiB可用空间且旧工作树有未提交内容，本轮未构建或清理Mac，未声称该平台发布验收通过。

## 手动验收

- [ ] 打开上述Windows程序，或升级安装上述APK → 书架 → 同步：完成卡紧凑，详情默认收起，自动同步安排可见。
- [ ] 设置 → 定期同步 → 切换频率/关闭 → 返回：显示对应预计日期时间/关闭提示；关闭重开面板不会重新计时。Android系统与网络约束可能延后实际执行。
- [ ] 开始同步 → 上传/下载/双向均只呈现同一范围的完成数/总数、进度与耗时/估时；暂停后等待15秒，已用时保持，继续后排除暂停时长。统计尚未就绪时不伪造总数。
- [ ] 待确认操作 → 保留或取消：先显示确认，取消确认不执行；窄屏/大字号操作可换行。
- [ ] 设置 → 更多 → 诊断：采集为主操作；展开后可访问会话和JSON，采集后仍保持展开；返回回到设置。断开/更换空间仍先确认。


## 2026-10-03：合并主界面暂停入口

用户验收发现首次合并期间同时显示“暂停同步”和“暂停首次合并”。本次仅整理共享表现层：运行卡删除首次合并的独立暂停/继续，统一使用整轮暂停/继续；空闲且无运行记录时，首次合并控制也仅在展开详情后显示。详情中的独立控制保留原语义，不修改持久化、数据交换或计时逻辑。

真实 Compose 回归先确认首次合并按钮仍出现而失败，再修改生产组件。针对主卡与真实 controller 的 90 项测试及 presentation-sync 格式检查通过（sync-single-pause-green4）；主卡点击分派 PauseSync/ResumeSync，真实 controller 测试验证持久暂停与计时冻结。用户要求快速完成，本次不重复上一轮全量测试，也不涉及 macOS 或实体设备操作。

Windows 正式脚本构建 0.11.19.75.b08d06d，发布运行版本与真实扩展安装验收通过（sync-single-pause-windows）。发布 EXE 为 `app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.75.b08d06d-unpacked/Mihon Desktop.exe`。Android 正式候选 0.19.4-aex.25/vc43 构建及独立 verify 通过，v2/v3 签名连续，R8/资源压缩开启；APK 位于 `app/artifacts/android/0.19.4-aex.25-vc43-b08d06d76f-release/`。构建基线为 b08d06d，包含本次已验证的未提交功能 diff；Android 真机 UI 仍由用户安装验收。
