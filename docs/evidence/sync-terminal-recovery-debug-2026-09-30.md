# 同步终态恢复与真机 DEBUG 验收记录

日期：2026-09-30。状态：已完成实现、独立审查、真机与双平台验收；随本轮聚合提交收口。以下阶段性记录保留当时进度，以末尾最终结论为准。

需求与验收权威：[修复及 DEBUG roadmap](../roadmap/2026-09-30-sync-terminal-recovery-debug-roadmap.md)。原稳定摘要规则及安全确认语义继续有效。

## 授权、范围与基线

- 用户明确授权自行执行方案、真机 DEBUG、必要升级及恢复/真实同步操作，直到原问题解决；凭据和密码仍走原安全流程，不提取或记录秘密。
- 实施工作树 `D:/Codex/worktrees/dc4c/mihon`，分支 `codex/sync-progress-display`；启动 HEAD `c00eed9a1098d435227040bc59f474a882e3233b`，工作树干净。主规划树及其他并行修改未编辑。
- 复用 Runtime → Controller → 共享 Compose → Android/Desktop wrapper，不新增第二套同步引擎、不修改协议/schema、不清空历史。
- 预算：最多2个子代理，同一实施者串行完成主要修复/诊断，1位独立审查者；独立审查1轮及必要修复复审1轮。focused 红绿验证，完整 Android/Desktop 各一次；正式候选先取证再按实际必要改动决定是否重建。
- SOURCE：当前生产提交的连接/运行选择及操作链；PROJECT_POLICY：roadmap R01–R08、诊断脱敏/限额和原生验收；HTML 原型仅为已审核信息结构参考，本次不修改原型。

## 变更前真机观察

核对目标实体设备为已连接的 Huawei PCE-W30。设备序列号只用于本轮本地命令，不写长期报告。

- 16:57 再次核对已安装 Release 包 `app.mihon.desktop.fork`，versionCode36 / `0.19.4-aex.18`，非 debuggable。实际设备 base APK 的 SHA-256 为 `d48be92492cde37707799828ec1189680df022909bf7368f199548bd93f15245`，与此前正式候选一致。
- 签名预检 `python scripts/build-android.py check --signing` 退出0，原正式证书 verified；已登记本地有效 worktree 的发布配置最高 versionCode36，不复用已有正式版本号。
- 唤醒非安全锁屏后读取原生 UI：两处“本次同步已取消”、本次已确认1536条、已用291:54。“立即同步”文字节点 enabled=true，但其实际按钮父节点 enabled=false；以父节点作为按钮可执行性证据。
- 关闭面板后，通过层级匹配书架“同步”入口重新打开，16:57:36再次读到相同终态、确认量及历时。没有点击授权或同步，没有清数据或读取私有数据库。
- 设置只读核对：启动时同步开关 checked=false；定期同步选中1小时；密码保护开启；“重新连接 GitHub”入口存在。未改变偏好，设置返回 MAIN 后历史仍为1536条/291:54。生命周期/升级验收需考虑原定期触发，但不能由此反推连接已启用。
- 当前应用进程存活。`dumpsys activity services <package>` 为 `(nothing)`；系统 JobScheduler 中目标包 `NOT active, 0 running bg jobs`。这些只说明采样时段，不能排除此前重试或未观测的进程内工作。
- 变更前截图保存在忽略的过程目录 `.gradle-coordinator/sr-device/baseline.png`，只作本地取证，不作为产品视觉基准。
- 18:43在原Release暖启动进入应用后，通过实时层级“同步”重新打开面板，仍为1536条/291:54，主按钮仍禁用；较16:57的UI观察跨度约106分钟。中途前台为系统管理页面，未据此推断应用状态；显式进入目标MainActivity后才读应用层级。该补充仍不替代coordinator/持久字段取证。

