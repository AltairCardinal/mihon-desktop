# 同步异常处理闭环：实施与验证记录

状态：2026-10-08 完成本轮设计、实现、独立审查、相关定点验证和正式产物构建。Android APK 已签名并复核，Windows 正式运行验证通过；真实账号和 Android 实机验收留待用户执行。

**2026-10-10 更正**：上句是当时的交付记录，不再作为所有异常路径完整的结论。用户反馈和后续源码核对确认：不可访问仓库仍可能显示错误的归档/停用提示，恢复入口仍受旧绑定条件影响，平台能力与完整接续也有缺口。历史测试与产物证据保留；原计划 P3–P5 重新打开，补正范围、依据和固定验收见[处理路径补全设计](../roadmap/2026-10-10-sync-recovery-path-completion-design.md)。本次未修改产品，也未运行新的产品测试。

## 范围与基线

- 计划：[同步异常处理闭环](../roadmap/2026-10-07-sync-recovery-completion-roadmap.md)。输入为原审查 118 项中的 69 项“缺”和 27 项“部”，共 96 项，逐项归入 R01–R29；14 项已有路径、8 项正常/分类对照用于保护既有行为。
- 开始时分支 `codex/sync-progress-display`，HEAD `9a6651d5d8`，工作树干净。原产品代码与 `92d8204ddb` 相同。之后的代码、测试与构建元数据为本次未提交改动，不能把不同阶段的 focused 证据说成最终代码全量通过。
- 两个实施代理均为用户指定的 `gpt-6.1-sol` / `high`：A 负责 GitHub、空间与共享 runtime/controller；B 负责数据问题、原生面板与平台恢复入口。主代理负责设计、接口、独立审查、正式构建与提交。
- 为减少 B 的原生接线与审查修复串行等待，主代理在收回对应写入范围后接手 P2 的 typed 拒收、旧/因果失败计数和迁移修复；A 独立检查主代理实施部分。仍为两名子代理、一轮分段独立审查，Gradle 串行，不增加功能范围。
- 设计映射核验：118 项状态、96 项缺口、29 个处理组；每个缺/部项都有处理组。这只是设计完整性检查，不属于产品行为测试。

## 环境与外部条件

