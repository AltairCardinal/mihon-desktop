# 同步进度稳定展示验收记录

日期：2026-09-30。状态：SP01/SP02实现及唯一修复复审通过；SP03正式发布与人工门禁未完成。

需求权威：[开发规格](../2026-09-28-sync-progress-display-design.md)；执行入口：[Roadmap](../roadmap/2026-09-28-sync-progress-display-roadmap.md)。

## 基线与范围

- 工作树：`D:/Codex/worktrees/dc4c/mihon`，分支 `codex/sync-progress-display`。
- 启动 HEAD：`41e7e5ff1ef92b382de1b3134e11cf94ae1883f4`；工作树干净。
- 相对规格基线 `02c48df981`，共享面板/controller/进度事实接口无新增行为；Android/Desktop wrapper 新增 review 入口，保留该功能。
- 复用现有 runtime → controller → 共享 Compose → 双端 wrapper；不修改协议、数据库 schema、安全确认算法及 ETA 估算器。
- PROJECT_POLICY：规格 P1–P14/D01–D10 与稳定七槽约束；SOURCE：现有 Compose 卡片/主题及 action/runtime；HTML_ADAPTER：原型仅为信息架构参照。

## 环境预检

- Windows JDK 21.0.11；Git Bash 位于 `C:/Program Files/Git/bin/bash.exe`。
- Android SDK 的 android-36/android.jar、36.0.0/aapt2.exe、adb.exe 均存在。
- `python scripts/build-android.py check --signing` 成功，原证书签名预检通过，不安装或操作实体设备。
- 原始checkout与本工作树公开发布配置同为versionCode 35 / `0.19.4-aex.17`，原始正式候选目录最高35；正式候选前复核占用，由统一入口递增，不复用已交付版本号。
- macOS：首次 `ssh -o BatchMode=yes -o ConnectTimeout=8 mbp` 超时，备用 `mbp-lan` 成功；macOS 14.8.4、JDK 21.0.10、Gradle cache 可用，剩余磁盘约 19GiB。日常树有用户改动，发布时使用隔离 checkout/dist/app/config，不覆盖日常实例。

- macOS 隔离源码树：`mbp-lan:/Users/altair/github/mihon-sync-progress-20260930-dc4c`，通过4MB增量bundle同步至启动HEAD；未改日常checkout/应用。
- 核对现有 parity manifest，没有本次多设备同步展示的对应独立capability；不新增重复状态表或改动其他能力状态。

## 验证与覆盖

- Compose 首轮红测：`.gradle-coordinator/sync-display-red.log`，1 项失败；运行卡阶段计数默认可见，违反默认收起详情的契约。
- 投影首轮红测：`.gradle-coordinator/sync-display-projection-red.log`，3 项失败；动作800ms/ETA2000ms、scope/hold撤销与安全nullable计数尚未实现。编译正常，失败是行为断言。
- 首轮 focused green：`.gradle-coordinator/sync-display-green.log`，投影3项与真实Compose折叠1项通过。
- 红绿循环随后覆盖隐藏面板定时取消、48dp主操作槽、200%字体真实TextLayout、保持暂停运行ID和关闭重开恢复。早期失败与中间通过只作为过程证据，不替代最终冻结。
- 独立审查第一轮：`NOT_ACCEPTED`。发现 FAILED/PARTIAL 原因被去重吞掉、修复入口缺少不兼容配置限制、详情隐藏已知零计数，以及真实批处理按钮集成证据不足。统一交原实施者修复；同轮补修200%字体说明槽裁切及去重后空消息行。
- 原冻结的共享存储、Android/Desktop wiring 通过；Android面板8项中3项因Windows不支持Unix文件权限夹具跳过，必须在macOS补验。后续发现说明槽裁切已撤销该轮UI冻结，未将它作为完成证据。
- 修复后冻结：`.gradle-coordinator/sync-display-reviewed-freeze.log`，2026-09-30 14:13:33结束，退出0，耗时1分7秒。共享UI85项全绿（71 Compose、2 onboarding、1真实controller、7投影、4 review）；JVM storage36、Android storage35、Android runtime14、Desktop panel1+wiring2均无失败/跳过；Android panel8项无失败、3项Unix夹具跳过。presentation-sync/data/i18n/app格式检查通过。
- 主代理独立读取最终JUnit XML核对上述数量/失败/跳过，唯一修复复审通过（SP01/SP02代码批次）。批处理补验通过真实协议校验的400事件夹具（每批200、同actor连续seq、匹配batchId），真实独立偏好节点在finally清理；修复夹具不改变生产协议。

