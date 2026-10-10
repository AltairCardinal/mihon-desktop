# 整轮同步计数与简洁进度验收记录

状态：SP04实现、定向验证、独立审查与必要修复复审完成，正式三端产物已生成并校验；本记录随功能提交。首次集中全量有2项失败，修复与复验边界见下文，不宣称首次全量全绿。启动基线 `1d3c6c1f63`，工作树 dc4c，分支 codex/sync-progress-display；启动时干净。用户批准两行UI、确定进度条，要求统一“同步中”及上传/下载/双向完整总数。

规格：[用户确认修订与T01–T06](../2026-09-28-sync-progress-display-design.md)。计划：[SP04](../roadmap/2026-09-28-sync-progress-display-roadmap.md)。旧SP03真实账号、实体Android与原生键盘/读屏门禁继续待验，不以DEMO通过代替。

## 过程证据（阶段性记录，以最后收口结果为准）

- HTML红：新增方向共享整轮契约预期“同步中，已完成0/300条”，旧实现返回“上传中”，正确失败。
- HTML绿：`node --test docs/prototypes/multi-device-sync/sync-progress-compact.test.cjs`，6/6通过（含双端真实Chrome、320px大字、暂停恢复及关闭重开）。
- 数据接口红：实施代理报告冻结总数预期5实际0；未确认PLANNED旧finish错误报SUCCEEDED。原始日志位于忽略的`.gradle-coordinator/sync-round-plan-red.log`及`sync-round-recovery-red.log`。最终结果待整合核验。

- 完整DEMO：全部14个`*.test.cjs`串行运行，51/51通过，193秒；修改脚本`node --check`和`git diff --check`通过。
- Android `build-android.py check --signing`退出0，JDK21/SDK36及原证书验证通过；不是新正式候选或实体运行证据。
- macOS实际JDK21可用，独立worktree `/Users/altair/github/mihon-sync-total-20261002` 基线1d3c6c1f63；旧脏工作树和日常应用保留。预检可用磁盘8.9GiB，正式构建时复查，不清理其他业务。

- 数据稳定候选：`sync-round-data-stable`退出0，1分26秒；JVM70/70、Android18/18，共88项，Spotless及diff检查通过。主代理核对真实XML（RunStore18、RuntimeWiring28、文件DBRestart3、两端storage/panel），安全失败报告另2项。新契约10项×双端20通过，原回归继续保留。
- 独立审查首次数据里程碑：逻辑未见阻塞，但>128覆盖只走helper，尚不能证明生产冻结不是首页128；要求原实施代理补真实exchange+StoreReporter大批量用例，接口尚未批准下游UI。属于同一连续审查与既定T01范围。

- 审查补证据：真实GitHub transport、authenticated snapshot和加密批次的130下载+1上传，production首readBatch要求131/0。把preflight暂误改为`discovery.pending()`时正确得到129/0而失败（测试执行1.356秒）；恢复`allPending()`后JVM1/1与Android1/1绿，format/diff通过，47秒。未降低下载投影守卫，安全停止仍保留全部成员，不计尚未合并的下载。原88项回归仍适用，总90项有效focused证据。

- 同一连续审查的数据里程碑PASS，已批准UI依赖：审查者独立读取code/log/XML，确认持久计划、owner/active-space原子冻结、PLANNED与真实PENDING隔离、安全ACK恢复、生产无liveProgress接线及新增工作延期。该PASS不代表UI或整批交付完成；最终共享UI/ETA稳定后继续同轮检查。

- UI红绿：首轮production projection6+真实Compose2，旧实现8项正确失败，最小compact/整轮safe-rate后8项绿。固定布局红测抓到主按钮475→523（48px），按冻结N与时间格式仅测量两个字段必要高度后9项绿，含8配置矩阵、320dp/200%无裁切、999→1000计数和ETA切换按钮差≤1dp；没有保留七槽空白。最终真实controller及平台回归尚待完成。
- 新正式Android版本分配：统一属性由vc37/aex.19递增为vc38/aex.20，package与原证书指纹不变；当前仅分配，尚未正式构建或安装。

- 真实Controller补线：已打开MAIN冻结计划后原5秒等待超时，新增按可见run的既有观察流后即时0/1。JVM/Android观察隔离各1项绿，覆盖0→5总数、0→3→5安全完成、并发refresh无倒退、关闭取消订阅、重开/终态/新run及space/generation切换；不增加周期DB查询/DI/API。新增桥只更新同run的N/X，最后同轮审查仍需检查。
- 相关共享UI87/87绿；PAUSING实际Disabled门禁始终保留，旧失败来自控件语义断言而非产品放宽。实际Enter down/up、焦点、终态详情→运行隐藏→终态恢复、polite仅状态播报均验证。
- 平台focused 5分9秒：AndroidPanel10通过/3既有Windows FileProvider宿主skip，AndroidRuntimeWiring14、DesktopPanel1/Wiring2、布局2通过。不是实体Android/系统读屏证据。
- 原生8图自查后，仅为当前状态+冻结N最大计数预留必要高度，消除长等待文案造成的2–3行空白；普通计数/ETA稳定门禁保留，明确hold状态可必要重排。
- 最后薄adapter自查：无run busy统计计时红测9000秒（应0）、超大ETA被Long饱和（应未知）已定位并做最小修复，最终绿待核验。另基于用户10分钟约8000条及256/ACK推断20秒确认节奏，原10秒窗口无法凑够3个确认增量；已冻结仅whole-confirmation adapter的9点/5分钟、3增量3秒、10–120秒自适应陈旧门禁，Timeline未改。slow-ACK红绿与最终candidate待完成。

