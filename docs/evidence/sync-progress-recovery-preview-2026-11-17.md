# Windows 同步进度优化与已验收恢复修复整合

日期：2026-11-17。用户要求把此前因同步故障搁置的进度优化纳入新的 Windows 验收包；本轮不扩展为交接文档中的全面错误整改，不操作真实用户数据库，不代表用户已验收本候选。

## 源码边界

基线为本地 main `fd95b7ea2a28bb0d50b269408b0c0dc58ca1acfa`，包含已验收的 `e35afaa8cd` 和 `640ddd5a8b`。在现有隔离 worktree 增量整合原工作区的同步进度差异，原工作区混合改动保持不动；包含原先未跟踪的 `SyncBatchProgress.kt`。19 个产品/测试文件共同覆盖观察 → exchange → 面板的同一链路，不引入依赖或数据库 DDL/migration。

6 个文件的 9 处旧基线冲突逐项处理。保留当前 attempt 计时/新增确认、暂停冻结、`local_projection`、`upload-reconciliation` 及 `allowPublish`。`SyncRuntime.kt`、`SyncPanelController.kt`、GitHub transport 实现无差异，不覆盖此前的恢复修复。旧交接“真实故障未查明”不再适用，本会话已完成的恢复证据见同目录 `sync-auto-recovery-2026-11-15.md`。

运行卡显示总条目已处理/冻结计划总量、细化全轮进度和耗时/ETA，不单列批次信息。上传字节折算与下载写入观察不等于系统确认；终态及独立确认数继续由持久账本拥有。中英文使用“已处理/processed”，避免尚未确认即声称完成。ETA 无可靠确认速度时显示未知。原因按钮改为真实导航，存储问题不直接猜测需要重新输入密码。

## 验证及审查

- `sync-progress-integration-red`：只移植两条真实按钮行为测试，当前生产实现两项按预期失败，非编译失败。
- `sync-progress-integration-green`：限定修改文件的真实 Spotless Apply/Check 通过；data 15/15 通过，包括 tracker、加密 inbox 回调、真实上传下载接线、旧本地恢复和只读上传对账。presentation 25/26 通过，唯一失败为原 D06/D07 集成测试仍等待已删除的旧密码按钮。
- 修正该旧入口断言，保留所有暂停、持久化重建及 attempt 时钟断言，新增真实点击进入恢复页。第一次补验因测试末表达式返回非 Unit 而未发现测试，不计为通过；补显式 Unit 后 `sync-progress-controller-unit` 真实 1/1 通过，XML tests=1。没有延长 deadline 或放宽行为条件。
- 组合证据为 40+1 个唯一 focused 用例；最后补验期间生产代码未修改，不声称最终代码重新执行全部 41 项。末轮对包含该测试的 6 个 presentation Kotlin 文件执行真实 Apply/Check。
- 实施代理负责代码和定向验证；主代理独立检查回调的遥测隔离、事务回滚、串行 tracker、作用域过滤、持久确认、attempt 字段及只读权限传递，收紧处理量文案。没有改变确认、发布或恢复权限，也不以源码扫描代替上述行为测试。
- 没有执行完整模块/全仓测试、Android 用户验收或 Windows 原生输入/视觉验收。公共功能说明等待用户验收后更新。

## Windows 候选

- 入口 `scripts/build-desktop.sh preview`，协调器 `sync-progress-recovery-preview-20261117` 成功，生产扩展 APK 安装及源加载检查通过；未分配正式版本。
- 实际 EXE：`app-desktop/artifacts/preview/windows/20261117T115253Z-3bf75340/Mihon-Desktop-0.11.24.10.fd95b7e-unpacked/Mihon Desktop.exe`。
- manifest 如实记录构建时 HEAD 加 dirty 输入，包含新 tracker。最终源码生产输入摘要与候选一致：`4a858d1574a87c57890777fc8dda3aec6accc48d7cc845853c6706b86b025063`；产物树摘要：`9f9bb793cb2dd645cb1f0f2ae3eca31c61cca6616bd42e51b5dd8d910450319b`。不改写包标签冒充最终提交或主干发行。
- 交给用户的验收路径：退出旧版，打开本候选 → 书架同步面板 → 有实际工作时观察总条目已处理数和全轮进度在批次内推进，耗时/ETA 保持可解释；暂停、关闭重开后保持暂停，继续后仍属于原任务。没有工作时不伪造变化或为体验注入用户数据。
- 本次没有使用真实用户数据库，也没有清库、还原或启动用户 profile；候选原生视觉/输入为 NOT_RUN。此前真实数据库恢复证据与本轮保留回归分别记录，不用生产扩展运行时检查冒充真实空间同步验收。