## 实施与审查边界

- 2个子代理：同一实施者串行负责SP01/SP02，一个只读独立审查者；主代理负责文档、环境、整合与最终平台验收，未重复实施委派代码。
- SP01/SP02修改同一共享面板、投影和真实controller接线，保持为内聚用户能力；超过8文件/400行主要为边界、几何与双端契约测试，不按机械行数拆开不可独立验收的上下文。
- 数据层仅局部修复`ResumeImport`：解除导入暂停不应启动第二轮覆盖已有用户暂停运行；使用持久运行状态保留原ID。未改协议、数据库格式或调度算法。
- 核对真实构建脚本：当前Desktop入口未内置协调器，因此正式测试/构建使用唯一外层协调器；Android统一入口已内置协调器，不嵌套。实现后完整Android/Desktop测试各安排一次，修复循环仅focused。

## 契约入口（最终结果待冻结）

| 规格 | production行为验证入口 | 补充门禁 |
|---|---|---|
| P1/P2、D01 | `SyncProgressPresentationTest`动作800ms/频繁变化锁定，`SyncPanelContentTest`无新事件deadline提升 | 单调时钟与数字变化不重启稳定窗 |
| P3/P6/P7、D02/D03 | 投影scope/attempt/方向立即撤比例，ETA1999/2000ms及10/60秒边界 | 真实Compose单轨道、固定时间标签 |
| P4、D09 | `D09 fixed summary geometry and status live region survive progress changes`及详情focus测试 | 新日志与数字不改变摘要配置内几何 |
| P5/P14、D04/D05 | 投影安全nullable计数、传输完成非终态，Compose终态与零计数测试 | 持久结果优先、失败原因全文 |
| P8/P9、D06/D07 | `SyncProgressControllerIntegrationTest`真实运行/controller点击；共享文件数据库storage契约 | 两端runtime wiring；bulk新增夹具必须通过真实批次校验 |
| P10、D08 | `SyncPanelContentTest`决定项/日志/公开仓库/终态原因及修复guard | Android真实FileProvider和Desktop打开adapter |
| P11 | `SyncPanelOnboardingIntegrationTest`及MAIN/SETUP共享摘要几何 | 两处使用同一投影与展示会话 |
| P12、D09 | `P12 D09 native summary matrix stays readable and scrolls at 200 percent` | 离屏Compose中英/双主题/320–560dp，TextLayout无裁切 |
| P13 | detail focus、返回/关闭和MotionDurationScale=0原生测试 | 读屏实际播报与设备人工验收待验 |
| D10 | `D10 hidden panel cancels visual wakes and reopen projects current terminal` | 隐藏取消视觉timer，生产runtime不停止 |

主代理已核对修复后的320dp中文默认、中文200%深色及英文200%浅色离屏图：说明全文与详情按钮可达，英文五行说明不再裁切；200%时单列滚动到摘要底部，顶部标题允许省略并保留设置/关闭按钮。图片保存于`presentation-sync/build/sync-visual/summary-*.png`，为当前Material主题的原生Compose候选图，不是上游像素基准，也不代替真实发布产物/读屏验收。

## 产物与待验

尚未生成本轮正式产物；不得引用历史 APK/EXE 作为本轮证据。真实账号同步、读屏及设备人工验收保持待验。

复审结论：`ACCEPTED`，上一轮四项阻塞全部关闭，F说明槽修复及真实bulk偏好隔离/清理已核对，无新增阻塞。SP01/SP02以同一内聚功能提交交付；SP03构建、macOS补验及人工读屏/Tab遍历尚不作为通过。
