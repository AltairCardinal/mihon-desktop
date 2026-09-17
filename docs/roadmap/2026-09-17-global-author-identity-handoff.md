# 全局作者身份实施交接

日期：2026-09-17。用户要求：完成GA-02后暂停roadmap实施并交接；不得自动启动GA-03。
交接时状态：PAUSED。GA-01与GA-02均已通过独立审查/复审及批次验证；GA-02随本交接同批提交后按用户要求暂停。

恢复记录（2026-09-17）：用户已明确要求从本交接继续执行，原暂停已解除。GA-03现已完成全局设置、真实后台调度、取消关注边界及双端交互，通过独立限定复审与97项相关用例；随本批提交完成。接下来为GA-04。当前进度与验收仍只以实施roadmap为准，以下为原暂停时的交接基线。

## 进度与提交

- GA-01：已完成，提交 `e6a75afd3f`（精确名称唯一内核、迁移及所有身份写入约束）。
- GA-02：添加别名、已有别名设主名及双端最终交互；本交接随该批实现、测试和roadmap checkoff一起提交。最终提交可由 `git log -1 --format="%h %s" -- docs/roadmap/2026-09-17-global-author-identity-handoff.md` 定位；不为记录自身hash另造状态提交。
- GA-03至GA-06未完成、未启动实施。GA-01/02完成不等于整项功能已正式发布。
- 唯一进度入口：[实施roadmap](2026-09-17-global-author-identity-roadmap.md)。本文件不新增active-task或另一套状态权威。

## 必须保留的决定

- 相同精确姓名文本跨插件自动同一根；不同繁简、大小写、标点和音译初始独立。搜索归一化只用于搜索。
- 添加现有作者为别名保留发起根及主名；一次提交校验目标及全部选择的版本，原子合并，关注启用取并集。没有预览或二次确认。
- 主名只能从当前已有别名选择；确认文案遵循功能设计。没有自由输入改名、同名身份选择或图源例外。
- 原型基线 `77a610534`；[功能设计](../2026-09-17-global-author-functional-design.md)定义产品，[技术方案](../2026-09-17-creator-identity-reconciliation-proposal.md)定义持久化边界，[原型说明](../prototypes/author-identity/README.md)定义交互对照。HTML不能代替production验收。
- 用户明确要求Android fork遥测及更新器保持关闭。不要使用通用AGENTS示例中的两个启用参数。
- 用户已撤销原Sol/GPT-6模型指定，后续按AGENTS默认继承、职责和预算调度，不重新套用旧模型分工。
- 所有批次验收完成前，不向真实用户库安装中间迁移版本。本轮未安装APK、未覆盖任何正式Desktop应用。

## 当前实现入口

- `data/src/commonMain/kotlin/tachiyomi/data/creator/CreatorRepositoryImpl.kt`：精确名称唯一入口、root解析与持续订阅、多选/主名命令、版本及请求重放校验。
- `CreatorIdentityGraphMerger.kt`：复用的事务内关系合并。普通元数据角色更新仍是替换；只有合并显式取并集。
- `CreatorIdentityRecovery.kt`与`author_identity_recovery.sq`：固定版本的分量内17表恢复资料，与合并同事务保存；用于审计/受控恢复，不是通用撤销工具。
- `author_archive.sq`、`author_identity_editing.sq`及迁移`25.sqm`/`26.sqm`：schema26的精确索引和迁移记录；schema27的本地命令凭据及关注版本触发器。凭据含幂等键、请求指纹、结果根和恢复图。
- `domain/.../creator/service/CreatorIdentityEditor.kt`：两端共用窗口状态。候选加载和主名刷新均隔离旧会话响应；CAS重试保护提交；命令错误与订阅读取恢复区分。
- `CreatorWorkArchiveFilter.kt`和`OpenCreatorWorkVersion.kt`：展示过滤不改完整计数，不按标题合并；优先已有本地mangaId，缺条目才调用平台既有保存流程。
- 两端`ui/.../author(s)/AuthorsTab.kt`及`CreatorIdentityHeader.kt`：实际入口、对话框、焦点和作品行；Desktop `AuthorsScreenModels.kt`负责真实依赖组合。
- `data/src/creatorEntryContract/.../CreatorIdentityEditorContract.kt`：两端测试复用的真实SQL契约，不是production依赖。

双端已归组、未归组和排除作品使用同一平台内作品行，封面与标题顶对齐、图源按钮在标题下，缺插件有标记且本地版本仍可打开。Desktop旧作品对照操作保留。旧按漫画/自由新名拆分无法安全表达名称整体迁移，显示限制且不写入；没有新拆分管理器。

## 验证与审查证据

所有日志位于仓库忽略目录 `.gradle-coordinator/`，只作为本机过程证据；恢复时先检查文件是否存在，不把历史测试假定为当前diff证据。测试输出XML会被下一次focused覆盖。