结论：按钮入口缺陷已通过 UI 与源码组合证实。长历时在采样中冻结，当前系统采样不支持持续运行猜测；仍须读取原始连接字段、active/latest、持久时间与 coordinator 事实。打开刷新已有实现，不能仅因重开看到相同结果就诊断为缺少刷新。

## 平台环境

- Windows：JDK21.0.11、Android SDK36及原签名预检可用。真机为Android12/API31，最终验收使用原证书非debuggable Release，不以JVM/Robolectric代替实体ART。
- macOS：`mbp-lan` 可达，macOS14.8.4，当前剩余磁盘约14GiB。上轮隔离 checkout 存在未提交 `AppVersion.kt`，按用户改动保护，不覆盖。本轮另建 `/Users/altair/github/mihon-sync-terminal-20260930`，以本地 clone 加小型增量 bundle 核对为 `c00eed9a10` 的干净独立源码，后续配置/dist/app也隔离。
- 现有失败日志会包含作品、URL 和事件细节，不用其内容作为本次诊断报告；诊断只复用安全文件生成和平台打开/分享模式，另行生成脱敏事实。

## 实施、审查及验证

- 首轮启动/编译夹具错误未计为行为红：Windows coordinator 需 bare `gradlew.bat`；共享 UI 模块不能调用 data internal `awaitIdle`，改为观察真实 state 谓词，不扩大生产 API。
- `sr01-red` 09:01:52结束、退出1，86项中7失败。实施者确认 R01 disabled+CANCELLED 的真实 Compose 主操作断言失败（Disabled=true），R04 缺少原因入口，R05 停用连接时 PAUSED 操作仍可执行，R06 缺少上次结果语境。R02 原链路已有新运行与旧1536记录分离的通过证据。Windows 文件句柄清理遮住的断言单独补正夹具，不作为产品根因。
- 第一轮 focused green 90项通过；新增 `sr01-source-red` 行为红确认当前 ACTIVE BLOCKED 被误标历史。最小增加 controller 真实 `runSource`（ACTIVE/LATEST），不修改 active 查询集合、调度或数据库格式；ACTIVE BLOCKED/有待确认下载 PARTIAL 保留当前恢复语境，LATEST 才标上次。新增慢连接读取跨空间刷新及旧 fact 晚到用例、共享 JVM/Android 文件存储取消后新任务契约，仍在最终 focused 验证。
- 来源行为绿 `sr01-source-green` 91项通过。R08 大字红 `sr01-layout-red` 确认320dp/200%主操作文字实际 TextLayout 为144×32且 overflow=true；按有限主操作资源、真实 labelLarge/padding 预留槽高后8种布局检查通过。主代理查看离屏英文候选另发现第三行首字母被胶囊圆角裁切，交原实施者仅修主操作形状；其他按钮不改。候选图为真实 Compose 离屏样本，不能替代真机 Release。
- `sr01-verify` 集中 focused 验证退出0，耗时5分49秒；主代理独立读取JUnit XML：共享 UI/投影/真实controller 92项、JVM存储1项、Android Release存储1项、Android wrapper9项、Desktop wrapper1项，共104项无失败/错误，3项Android文件分享用例因Windows的Unix权限夹具限制跳过，待Mac补验。presentation-sync/data/app/i18n格式检查通过，未跑完整Android/Desktop。主代理再次查看320dp/200%英文离屏图，第三行problem完整可见，局部形状修复未再裁切。
- SR01 focused暂时稳定，同一实施者串行接续SR02；独立审查、正式包及真机验收尚未执行。
- 已核对所有本地有效worktree的发布配置及已有候选目录最高code36，为本轮诊断候选在唯一发布配置预分配code37 / `0.19.4-aex.19`；原证书不变。构建前再次检查候选冲突，不把版本预分配当构建或安装成功。
- SR02 首轮行为红 `sr02-red` 退出1，54秒，真实 JVM 文件存储契约2项失败：诊断尚不能返回停用连接与历史状态、不能区分不兼容解码与停用投影。该次编译成功，失败来自行为断言；最小实现继续进行。
- SR02 新边界夹具最初未把读取 gate 接入真实 secure store，等待 gate 的超时不作为行为红。补正夹具后的 `sr02-boundary-red-fixed` 36秒退出1，5项中3项因正确行为断言失败：读取异常仍误报OK、真实解码期间切换空间未标为INCONSISTENT、内存转换超过128条。停用/不兼容2项已通过。平台导出、实际诊断入口与反馈接线继续串行TDD。
- 独立审查者先只读审查稳定SR01，未发现阻塞问题；核对production选择、真实点击/存储断言、红绿日志及窄屏图，不重复启动Gradle。SR02仍未稳定，安全边界尚未通过独立审查，尚不安装新诊断包。
- 本次变更超过8个文件/400行，内聚范围是同一恢复与取证链的共享Runtime/Controller、共享Compose、平台导出adapter及其真实存储/wiring测试和资源。风险集中在连接守卫、历史/活动选择、采样一致性与文件脱敏边界，交由独立审查；不按文件或行数拆开不可独立验收的上下文，不改同步协议、数据库schema或取消算法。
- `sr02-wiring-red` 的Android方法筛选叠加 `forkEvery=1` 导致大量短命worker；仅通过协调器停止该记录进程树，终态CANCELLED/exit130。夹具越界和进程遍历不计作产品失败；受影响完整测试类将集中focused验证，尚未运行完整测试。
- 任务尚未满足实现、独立审查、验证、提交全部条件，roadmap 保持未勾选。