- 最终薄adapter回归：`sync-compact-race-green`退出0，真实XML确认Presentation10、原生矩阵1、JVM/Android观察各1，共13通过。20秒/256条安全确认得到722秒估时，最后确认15秒仍可用，40秒陈旧撤下；无run计时和溢出修复通过。
- 受控旧snapshot红：让真实refresh读出旧N=null/X=0后挂起，冻结计划/安全确认已发布再释放旧refresh；两平台正确失败。仅同run/space/generation合并不可变已知N与单调安全X后绿，新身份不继承。随机并发绿不足以证明没有该竞态，最终以闸门红绿为证。
- 最后格式检查曾在自动修正嵌套缩进后暴露条件表达式超过120字符；机械拆成if块后`sync-compact-format-finish2`22秒通过。
- 最终相关首轮presentation101项中100通过，R05旧测试等待本轮要求隐藏的独立retry倒计时而超时；只迁移为首行等待状态及无独立倒计时，真实持久nextRetryAt、同run和禁用重试门禁保持。该次未continue，后续data/platform未执行，不计为最终绿，定向复验进行中。
- 新版本签名前置检查退出0：vc38/aex.20、SDK36/JDK21和原证书验证；当前仍不是正式候选运行证据。
- 定向收口：`sync-compact-final-hosts`退出0，6分27秒；最终候选133项，130通过/3既有Windows FileProvider跳过。100项显示/终态绿与最后33项真实controller、两端观察及平台接线证据合并；夹具仅增加有界等待相同真实节点，未降低wiring断言。相关Spotless/i18n及diff检查通过，全部记录进程已结束。
- 同一连续独立审查最终PASS，无未关闭代码阻塞；审查者独立核对生产计数、计划、安全确认、ETA、观察订阅/竞态、终态与暂停入口、R05及夹具断言，未运行或复制实施者实现。实际Tab/系统读屏仍属未验人工门禁。
- macOS独立工作树原生定向验证：`sync-total-macos-focused`退出0，真实XML为data70、presentation91、Desktop3，共164/164通过；随后只同步两个测试夹具的最终修正，production未变。

## 内聚性与风险

### 集中验收发现与必要修复

唯一一次 Android/shared 集中全量 `sync-total-android-full` 用时31分45秒，在 data JVM 的782项中2项失败、1项跳过；此前执行的 Android release、domain/core JVM 均通过。全量并未使用 continue，因此未执行的 data Android debug、共享UI、测试客户端和格式另以 `sync-total-android-remaining` 接续，3分6秒通过：371、107、52项均零失败。没有重新执行整个全量。

第一项失败是默认7样本的 `SyncScaleAcceptanceTest`。原始等待超时发生在安全完成计数，不是导入通知丢失。将原成功/上传量/持久计数/队列断言提前后，实际结果为FAILED/UNKNOWN/上传0；临时JFR捕获原生产guard“sync upload does not extend the actor sequence”。确认原因是本轮冻结计划按随机batch id执行，可能先传序列后半段。修复只复用原outbox actor/epoch/first_seq顺序再过滤冻结成员；固定N、ACK、远端守卫及schema保持不变。

确定性红绿覆盖反字典300条两批上传，以及556条三批发布后确认中断/恢复/新工作延期：Android/JVM共4项红后绿，`sync-upload-sequence-green`1分11秒通过。相关 `sync-upload-sequence-related`4分27秒、61/61通过，含原默认7性能样本全部SUCCESS/上传300、28项RuntimeWiring、完整6项S2及双端共享契约；原性能阈值和认证断言未降低。既定唯一修复复审PASS，未追加代理或审查轮。macOS同步最新4文件后 `sync-total-macos-ordering`1分2秒、3/3通过。

第二项全量失败是 `SyncS2ContractTest` 的 `UncaughtExceptionsBeforeTest`，并非single-flight断言失败。原cause未保留，单项及完整S2在RuntimeWiring之后均通过；来源仍未定位，不能宣称该异常原因已修复，也不能把第一次集中全量描述为全绿。定向复验和剩余任务的绿结果按实际范围报告。

本轮跨既有 outbox/discovery/运行存储/交换/coordinator/共享UI及文档，合计会超过8文件或400行。范围是同一个用户能力的完整接线；若拆开，固定分母可能与实际交换或恢复脱节，不能独立验收。主要风险为安全确认、运行所有权、恢复和冻结后新增工作隔离，由稳定数据接口的独立检查及真实SQLite/交换/coordinator共享契约覆盖；没有远端协议、加密或schema迁移。

## 正式构建与交付