- GA-01追加独立审查通过；数据focused87项、双端入口8项；原10,000漫画性能case通过。详见roadmap。早期取消的模块全量不算通过，最终全量仍待GA-06。
- GA-02首轮稳定实现有42个独立case适用证据（data13/Desktop19/Android10），跨多个focused，不是某次整轮42项全绿。逐项日志映射在roadmap。
- `ga02-wiring-green1`存在Android测试重试，虽然exit0不能记整轮干净；Follow的夹具依赖/虚拟时钟问题随后在`ga02-follow-layout-red`两端各1项独立通过。
- 首轮独立审查拒绝两项：未归组/排除作品旧布局；主名刷新迟到响应可干扰新确认窗口。两项修复后的唯一限定复审已APPROVED；审查者独立核对实际代码、XML、截图与格式证据，未重复Gradle。
- `ga02-review-name-red`：三个迟到刷新用例按断言失败；`ga02-review-name-green`：四项通过，覆盖迟到成功、失败、不覆盖新快照/幂等键以及当前会话正常刷新。
- `ga02-review-rows-red2`：真实作者页已挂载，旧pending行缺封面节点而失败。`ga02-review-rows-green`：Desktop两项（原已归组及新增未归组/排除、两主题）和Android作者接线八项全部通过，XML零失败/错误/跳过。
- `ga02-review-row-actions`：未归组/排除真实页面再次通过；更多按钮进入准确`WorkCompareScreen`，返回后图源按钮进入准确本地`MangaDetailScreen.mangaId`。
- `ga02-review-check`：app/data/domain spotlessCheck通过；此前仅Apply的日志不得充当Check。
- 离屏图片位于`app-desktop/build/ga02/author-{confirmed,pending,rejected}-320-{light,dark}.png`。使用本地MockWebServer提供PNG，实际像素、换行、封面/标题对齐、按钮边界、搜索/来源筛选和完整计数都由测试验证；主代理查看了截图。图片是过程产物，不是发布包。

Android现有证据是实际ScreenModel、真实SQL、DI和导航事件；没有宣称Android Compose真机点击已验收。没有新的正式Windows/macOS构建或APK，不能以JVM绿灯关闭GA-06。

## 暂停后的首个任务：GA-03

只有用户明确恢复实施后才继续；从roadmap第一个未勾选批次GA-03进入，不重做已完成内核。

1. 共享Preference/DI保存每天/每周/每月；缺失默认每天。旧每作者周期保留恢复用途，但停止参与调度。两端列表齿轮为唯一设置入口，详情不保留周期设置。
2. 按功能设计第12节日历语义：成功Instant按当前时区加1/7天或1月，处理月末、DST与时区变化。已有`FetchInterval.kt`在domain commonMain使用java.time，可沿用项目技术栈。
3. 真实调度入口是`CreatorDiscoveryService.discoverDue`→`archive.getDueWatchSources`，以及成功检查点当前`now + policy.periodMillis`处；只增加独立计算器不算接线完成。
4. Android `CreatorDiscoveryJob`和Desktop `CreatorDiscoveryScheduler`继续复用锁、后台约束、重启恢复及outbox。设置保存走到期重算，不借手动force绕过退避；成功/失败来源检查点分离。
5. 取消关注不得新增周期任务或通知。保存设置不改名称/关注，不取消已运行事务，重复触发最多一次。

GA-04扩既有`BackupAuthorArchive.kt`与`AuthorArchiveBackupContributor`，覆盖空库/已有库完整名称、主名、旧键及设置恢复范围；明确本地命令凭据备份边界。GA-05适配现有FOLLOWING因果投影，不发明在线别名/主名/周期协议。GA-06才做模块/全量、Test Mode、三平台正式构建及用户设备验收。

## 环境与操作边界

- Windows根目录 `D:/Shell/Github/mihon`；UTF-8、TDD、Git与Gradle协调规则见AGENTS。独占重型Gradle，超时先查协调器，不重复启动或全局kill Java。
- 用户既有未跟踪`testfile/`不属于本任务，保持不动。恢复先检查git status及已有进程；本轮只提交本任务文件，不推送远端。
- Android SDK `D:/Android/Sdk`。此前只读确认真机PCE-W30/API31，目标包`app.mihon.desktop.fork`，安装版本0.19.4-aex.7/code25；现场状态可能变化，最终必须重新确认并先备份用户库，使用`adb -d`区分模拟器。
- 发布用`scripts/android-fork-release.init.gradle`且不启用telemetry/updater；最终统一增加可升级版本，并同步`sign-android-fork-release.ps1`校验。沿用现有签名身份，凭据不进仓库/日志。
- Windows/macOS发布必须用`scripts/build-desktop.sh`。完成报告只能引用实际存在的`Final unpacked EXE:`发布目录，不引用tmp或Gradle build。
- `mbp-lan`此前可连接，JDK21.0.10可用。远端`/Users/altair/Github/mihon`有用户未提交改动，不能覆盖；GA-06用隔离checkout与`MIHON_MACOS_DIST_ROOT`/`MIHON_MACOS_DEPLOY_DIR`。
- 原实施者在GA-02复审修复期间连续两次模型容量错误。主代理收回写入权限后接管两项限定修复，保留其已写入的主名修复，仍由原独立验收者复审。后续复用前先确认代理可用，不重跑既有工作。