## 真机判别及正式交付

### 独立审查与正式候选

- SR01/SR02同一轮独立审查PASS；审查者未实施目标代码。核对当前有效JUnit XML共158项，0失败/0错误，3个已有Unix权限夹具在Windows跳过。`sr02-verify`唯一失败为commonTest使用Java11 `Files.writeString`不能在Android test编译，等价改为UTF-8 `Files.write`后，`sr02-storage-verify`退出0；格式检查通过。生产实现无该JavaAPI问题。
- 原证书正式候选versionCode37 / `0.19.4-aex.19`已构建、verify、升级成功。文件：`app/artifacts/android/0.19.4-aex.19-vc37-c00eed9a10-release/Mihon-Fork-0.19.4-aex.19-vc37-release-universal.apk`；SHA-256 `244e157945060f1cc1c13faf828bf9cbadef16e0ac848d6a9a88ba0acef76f58`。R8、资源缩减、非debuggable、正式fork身份，v2/v3签名验证成功。源码为c00eed9a10加本轮dirty输入，manifest记录生产输入指纹`c24d95bb12794ff8c3a2f82ed0ac43e82dacb44a7ad6a534003ac8fc818b90e8`。本次未清数据、未卸载、未读私有DB、未修改debuggable。
- 真机ART冷启动显示“上次同步已取消 / 上次已确认1536条 / 上次历时291:54 / 整体剩余—”；可执行主按钮为“连接同步空间”，并解释设备尚未连接或已断开。原历史记录保留。

### 真机只读取证与生命周期