完整Desktop `scripts/build-desktop.sh full-tests` 经协调器 `sync-total-desktop-full`执行，5分14秒退出0；实际431份XML、3239项、3236通过、3条件跳过、零失败。后续构建使用build-only，未重复完整Desktop测试。

macOS原生 `scripts/build-desktop.sh build-only` 经隔离协调器执行成功，正式应用为 `0.11.19.71.1d3c6c1`，路径位于新工作树artifacts/macos，不覆盖日常/Applications。真实应用在UUID隔离profile下通过MAIN打开、关闭重开和SETUP入口，正常shutdown。ZIP回传Windows后CRC与SHA-256校验通过：`ad3a48754b67250fbc0cc201ee0e7657580dbd4d0d30d69b18e1f8e9462575e3`，196649230字节。实际业务账号与系统读屏不在该空账户Test Mode证据范围。

Windows首次build-only已分配BUILD71，但PowerShell启动在日志输出前失败；绝对PowerShell7与系统PowerShell的File启动同样未执行脚本。解析及独立子进程检查通过；最终以协调器foreground通过系统PowerShell Command入口调用同一个 `scripts/build-windows.ps1 -SkipTests -VersionAllocated -ExpectedVersion 0.11.19.71.1d3c6c1` 接续成功，未改构建脚本或再次分配版本、未绕过测试/production runtime验收。File启动失败的底层原因未确证。成功日志 `sync-total-windows-command.log` 及其终态退出0；正式EXE为日志Final unpacked EXE的实际路径。脚本自带真实扩展安装/版本验收通过，发布后EXE在UUID隔离profile下另通过MAIN开关重开、SETUP入口及正常shutdown。

Android `build-android.py check --signing`、`candidate --offline`、`verify --artifact` 均退出0。candidate Gradle 2分27秒；aex.20/versionCode38、原证书、v2/v3签名、四ABI、R8及resource shrinking核验通过。构建过程中冻结全部源码，源revision+diff及production inputs均写入artifact.json；正式产物在功能提交前构建，版本后缀因此保留基线hash，而不是未来提交hash。最后只修改验收文档/checkoff，不改变已构建production inputs。APK SHA-256：`2bf9d7f15ca6fb3ecfb31ae0a5b63fe7d2f8cacb306d17608a7294bbe7342d63`；production inputs：`2413ef5384cb63701adaae336ca6ab3383ddc50c57087d4568e748b265e902fa`。

### 产物

- [Windows正式EXE](<D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.71.1d3c6c1-unpacked/Mihon Desktop.exe>)：完整未打包应用目录内启动，勿单独复制EXE。
- [macOS x64正式ZIP](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.71.1d3c6c1-macos-x64.zip)：保留app包；仅验证Intel Mac，未生成Apple Silicon独立构建。
- [Android正式APK](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.20-vc38-1d3c6c1f63-release/Mihon-Fork-0.19.4-aex.20-vc38-release-universal.apk)：未安装到用户设备，实际升级与实体运行待用户验收。

## 用户验收步骤及保留门禁

本轮全部产品实现使用T01–T06。运行期只显示状态/完成数和已用/剩余估时两项、确定进度条及必要操作，MAIN和首次合并共享；上传、下载、双向统一“同步中”。N由本轮全部上传及下载成员一次冻结；X仅取持久安全ACK或下载投影完成，字节传完不算完成。条目是数据变更记录，不等于漫画数；通常按安全批次跳增。统计完成前显示“正在统计数据”，不能未认证远端就捏造完整N；实际批次开始前显示0/N，方向切换不重置。剩余时间默认—，取得3个足够跨度的安全确认增量后显示；暂停、恢复和陈旧样本撤下估时。

1. 在正式Android候选和Windows/Mac应用中进入书架→同步，确认使用同一个测试空间。保留本地数据，不必删除漫画来制造同步工作。
2. **上传**：A端新增收藏或修改阅读状态，B端不做本地修改；A端立即同步。统计结束后0/N→X/N→N/N，进度条与X/N一致，只有两项信息，耗时持续更新。
3. **下载**：A成功后，B端立即同步。B仅接收A的改动，显示仍为“同步中”；分母固定、确认完成后书架/阅读状态更新。
4. **双向**：A修改一本漫画的阅读状态但暂不同步；B修改另一本漫画并先完成同步；再让A同步。A同时接收B并上传自己的修改，本轮N包含两个方向且方向变化时不回零；完成后B再同步核对两端状态。
5. **恢复**：在有足够条目的本轮暂停，记下X/N，继续并关闭重开同步面板。相同run的N不变、X不倒退；显式重新同步/重试产生新run时允许新计划。运行期新修改留下一轮，不偷偷增加N。
6. **边界**：估时初期/暂停可为—；无待同步工作合法0/0；失败或部分完成仍能查看原因/失败日志及执行原恢复操作。观察真实账号结果，不把本地DEMO或空账户Test Mode当成真实服务验收。

SP03整体保持未勾选：真实账号同步、实体Android升级/运行、原生Tab焦点/系统读屏等人工必做门禁没有本轮现场证据。SP04本轮实现、审查、自动化、正式产物与提交完成，不等于旧roadmap全量人工验收完成。