- `python scripts/build-android.py check --signing` 通过：JDK 21.0.11、Android SDK 36/Build Tools 36.0.0、正式签名验证成功；原始版本为 `0.19.4-aex.36` / versionCode 54。
- SDK 的 `android.jar`、`aapt2.exe`、`adb.exe` 已核对存在。正式交付仍使用 candidate 入口，不拿开发中间 APK 当交付，不自动安装或上传。
- 2026-10-07 使用本机会话代理对[公开 App 信息接口](https://api.github.com/apps/mihon-desktop)进行一次只读查询，响应权限为 `administration: write`、`contents: write`、`metadata: read`。这说明当前 App 配置具备仓库管理权限请求基础，**不证明某个用户的旧安装已接受权限，也不证明 token 可以增加安装仓库范围**。
- 创建、安装范围调整、仓库属性修改沿[GitHub 官方仓库接口](https://docs.github.com/en/rest/repos/repos)和[安装接口](https://docs.github.com/en/rest/apps/installations)边界实施。正式用户权限不足时必须保留创建结果和目标，提供必要官方操作并返回继续；不能重复创建。
- 未读取用户 token/设备码，未对用户真实远端执行创建、删除、授权范围或属性变更；因此模拟 HTTP 契约不充作真实账号端到端验收。

## 已取得的 focused 证据

重型 Gradle 均通过协调器串行执行。Windows wrapper 使用绝对路径；遇到联网元数据等待后，后续优先使用已有缓存 `--offline`。编译输入在测试时段内保持稳定。

| 阶段 | 命令范围 / 协调器 key | 结果与边界 |
|---|---|---|
| P2 拒收持久化红 | `:data:jvmTest --tests mihon.data.sync.JvmSyncInboxStorageContractTest` / `sync-recovery-p2-red` | 23 项，1 项按预期失败：被拒收批次未保留可读取记录 |
| P1 授权/管理/限流红 | `AuthProtocolContractTest`、`SyncRepositoryManagementContractTest`、`SyncHttpSafetyContractTest` / `sync-recovery-a-p1-red-2` | 38 项，9 项新增行为按预期失败；无编译错误 |
| P2 作用域/依赖红 | 同 inbox focused / `sync-recovery-p2-boundary-red` | 错误作用域和缺失依赖计数正确复现；其中损坏 JSON 原断言有夹具错误，不能作为该行为红证据 |
| P2 完整计数红 | 单个 1,100 条分页用例 / `sync-recovery-p2-count-red-offline` | 按预期失败；后续修正后完整计数与末页可读取通过 |
| P2 损坏记录红 | 修正真实 revision 夹具，暂恢复该段直解码生产行为后跑单例 | 按预期 `JsonDecodingException`；恢复容错实现后通过，原件保留并显示 UNREADABLE |
| P2 当前稳定绿 | inbox focused / `sync-recovery-p2-green-final` | 27/27 通过；此时第二页真实重取、缺失依赖补取与 P4 接线尚未完成，不把本结果当整个功能已完成 |
| P1 后端稳定绿 | auth、manager、HTTP、discovery 和初始化单例 / `sync-recovery-a-p1-green` | 78/78 通过；仅证明此时后端范围，controller/native 接线和审查修复仍继续 |
| P2 操作与 P4 入口红 | `sync-recovery-b-p2-p4-red` | data 两项实际操作均按预期失败：第二页没有重取、父依赖未补齐；Desktop 5 项中 2 项按预期失败：profile 未隔离、实际启动失败未进入恢复；共享 Compose 3 项按预期缺少处理节点 |
| P2 定向操作绿 | inbox focused / `sync-recovery-p2-refetch-green` | 30/30 通过：第二页拒收重新经过快照与认证接收、按清单补取父依赖、末页指定对象重新投影；保留仍无法修复的事实 |
| P3 控制器红 | `sync-recovery-a-p3-red` | 23 项中 8 项按预期失败：权限分流、真实同步验证、只读检查不能完成恢复、坏绑定出口、平台步骤重启接续、新实例不冒充原数据恢复、账号未知时授权冷却；201 后本地检查点保存失败的防重复创建用例已通过 |
| P3 真实边界红 | `sync-recovery-a-p3-boundary-red` | 6/6 按预期失败：DNS、本地代理 CONNECT 403、HTTP 500 阶段未保留；真实数据修复未接到恢复结果；安全存储完全失败时无 STORAGE 出口；runtime 未传递 HTTP 失败阶段 |
| P4 隔离与返回红 | `sync-recovery-b-platform-callback-red-correct` | Desktop 5 项中 3 项按预期失败：恢复实例仍使用原凭据命名空间、没有持久启动入口、没有拒绝系统 JDK；共享面板的平台回调/冷却用例进入行为失败。前一次 UI 编译失败不计红证据 |
| P3 接线与 P2 审查回归 | `sync-recovery-a-p3-green-and-p2-red-2` | 46 项、8 项失败；仓库管理 17/17 与 HTTP 16/16 通过，P3 仍有 4 项需修正。P2 的损坏事件、因果拒收、旧库迁移三项正确复现剩余数为零；认证后语义错误的夹具先被出站 prepare 防线拦截，不能计为入站红，改为真实 Git 远端文件及认证索引夹具后再跑。此前一次类型编译错误不算测试结果 |
| P2 真实认证接收补红 | `sync-recovery-p2-authenticated-rejection-red` | 1/1 正确失败：真实 HTTP/认证索引/AEAD 接收下，UNKNOWN_PROTOCOL 和 INVALID_PAYLOAD 都被错误折叠成认证失败。出站 prepare 校验保持原样 |
| P2 已收到数据重取补红 | `sync-recovery-p2-duplicate-refetch-red` | 1/1 正确失败：原批次已收到，再次认证通过的重复接收仍留下临时认证失败，剩余数期望 0、实际 1。修复只允许解除临时读取/认证问题，不能抹除语义冲突证据 |
| P2/P3 最小实现绿与迁移 | `sync-recovery-p2-p3-repair-green` | 63/63 通过，1 分 46 秒：inbox 35、共享恢复选择 9、旧数据库兼容 18、暂停计时迁移 1。真实认证后的错误分类、因果/损坏记录保留、旧拒收迁移、重复接收清临时错误、真实恢复核验与 HTTP 阶段均通过；格式整理、原生接线与发布仍未完成 |
| P2 审查修复红 | `sync-recovery-p2-authenticated-context-red` | 同一真实接收表例扩为 8 种内容：原两种原因通过；actor、epoch、space、generation、sequence、batch ID 与已认证外层不符的六种情况仍被折叠，全部正确复现。修复仅将认证成功后的相同 guard 改为具体拒收原因，不放宽接受条件 |
| P2 修复绿与 Android 隔离绿 | `sync-recovery-root-data-android-green` | 54/54 通过，1 分 7 秒：inbox 35、crypto 16、Android profile 3。独立复审确认 guard 的接受条件等价，错误分类改变而校验未放宽；P2 只待统一格式、最终相关验证与提交 |
| P4 原生边界红 | `sync-recovery-b-native-boundary-red` | 1 分 25 秒：共享 UI 5 项因缺少仓库操作、授权优先级和产品解释正确失败；Desktop 取消 1 项因错误进入启动失败页正确失败；Android 6 项覆盖早期损坏 profile、错误进程、存储根、停止后切换和原生返回，均按预期失败。Android 的 18 个失败条目包含既有 Test Retry，不代表 18 个独立场景或多个 SDK；增量编译回退后实际执行了行为测试 |
| P1/P3 原生仓库接线红 | `sync-recovery-a-native-repository-red` | 4/4 失败、39 秒。三项因创建/属性/账号变更确认没有真实动作而期望非空确认得到空；一项因创建入口未接通、未产生已创建 ID 而超时。后者不能当作真实 GitHub 授权失败的证据；实现后须走完创建成功、安装范围被拒、重启后官方选择同一 ID、不重复 POST 的完整夹具链路 |
| P4 启动入口审查红 | `sync-recovery-b-startup-final-red` | 1 分 3 秒，无编译错误。Desktop 两项正确复现坏 marker 早于安全入口抛出、关闭 coordinator 消费空退出回调后不退出；Android 两项正确复现真实安全 Activity 在无 DI 时抛 `InjektionException`、第二个恢复实例未保留直接前驱。Android 的 6 次失败包含 Test Retry |
| P1/P3 原生仓库接线绿 | `sync-recovery-a-native-repository-green` | 13/13 通过、49 秒：原生仓库 4 项与共享恢复 9 项。覆盖非默认名称经真实 controller/HTTP/Git fixture 完成初始化和基线、授权失败后重启接续同一仓库、固定 ID 属性修复后真实同步、账号变化前阻止写入。此时手工创建备用接续、陈旧成功结论失效和 R22 的完整面板重试证据仍在补齐 |
| P1/P3 最后缺口红 | `sync-recovery-a-final-gaps-red` | 3 项中 2 项按预期失败：手工创建的非默认仓库没有明确确认后的接续，历史恢复成功在新故障后仍被显示。失败批量项经真实 controller 生成新确认集合的用例为基线通过，不记成新增红证据 |
| P1/P3 最后缺口绿 | `sync-recovery-a-final-gaps-green` | 33/33 通过、1 分 12 秒：仓库管理 17、共享恢复 15、真实面板失败批量重试 1。手工备用路径须用户单独确认、按固定 ID/私有/空仓库读回；新错误或剩余工作使历史完成结论失效；已成功批量项不会重放 |
| P1/P3 当前页面接续红 | `sync-recovery-a-continuation-red` | 1 项正确失败、40 秒：用户留在当前面板完成 GitHub 选择授权后重试，期望进入密码设置却再次进入错误；创建 POST 仍只有一次。修复须让当前页重试和授权返回都优先接续持久创建记录 |
| P1/P3 当前页面接续绿 | `sync-recovery-a-continuation-green` | 6/6 通过、50 秒：当前面板接续、原生仓库 4 项、明确手工仓库 1 项；重试与重新授权都共用持久创建优先路径。随后冻结 A 的 production 输入，统一格式与最终验证由主代理执行 |
| P3 返回来源回归 | `sync-recovery-a-return-source-red` / `sync-recovery-a-return-source-green` | 复用既有 `generic setup error opens neutral recovery and back preserves source`：红为期望 SETUP、实际 null；跳页前保存来源后 1/1 通过。坏绑定安全入口也保留先前错误页，不新增导航系统 |
| P4 最后回退红 | `sync-recovery-b-final-fallback-red` | Android 两个唯一用例分别暴露 WebView 数据未隔离、真实 5 类 DI 能解析但原实例未核验信息未传入；既有 Retry 造成重复失败条目。共享 UI 的浏览器/剪贴板回退与 HTTP 503 默认引导修网络正确失败；诊断用例首次夹具未 render，不作行为红证据 |
| P4 异常入口红 | `sync-recovery-b-recovery-entry-red-correct` | 3/3 正确失败：修正 render 夹具后外部诊断查看失败未捕获；主页面数据损坏没有修复入口；不可变/坏绑定 STORAGE 被 canChangeSpace 挡住恢复入口 |
| P4 坏前驱启动红 | `sync-recovery-b-bad-origin-red-correct` | 1/1 正确失败、21 秒：从坏 marker 实例创建新实例后，持久返回入口校验前驱失败，使新实例也不能启动。前一次编译失败不算行为红；保留前驱地址、由真正启动入口再次验证原实例，不把原件改成已验证 |
| P4 首次集中补验 | `sync-recovery-b-final-native-green` | 总任务失败、1 分 56 秒。共享 UI 17/17、Desktop 启动 4/profile 6/路径 4/报告打开 1 共 15/15 通过；Desktop 原生 host 2 项未通过；Android 因新增 import 遗漏未执行。部分 max-line-length 需手工格式整理，不能把本轮说成整个原生验证通过 |
| P4 未核验事实与回退红 | `sync-recovery-b-unverified-fact-red`、`sync-recovery-b-network-facts-red`、`sync-recovery-b-official-address-red` | 分别覆盖：未检查却显示原空间已恢复、网络恢复页未消费 TLS/需重启/保存失败事实、官方操作打不开却未显示当前目标地址；每次为新增单例正确红 |
| P4 定点补验 | `sync-recovery-b-native-repair-green` | 总任务仍失败、1 分 51 秒；Desktop host 2/2、共享改动 5/5 通过。host 原失败分别是未显式 stub 的泛型列表导致 fixture composition 中断、LazyColumn 未滚到的焦点节点，不以改成无内容页面绕过。Android 仍因 processName 可空的编译接线未执行；格式器逐文件首个行长错误尚需整理 |
| P4 真实启动接线红 | `sync-recovery-b-real-webview-startup-red-2` | 2 分 36 秒，1 个唯一用例（既有 Retry 使其执行 3 次）正确失败：临时移除真实 `App.onCreate` 的配置调用后，WebView setter 没有收到冻结实例的目录后缀。随后立即恢复生产调用；前一轮因数据层机械换行编译失败，不计行为红 |
| P4 Android 最终定向绿 | `sync-recovery-b-android-final-green` | 56 秒通过：完整 `AndroidRecoveryProfileIntegrationTest`，及真实 native 返回、浏览器授权与诊断分享三个平台用例。`App.onCreate` 真实调用已经恢复并通过；原始目录、错误进程、安全 Activity、数据库/存储/凭据/网页目录与真实 DI 均在此范围内 |

早期一次 A 红测因 B 多文件输入正在落盘导致编译失败，不算行为红；修正编译时段冻结后重跑。一次未配置代理的联网等待和一次代理参数被 shell 拆分均未到行为测试；只终止了相应协调器记录的进程树，随后切离线缓存。没有以这些失败推断产品行为。

Android 隔离的红绿使用 API 35 Robolectric、真实 `App.attach`、Preference/App/Domain DI 与 Debug 的 Framework SQLite / production SyncSecureStore 工厂。早期 secure fixture 的密文长度不满足存储格式，修正后才取得从原目录读取导致的正确红；数据库绿阶段遇到 304 字符的 Windows 宿主临时路径，缩短测试方法名后同一 production 路径逻辑通过。失败条目重复 3 次来自现有 Test Retry 的 2 次重试，不是三个 SDK 覆盖。此证据不代替 Release Requery/ART 或实体设备运行验收。

最终验证资源安排：Android Debug 完整单元验证使用临时 init script，沿用仓库 Release 已有的 Robolectric 类间隔离策略（1 GB heap、每类新 worker）；模块串行、最多 2 workers。此文件只控制宿主验证资源，不改变 APK。Android 28 及以上的恢复实例使用独立 WebView 数据目录；26/27 没有该平台能力，恢复实例关闭内部 WebView/其 CookieManager，保留外部 GitHub 授权路径，不冒用原实例网页登录态。

## 首次完整验证与定点收口

`sync-recovery-final-validation-run` 于 2026-10-07 22:36 开始，执行 52 分 13 秒；未通过。首次结果保留在同名协调器日志及 `sync-recovery-first-full-results.json`，后续补验不能覆盖这个结论。前一次启动在 init script 配置 `buildSrc` 时即失败，未执行测试；改用可空项目查找后才开始此完整运行。

| 完整范围 | 执行数 | 失败记录 | 跳过 |
|---|---:|---:|---:|
| domain JVM | 568 | 0 | 0 |
| data JVM | 892 | 8 | 1 |
| 共享同步界面 JVM | 173 | 7 | 0 |
| core/common JVM | 56 | 0 | 0 |
| core/common Android | 10 | 0 | 0 |
| data Android | 470 | 3 | 0 |
| app Android Debug | 694 | 3 | 3 |
| Desktop JVM（含 integration） | 3,252 | 2 | 3 |

合计 6,115 次执行、23 条失败记录、7 条跳过。Android app 的三条失败是同一个迁移夹具用例及既有 Test Retry 的两次重试；data Android 三项与 JVM 的共享契约失败同源。已有一万/十万事件、文件数据库、首次上传/下载/增量与中断恢复场景在此轮通过。失败分类及后续处理如下，补验结果在收口后追加：

- **实际回归**：损坏切换记录被可选读取吞掉；部分异常页诊断入口遗漏；存储失败仍可能点击关联另一空间。恢复关键读取错误传播、次要诊断入口与禁用保护，不清除原件。
- **新契约下旧断言**：安全恢复页代替无出口错误页；手工仓库接续需要独立确认；错误原因与初始化状态精确化；限流测试按真实授权门限推进时钟。保留零 HTTP、原凭据、固定目标、删除恰一次与重试预算断言。
- **旧数据库夹具**：JVM 两项保留了新 `sync_repair_failures` 表；Android v38 夹具还保留 `planned_at` 等后续结构并硬编码预期版本 40。仅修正旧结构重建及目标版本断言，继续运行生产迁移。
- **格式与证据定位**：数据层三个测试文件的长行仍需处理；Desktop manifest 的七个当前源码锚点因行号变化失效，仅更新行号，不改 capability 状态。
- **另一个测试的异步异常**：Desktop 源列表测试开始前捕获 `getPresentationExclusions` 后台查询的 `stmt pointer is closed`，不是该源列表行为断言失败；将定点复验相关作者页与源列表，记录结果，不扩大产品修改。

定点收口证据：

- `sync-recovery-final-storage-guard-red`：38 秒，更新旧入口断言后，实际 RECOVERY 页的换目标按钮未禁用，新增保护断言正确失败。随后才应用对应共享 UI 修复。
- `sync-recovery-final-repairs-green`：2 分 49 秒，data JVM 9/9、共享界面 9/9、Android 生产迁移 4/4、Desktop 28/28 通过；data Android 4 项中 1 项未通过。Desktop 这 28 项包含源码锚点、作者页生产接线、源列表与原生同步面板；未修改作者页/源列表实现或测试，首次出现的异步查询异常在这次联跑中未复现。
- Android 唯一剩余项揭示刷新竞态：凭据读取后提前发布 `canChangeSpace=true`，下一次关键检查尚未结束时可短暂重新启用按钮。后续最小修复在刷新开始即禁用，只在全部关键读取完成后的最终状态更新恢复；继续复用同一双端契约补验。此轮数据层三个测试文件仍有格式错误，不能将整轮记成通过。
- `sync-recovery-final-switch-green`：1 分 12 秒，data JVM 21/21、data Android 4/4、共享真实恢复向导 1/1 通过，刷新竞态已关闭；整个命令仍因上述三个文件的格式错误失败。
- 格式器报出的行号来自重新缩进过程，不能直接当作未格式化文件的对应位置。为读取实际输出，临时对三个测试文件运行一次取消行长限制的格式化；随后修正四处实际长行，移除临时覆盖，用仓库原规则完成下列正常检查。没有永久关闭规则、修改 `.editorconfig` 或提交临时 init script。
- `sync-recovery-final-checks`：2026-10-08 00:00 完成，1 分 1 秒通过。data、domain、共享同步界面、core/common、app、i18n 的正常 Spotless 检查通过；9 个实际执行的 data JVM 用例覆盖认证后重取、原生建库/授权接续、手工接续确认、属性修改、账号变化、旧实例范围与历史成功失效。未匹配到用例的名称过滤器不计作执行证据。`git diff --check` 通过。

以上为首次完整结果加局部修复/补验的组合证据，不表示最终代码重新跑完并通过全部测试。

## 关键安全审查与待验收点

- 仓库创建先持久化明确意图；响应不确定先读回归属与 ID，不再次 POST。App 尚看不到新仓库时，先补权限再校验，不能卡在“校验要求已有权限、补权限又要求校验”的循环。
- 失败事实按已验证的目标空间记录，不能相信坏载荷自称的空间/代数；不可变原件保留，协议错误不被静默忽略。
- 独立审查曾发现生产接收在 `decrypt → service` 提前折叠 codec 错误，直调 inbox 的测试不能覆盖；reducer 拒绝的事件也未进入恢复剩余项。这两处已在原 P2 接收/归约链路修复，以后续 63 项及 54 项定向结果关闭，早期 30 项绿不作为这两处的通过证据。
- 主代理接手的 P2 经 A 独立审查：认证与防回退边界未放松，临时错误与语义证据分开，分页/旧库保留成立；发现认证后内外身份不符仍会折叠原因。按本轮一次修复复审补同链路红绿并通过；新增六种上下文错误由后续 54 项范围覆盖。
- 剩余问题必须包含缺失依赖；分页的显示、准确计数和实际处理都需要覆盖，不能只有第一页能重取。
- 新增失败记录的 migration 42 将数据库提升到 43。升级测试必须从真实旧表形状开始，保留旧数据并能重开；沿用“最新建表再回迁”的测试需要移除新增表，不能把重复建表错误误判为真实升级失败。
- 新空间正常不能掩盖旧范围未恢复数据；读取旧 switch/scope 记录，明确未知远端缺失，不能仅凭本机计数为零宣称完整恢复。
- 启动安全恢复与普通恢复均保护原数据库和密钥；新 profile 与偏好隔离，不能复制 actor/token，不能通过移动目录破坏绑定目录的 wrapping key。
- 原生入口审查额外核对启动早期：Desktop profile 参数解析先于正常启动异常捕获；Android 的错误进程需保留 raw context，安全页也不能依赖尚未初始化的业务 DI/主题。关闭 Desktop 旧实例须沿真实 close coordinator 完成且退出一次，不能先消费空退出回调再留下已关闭 runtime 的窗口。相关项在实施批次内补回归，未以代码存在认定通过。
- 仓库 API、控制器动作、原生 UI/导航/平台接线、恢复后实际同步和最终正式产物仍需逐项验收，完成时在本记录补充真实证据。

本批跨越 8 个以上文件和 400 行：这是同一恢复能力在 HTTP、持久化、共享状态/面板和双端 adapter 的必要连通，不按机械行数拆开不可独立使用的片段。主要风险为写入归属、保留数据、启动隔离、恢复结果的真实性；用对应真实契约与平台集成覆盖，不扩大到无关模块重构。

离屏布局来源为实际共享 `SyncPanelContent`，Windows 宿主 `ImageComposeScene`、测试 Material 深色主题、400×800 与 900×600。主代理查看两张实际 PNG，修正了初版把 SOURCE 待处理误描述为“更换空间”、无检查证据却显示“原空间已恢复连接”的问题；修正版内容可滚动、主操作可见、无水平溢出。产物为 `presentation-sync/build/sync-visual/recovery-400x800.png` 与 `recovery-900x600.png`，可用相同 focused case 加 `-PsyncVisuals=true` 重现；不宣称这是实体 Android 截屏或真实远端验收。

## 实现复用与不能伪装为成功的条件

| 处理组 | 共用生产路径 | 完成条件与边界 |
|---|---|---|
| R01/R03/R05/R25/R26/R29 | 共享 panel/controller、持久 recovery flow、请求门控、run store 与 coordinator | 实际交换结果、未发布工作、失败事实共同判断；等待服务、冷却、用户暂停和活跃 owner 均不能被强行越过 |
| R04/R06/R07/R08/R09/R10 | 原 GitHub 客户端、固定 ID 仓库管理、创建记录和 onboarding/switch 事务 | 权限允许才原生创建/授权/修改；官方同意返回继续同一目标；不明写入结果不重复 POST；平台停用需要有权主体解除 |
| R11/R12/R13 | 原生安全入口、独立实例、原备份页面、原密码设置和有限重建 | 保留原目录和凭据；无法读取原材料时新实例成功只证明新范围，普通备份不冒充同步密钥包；无可信兼容安装包时仍待处理 |
| R14/R15/R16/R17/R18 | 原 inbox/归约器、认证快照、repair report；无法原位恢复时共用 R07 | 重取仍执行认证与因果校验；拒收、缺依赖和旧范围证据保留；新身份/新空间由现有 importer/journal 生成基线，不修改旧事件身份，不截断超限内容 |
| R19/R20/R21/R23/R24 | 原扩展、迁移、漫画详情/阅读、备份恢复页面与 request ID 返回核验 | 携带真实 source/对象定位；无法确定作者/章节映射则保留失败并走可信副本；进入页面或恢复部分备份不算整个同步恢复 |
| R22 | 原 bulk projector 与确认 UI | 按仍适用的 FAILED 条目生成新的冻结集合，重新确认；APPLIED 和已失效条目不重放 |
| R27/R28 | 原诊断文件边界、原授权状态；共享页面的内置回退 | 外部查看器、复制或浏览器失败仍保留任务；页面保留脱敏诊断快照、修复结果摘要及当前官方地址，支持手工完成后继续。没有新增通用失败文件阅读器，不能因日志导出失败抹掉原问题 |

没有真实账号写入/第三方服务恢复/实体设备操作的证据，不记为实机完成。完整设计映射在 roadmap；上表解释复用边界，不替代最终测试和构建记录。

## 正式产物与完成状态

Android 已分配 `0.19.4-aex.37` / versionCode 55，包名和原证书保持连续。Windows 由统一 `build-desktop.sh build-only` 执行：复用首次完整 Desktop 验证加相关定点补验，不重复全量；仍执行正式发布编译和生产运行验收。首次 Windows 启动因命令行代理 bypass 参数的管道符被 shell 拆分而未进入 Gradle，保留失败 BUILD 83 记录，修正后分配 BUILD 84；不把启动失败算作产品编译或运行失败。

2026-10-08 本机 ADB 未连接实体设备或模拟器，因此 Android 安装、ART 运行与真实账号端到端留待用户验收，不会标成已通过。

### Android 正式候选

- 统一入口 `python scripts/build-android.py candidate --offline` 成功，未安装、未上传。随后独立执行 `verify --artifact <下列 APK>` 成功，正式产物文件存在。
- [验收 APK：0.19.4-aex.37 / versionCode 55](../../app/artifacts/android/0.19.4-aex.37-vc55-9a6651d5d8-release/Mihon-Fork-0.19.4-aex.37-vc55-release-universal.apk)。正式 fork 身份 `app.mihon.desktop.fork`，不可调试；minSdk 26、target/compileSdk 36，包含 arm64-v8a、armeabi-v7a、x86、x86_64。
- R8 与资源压缩开启，签名 v2/v3 复核通过；证书 SHA-256 保持 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`。
- APK SHA-256：`a47d18ac589f8895b28e947a8bd0612a220b4134d9d88fc99d6293155c2a3c6b`。
- 候选清单记录 `sourceRevision=9a6651d5d8a68b068e3d51add2bf512a2691ec9f`、`sourceDiffSha256=82042e0077bef2aed1784296b3d56b6792b2e2e1af1a46ac4517c2e7f46b6bf7`、`untrackedInputsSha256=984fea37aca8d329d18a59c4a6e7a61f674d1da4b1b9c2bef176b2fab056caa9`、`productionInputsSha256=ffc48ee254b1b55652f808142b43e93eecc8cdc9209ecf5b55cce08d28f7ae3e`；构建期间输入冻结，后续只补文档与提交，不修改产物输入。
- 本次功能、测试、构建元数据、roadmap 完成标记和此记录同批提交；最终提交 hash 以本文件的 Git 提交和交付回复为准，不能把产物文件名中的基线 hash 当成最终实现提交。

### Windows 正式构建与运行

- `sync-recovery-desktop-release-run` 通过；统一脚本的发布编译耗时 1 分 34 秒，随后通过真实发布运行时的版本、扩展 APK 安装与 source 加载验证，并完成未打包应用与 ZIP 发布。最终版本为 `0.11.19.84.9a6651d`；版本尾部是构建基线提交，不代表本次未提交差异已在该基线中。
- [正式未打包 EXE](../../app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.84.9a6651d-unpacked/Mihon%20Desktop.exe)；ZIP SHA-256：`579190d34fff8dddfa0bf0814cd084a83b7b6c5aa59e50388175cc4483f6641b`。此地址来自构建日志 `Final unpacked EXE:`，不是临时构建目录。
- 在该正式 EXE 上运行独立临时 profile：真实同步 controller → 首次设置入口；操作系统安全存储写入后完整退出、重启读取；坏恢复 marker 的安全窗口不打开业务库且保留原 marker；新恢复 profile 创建独立数据库、原 profile 不被打开、显示预期版本并正常退出。2026-10-08 00:06 全部通过，记录位于 `.gradle-coordinator/sync-recovery-runtime-596655b2e26c4bcb96c95e3902a591a2/result.json`。
- 第一次窗口检测使用 `Process.MainWindowHandle`，未识别隐藏启动窗口而超时；改为仅枚举本轮拥有的进程及子进程的 AWT 窗口，随后同一 EXE 通过。只改临时验证脚本，没有为通过检查修改产品，也没有读取桌面屏幕像素。首次超时不记为通过。
- 未在 macOS 运行本轮产物；不将 Windows 验证扩称为所有操作系统实机通过。

## 用户验收路径

以下是验收操作及预期，不是已经完成的实机结果。使用既有验收账号/空间；不要求破坏日常使用的数据或仓库。

- [ ] 下载上述已签名 APK → 覆盖安装已有 fork → 关于页面确认 `0.19.4-aex.37`，原有书库仍可访问。
- [ ] 已删除验收空间 → 重新连接 GitHub → 授权返回：页面应根据当前检查结果提供创建/关联入口，已完成授权的状态可见，不只剩“重试”。
- [ ] 创建同步空间 → 输入一个新的名称 → 查看目标与保留范围 → 取消：不创建仓库；再次确认后完成创建与权限检查，继续密码设置和真实首次同步。
- [ ] 仓库已创建但还需 GitHub 选择访问范围 → 关闭/重开面板 → 完成官方授权 → 返回继续：沿同一个仓库接续，不再创建第二个仓库。
- [ ] 存在明确的公开/归档属性问题 → 查看修复说明 → 确认：对显示的目标修改属性并读回核对，再继续同步；权限不足有官方处理与返回入口。
- [ ] 网络故障 → 从同步页打开网络设置 → 修正/按提示重启 → 回到原恢复任务：已经完成的步骤保留，核验执行真实同步，仍失败时保留具体问题。
- [ ] 数据拒收/源不可用 → 查看受影响项目 → 重取或进入对应扩展、迁移、阅读/备份页面 → 返回：未修复项仍在计数中；仅新空间同步成功时，旧范围未恢复的说明仍可见。
- [ ] 统计期间暂停/恢复 → 动画暂停后恢复无限等待；完成统计后显示完成/总量并开始计时。ETA 未形成时只显示已用时间；暂停后计时冻结。
- [ ] 完成一次恢复核验 → 后续再出现故障 → 重新打开恢复页：显示当前待处理问题，不沿用历史成功结论。
- [ ] 窄窗、平板竖屏与横屏 → 进入较长恢复清单/确认框：内容可以滚动，主要操作与返回入口可达。

坏数据库、安全存储完全不可用、晚到请求、创建响应丢失和失败批量决定等破坏性或难以稳定手动触发的情形，以本文所列生产链路契约、启动/导航集成与正式运行验证为验收依据，不要求用户损坏自己的数据来复现。

## 2026-10-09：为当前真机重新构建升级候选

- 基线为本分支 `343ab3d3bd`，工作树起初干净。本次仅调整正式 Android 版本元数据，沿用已交付的同步恢复实现，不合入其他工作树的功能。
- ADB 只读核对：当前真机已安装 `app.mihon.desktop.fork`，`0.19.4-aex.46` / versionCode 64，证书与原正式证书一致；设备 API 31，支持 arm64-v8a 与 armeabi-v7a。核对各已登记 Windows 工作树的版本及正式候选目录后，已分配的最高 code 为 64，本次分配 65 / `0.19.4-aex.47`。
- 统一命令 `python scripts/build-android.py candidate --offline` 成功，完成 Release、R8、资源压缩和原证书签名。随后通过构建入口的真实 `verify_artifact` 校验，重新读取当前设备安装包，确认新包的包名/证书一致、版本 65 大于 64、minSdk 与 ABI 兼容。构建后产品输入摘要仍与候选清单一致。
- [新验收 APK：0.19.4-aex.47 / 65](../../app/artifacts/android/0.19.4-aex.47-vc65-343ab3d3bd-release/Mihon-Fork-0.19.4-aex.47-vc65-release-universal.apk)，SHA-256：`4982bd37702dbe0af0e1bad70fd4018c7312dac99542ae8ed87ca43209aa6474`。
- 本次没有功能代码变更，验证采用正式构建、签名、版本和目标设备兼容校验以及 `git diff --check`，未重复功能测试。覆盖安装及应用运行由用户验收；本次未执行安装或操作设备上的应用。