- 应用内真实诊断返回OK：原始exchangeEnabled=false、storedBindingDecode=OK、panelConnectionEnabled=false、hasActiveSpace=true；明确为持久停用，不是格式不兼容。谁或哪次动作停用没有历史轨迹，仍未知。
- activeRun=null，runSource=LATEST；latestRun=CANCELLED，phase=DOWNLOADING，stopReason=USER，attemptId=2，ownerPresent=false，confirmed/uploaded=1536，downloaded=0，nextRetryAt=0。USER是持久原因枚举，不能据此归因用户本人或某个按钮。
- createdAt=1790003130460、updatedAt=1790020645410、lastProgressAt=1790020630919，差值17514950ms，按分钟秒显示291:54。此为历史总历时，不能无分段记录就扣除等待或算作网络工作时间。
- opt-in24h会话后A/B/C的真实UTC采集时间为11:26:25.775、11:28:32.311、11:31:11.602；相对A为0 / 126.536 / 285.827秒，并非精确0/30/60。三次latest对象完全相同，activeRun=null、coordinatorRunning=false、尝试/确认/更新时间不变。此窗口不支持仍继续计时或继续执行；不能证明过去没有重试。
- 设置返回MAIN、关闭再开后的D：latest完全相同，真实OPEN→REFRESH_BEGIN→REFRESH_END完成，lastRefreshSource=OPEN。保存/打开文件的临时反馈没有重播。
- HOME→暖启动后的E：同一进程/历史记录，无活动运行。先核对无活动后对本目标包force-stop→冷启动；F的进程会话改变，同一run/space诊断别名和上一快照链仍可对应，latest保持一致、无活动运行，打开刷新再次完成。
- 冷启动F捕获PERIODIC协调检查短暂运行后退出；没有修改历史run/attempt/confirmed或创建活动运行。启动同步偏好=false、定期60分钟保持。将调度检查与业务同步区分，不声称进程重建期间完全没有调度活动。
- progressMatchesRun=true且hold=ACTIVE是原进度fallback重建的默认值；终态展示已优先冻结，不能用hold字段取代active查询/coordinator/owner证据。
- 真机200%字体（原font_scale=1.0，finally恢复1.0）主连接入口可见可点击；未改变wm尺寸/density。320dp/200%中英双主题证据来自真实Compose离屏布局，不能把平板当作320dp实体设备。

### 本机文件与连接恢复

- 正式Release“保存诊断JSON”反馈成功；“打开或分享本机文件”进入Android系统选择器，未选联系人或目标、未发送，Back取消。关闭重开后旧文件操作与成功反馈均未回放。
- 正常字体点击主“连接同步空间”真实进入既有SIGN_IN页面，可见“连接 GitHub”。继续恢复必须沿原授权/密码流程，不强制改exchange_enabled，不删除历史。后续新任务/最终平台测试和提交尚待完成。

历史原因证据不足时明确标为未知，不通过清除记录、强启用连接或改写时间伪造恢复。

## 集中验证进度

- macOS首次全双端focused命令在执行行为测试前因3个Android AAR离线缓存缺失中止。尝试按Windows已校验同字节补齐文件后，Gradle离线元数据仍无法解析；不把依赖错误当产品测试红，也不启动第二个在跑的Gradle。收窄为该平台实际生产链的共享JVM与Desktop原生focused测试，`sr-mac-native-focused`退出0，2分39秒，覆盖真实终态恢复、诊断store、Desktop面板/文件打开/TestMode接线。Windows真机正式ART另行验证了Android导出实际行为。
- 完整Android命令第一次因PowerShell拆分`-Pmihon.testBuildType=release`在配置阶段失败，没有执行测试；引用修正后的`sr-android-full-args`是本轮实际完整执行，PID/日志由协调器记录。
- 真实连接恢复已进入Firefox的GitHub授权。账号交互和原同步密码由用户在设备完成，未获取、输出或存储秘密；其间继续平台验证，不把等待当授权完成。

### 授权后真实业务推进

- 用户完成设备端GitHub授权后反馈“卡在准备同步空间”。19:51:43只读UI仍为准备；19:52:29已自动进入获取同步数据。当前观察证明页面随后推进，不把短暂准备直接定性死锁。没有重复授权、没有强改flag。
- SETUP/MERGING暂不显示设置入口。核对production Back仅取消授权/发现任务、不取消已有setup交换后，返回MAIN→设置→诊断。首次G采样标INCONSISTENT并清空组合事实，保留unknown；不能因latest=null判无run。第二次H采样OK：exchangeEnabled=true、同原space/generation；ACTIVE RUNNING/MERGING，owner=true、attempt=1，新run别名与F旧取消run不同，本次confirmed=0，downloaded=14240。采集脚本误用字段runAlias而非DTO.alias造成打印KeyError，不是产品故障；已用保存的安全DTO核验，不重复发起同步。
- 19:57:16真实UI推进到“正在核对GitHub已保存的数据”，本次已确认14496条，待上传4209项。继续跟踪到终态，不将已启动代替完成。

### 真实正常取消对照

- 19:59点击生产“暂停同步”，J诊断为PAUSED_USER/UPLOADING，owner=false、coordinator=false，confirmed=15520、uploaded=1280、downloaded=14240，继续入口可执行。只有确认执行器退出后才进入断开步骤。
- 20:02经设置→“断开GitHub”→现有明确保留书架/未上传变动的确认框→“确认”；自动化首次把同名对话框标题当按钮，点击前被可执行性检查拦截，未执行重复断开。随后核对真实确认按钮再执行。K诊断OK：exchangeEnabled=false、decode=OK，active=null，原同run成为CANCELLED/USER，owner=false、coordinator=false，confirmed=15520保持，nextRetry=0。真实MAIN为上次已取消、上次历时10:18、整体剩余—，连接入口可执行。
- 再次点击主连接入口→GitHub授权，按原断开语义重新授权由用户设备端完成；本轮不再次重复取消。之后保持连接完成业务验收。
- macOS独立平台focused结果XML为Desktop7、共享Compose真实终态恢复10、诊断store4，共21项，0失败/错误/跳过。先前无效SSH Python引号只造成汇总脚本SyntaxError，未执行或重复任何测试。
- 用户在全量Android启动后更新AGENTS：之后全量仅在全部实施工作完成的最终收口执行；已启动的原规则验证仍由原协调器完成，不以额度预先追加全量。当前未发现需追加生产修复的真机故障；Desktop全量与正式平台构建仍等待业务验收稳定。

- 用户随后明确授权代为操作已登录的平板浏览器，说明无需账号密码。此后原浏览器登录态/设备验证码仅在操作内瞬时使用；不将验证码、账号名或授权链接写入仓库、报告或输出。若实际出现秘密输入仍停止该步骤，不以已有网页登录态直接改同步偏好。

- `sr-android-full-args`最终退出0，23分33秒；明确按本次命令对应variant汇总JUnit XML共3468项，0失败/错误、8项既有平台/权限条件跳过。包括全部Android Release单元测试、domain/core/data/shared presentation JVM、data Debug存储和test-desktop客户端；不混入上轮app Debug或本轮仅focused的Desktop结果。根spotlessCheck通过。

- 已登录浏览器先按“Continue as”使用现有账号，再由应用当前验证码进入原GitHub设备授权。自动化一次整串输入在浏览器八格输入中未完整生效，页面明确“Please make sure you entered the user code correctly.”；这是ADB键盘操作问题，不归因应用。逐格输入后在内存核对8格与当前应用码完全一致，再Continue；页面出现Mihon授权按钮，按用户明确授权点击批准，返回应用等待实际接收结果。全程没有输入账号密码，不将码、账号或URL输出/落盘。

### 真实恢复成功与再次手动同步

- 浏览器授权被应用实际接收后，L诊断OK：同一space/generation已启用，新的ACTIVE run与K取消run不同，attempt=1、owner=true，本次confirmed=768且downloaded=0，继续上传之前尚未完成的变动。未清数据库、未借用上次15520条作为本次确认。
- 最终N为SUCCEEDED/COMPLETE，active=null、owner=false、coordinator=false、nextRetry=0，confirmed/uploaded=3185、downloaded=0，历时306736ms显示05:06，pendingTotal/importRemaining均0。主卡“上次同步已完成”，立即同步可执行。
- 再点击真实“立即同步”，UI立即切新任务、本次确认0、已用00:00；O取得与N不同的run别名，SUCCEEDED/COMPLETE、attempt=1、owner=false、active=null、coordinator=false、confirmed/uploaded/downloaded=0，历时2343ms。主卡再次恢复立即同步，显示上次历时00:02；空增量成功不能解释为旧确认被清空。
- 自开始取证至恢复完成未发现生命周期漏刷新、终态持续改写或取消后并行执行，不预先改调度/生命周期。历史291:54的分段原因仍证据不足。生产实现稳定，随后才启动本轮最终完整Desktop测试与正式构建；任务checkbox仍待测试/正式验收及聚合提交。

- P较O真实间隔145.620秒，latest成功记录完全相同、active=null、coordinator=false。先确认无活动，再对本目标包冷启动，Q为新进程会话、同run成功对象与同space连接已启用，OPEN刷新完成；主卡00:02及立即同步保持。startup=false、periodMinutes=60未修改。
- 诊断结束自动化首次使用了不正确的文案“结束本次诊断”，目标不存在而未点击任何控件；随后按真实资源“结束诊断会话”完成结束，结果以R快照/实际反馈核对，不能将脚本目标错误归因产品故障。

- R结束诊断反馈“私有缓存已清除”，重采样crossProcessComparable=false、sessionExpiresAt=null、连接仍启用、active=null、coordinator=false。最终设备保留连接与成功结果。
- 最终Desktop full-tests 5分46秒，唯一失败为既有parity角色证据行号过期，具体ID4的DomainModule注册方法因新增import从148移至149；核对同diff另有ID11通知器1087→1093、ID59下载过滤1130→1136。主代理仅修清单这三个当前位置，没有改变能力status/实施证据/冻结原始行号/合同断言，按完整解析逐对象核对其余语义值不变。首轮focused未覆盖该清单合同，复用原审查者做一次聚焦复审，主代理仅重跑该合同类与模块格式，其他已执行全量不重复。

- Desktop单次全量XML3239项，1个上述定位合同失败、0错误、3个既有条件跳过。受影响合同的focused `sr-parity-locators-fixed-command`39秒退出0、34项0失败/错误/跳过，全仓格式检查通过。该复验不改变产品/测试断言，独立聚焦复审PASS确认其他全量通过证据继续有效，不把初次全量写成无失败，也不重复全量。第一次focused附带了不存在的`:app-desktop:spotlessCheck`任务，在配置阶段失败未跑测试，改用实际根spotlessCheck后完成上述验证。
- 最终Win/Mac使用统一`build-desktop.sh build-only`，前置为本轮同生产diff的完整Desktop有效证据、Mac原生focused21项及唯一聚焦索引复审PASS。版本各从0.11.19.69分配为0.11.19.70，两个隔离工作树独立并行打包；Mac只部署到本轮专用artifacts，未覆盖用户日常应用。正式APK先于Desktop独立版本分配构建，manifest保留当时69的真实输入指纹；Desktop的AppVersion70不参与Android编译，不为该版本或文档hash重签APK，不能声称APK源码集合与最终聚合commit所有平台版本完全相同。

## 正式平台产物与真实运行

- Windows统一build-only退出0，Gradle打包1分39秒，版本0.11.19.70.c00eed9。脚本内生产扩展安装/版本验收通过，并正式发布到artifacts/windows；唯一Final unpacked EXE是`D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.70.c00eed9-unpacked/Mihon Desktop.exe`，已核对存在并从该地址运行。Windows ZIP SHA-256为`04f1173c6396ef848d369f61fc465ab071b1056ee35c09985440c5ef934d37ff`。
- macOS统一build-only退出0，35秒，版本0.11.19.70.c00eed9；专用APP为`/Users/altair/github/mihon-sync-terminal-20260930/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.70-c00eed9.app`，实际Contents/MacOS可执行文件存在。用户日常APP与配置未覆盖。
- 从上述正式EXE和APP实际可执行文件启动既有Test Mode，分别使用本轮新建、marker保护的独立空profile及随机本地端口，绕过代理。真实生产DI的Open→loaded MAIN→DIAGNOSTICS→capture返回OK；环境均为DESKTOP、实际版本70、releaseBuild=true，runSource=NONE、active/latest=null，符合空夹具事实；Close后诊断GET为204、无快照。未通过独立客户端或复制业务实现替代运行。
- 两个真实验收进程均经本实例HTTP shutdown正常退出0，无全局杀进程；未读取桌面屏幕像素。安全JSON保存在忽略过程目录，不含账号/仓库/作品/令牌/密码。Desktop诊断文件打开的本机guard由真实平台集成测试覆盖，Android实际系统文件选择器在真机验证；Test Mode不冒称执行了原生Desktop文件打开UI。

- 为提供本机可点击的macOS产物，将已验APP用系统ditto归档，保留资源与父目录，ZIP校验全部条目CRC通过；回传本机后按流式SHA-256核对为`cc84551723614d89244ae3373304ef054d5251049544a54294ee2d4debe3def3`，196576363字节。Mac旧Python不支持file_digest导致首次仅哈希步骤失败，已用流式hashlib完成校验，未重复打包/覆盖APP。

## 最终结论与交付边界

1. **入口BUG已修复**：持久停用连接与历史CANCELLED曾只剩禁用立即同步；现根据实际连接提供恢复入口，合法连接后新run可启动，旧取消记录和确认结果不会占用新任务。真机现有取消/再次连接/成功/再次手动同步均实际通过。
2. **291:54是冻结历史历时**：它来自原记录updatedAt-createdAt；两轮多分钟稳定观察、关闭/重开与冷启动均未续写。过去近5小时究竟包含多少等待、谁停用连接仍缺历史轨迹，不编造原因，不扣除未记录等待。
3. **当前没有挂起同步**：最终成功记录无active、owner或coordinator执行，nextRetry=0；本次诊断已结束，连接保留。最后一次空增量复测为0条/00:02；它表示本次无变动，不是把已确认3185或历史1536清零。
4. **未发现生命周期刷新缺陷**：断开和重新连接后的冷启动、面板重开与前后台均消费当前状态，OPEN刷新有结束事件；PERIODIC检查与业务run区分。未给每次resume追加同步、未改取消/调度/协议/数据库schema。

诊断是一次性本地事实，不提供安装前完整历史归因。scheduler字段不可得时明确UNAVAILABLE，ADB与真实coordinator只补当前旁证；hold fallback不是活动运行证据。320dp/200%及中英双主题为真实Compose离屏证据，实体平板另测200%，不冒称窄320dp真机。系统读屏与真实Tab人工验收不是本轮新增闭环范围，原进度展示计划的相关待验状态保留；本轮恢复主操作焦点、布局和真实wiring由原生测试覆盖。

| 产物 | 正式交付地址 | 证据 |
|---|---|---|
| 已升级真机Android Release vc37 | [Mihon Fork APK](D:/Codex/worktrees/dc4c/mihon/app/artifacts/android/0.19.4-aex.19-vc37-c00eed9a10-release/Mihon-Fork-0.19.4-aex.19-vc37-release-universal.apk) | 原证书、verify/安装身份及真实ART业务 |
| Windows正式未打包应用 | [Mihon Desktop.exe](<D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/windows/Mihon-Desktop-0.11.19.70.c00eed9-unpacked/Mihon Desktop.exe>) | 日志Final unpacked EXE、脚本生产验收及本产物Test Mode |
| macOS已验APP归档 | [Mihon Desktop macOS ZIP](D:/Codex/worktrees/dc4c/mihon/app-desktop/artifacts/macos/Mihon-Desktop-0.11.19.70-c00eed9.zip) | mbp-lan原APP实际运行退出0、本机同字节归档 |

聚合提交仅包含本轮生产/测试/资源、必要维护索引、Android/Desktop版本及设计/roadmap/本证据；不混入主规划树及其他会话修改。roadmap checkoff随此同一功能批次提交生效，不为证据状态另建提交。
