# Android 平板实测：唤醒、采集与熄屏

目的：复用已经成功的真机操作，减少人工解锁和重复定位工具；只在需要前台输入或画面证据时亮屏，避免静态画面长时间停留。适用于已获授权的ADB平板测试，不替代[Android构建指南](../agent-guides/android-build.md)与[验证范围](../agent-guides/verification.md)。

## 已验证环境与依据

2026-11-03，PCE-W30／API31，最近无线ADB地址`192.168.50.51:5555`。地址、在线状态和设备身份每轮重新确认。当前平板无密码锁屏，真实字段为`secure=false`；不能把这个历史值套到其它设备或后续配置。

来源为[阅读器P3证据](../evidence/reader-startup-p3-2026-11-03.md)。成功的无密码唤醒原件：`.test-tmp/reader-open-p3-20261103/black-return-control-compare/wake-summary.json`及同目录`wake.json`、`state-after-wake.json`、`dismiss-keyguard.json`、`state-after-normal-dismiss.json`。用户明确要求无需前台时熄屏，并授权直接唤醒无密码锁屏。

### 无线不可达后改用USB

2026-11-04的P4中，旧无线地址一次连接超时、mDNS为空；这些信息只证明旧入口不可达，不能据此判断设备已锁定或关机。用户接入USB后，`adb devices -l`发现authorized设备`5JB0224111000992`；再次读回PCE-W30、Android12/API31、物理2880×1920/override1920×2880及secure=false，确认是同一目标。原件为`.test-tmp/reader-open-p4-20261104/usb-device-preflight.json`，结果见[P4记录](../evidence/reader-startup-p4-2026-11-04.md)。serial仅是本次记录，后续必须重新枚举，不照搬到另一设备。

所有安装、shell、pull和采集子进程都显式传`-s <已确认USB serial>`，避免默认选到无线别名或其它设备；只适配采集脚本的transport，不改测量时序。USB authorized后已成功复用下述WAKEUP→正常dismiss→状态读回流程，控制A1采集后也已读回Asleep，无须人工解锁。若显示unauthorized，则由用户完成设备正常授权，不能绕过。

USB供电是新的测量条件。控制和候选整组保持同一连接方式，USB组与旧无线组分别保存；不要直接把两组差值解释为产品收益。电池/温度信息在计时窗口外记录，缺少某次前置记录就明确缺失，不补造或改变充电、省电设置。

本轮复用采集脚本已在正常完成与finally收尾中执行：正常离开Reader并保全导出→仅force-stop隔离包→KEYCODE_SLEEP→dumpsys读回Asleep，分别保存各批`final-power.txt`及最终`final-device-cleanup.json`。异常中无法完成导出时保留缺口，不把finally强停当作完整采样。离线检查设置也应看实际控件：P4的XML父/子`checkable=false`不能证明开关OFF；原设置截图空复选框才是本次视觉依据。拼图出现歧义时回看单张原图和未经重采样的裁图，不重新操作设备来覆盖旧状态。hint控件没有保存就明确未复核。

## 正常唤醒与无密码锁屏

`showing=true`只说明锁屏正在显示，不等于需要密码。先分别核对电源状态、锁屏显示状态和`secure`字段。安装、编译、日志读取及离线分析通常不需要解锁；测量正常点击到漫画可见则必须在已唤醒、锁屏不遮挡、目标应用有焦点的条件下进行。

本机已成功的顺序（PowerShell；实际serial须当次确认）：

```powershell
$adb = 'D:/Android/Sdk/platform-tools/adb.exe'
$serial = '192.168.50.51:5555'
& $adb -s $serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $serial shell dumpsys power
& $adb -s $serial shell dumpsys window policy
# 本机WAKEUP后仍showing=true、secure=false；以下正常解除命令实测exit0。
& $adb -s $serial shell wm dismiss-keyguard
& $adb -s $serial shell dumpsys window policy
& $adb -s $serial shell dumpsys window windows
```

成功验收是实际读回`mWakefulness=Awake`、`showing=false`、`secure=false`并确认焦点；命令exit0单独不足。实测解除后焦点返回launcher，随后才启动隔离应用并检查其焦点。不要用可能反向切换状态的POWER按键替代明确的WAKEUP/SLEEP。

解除命令的状态会异步收敛：本轮另一次实测exit0后立即读取仍showing=true，约0.4秒后只读复核已变为false，期间没有重发输入。因此先做短时间、有界的只读复核，再判失败；不因紧接着的一次旧状态就反复发送解锁命令或要求用户操作。

如果已有认证锁、字段无法确认、系统不支持正常解除，或一次正常解除后仍遮挡，保留具体状态并请求用户处理；不猜密码、不改安全/超时设置、不绕过认证，也不循环发送手势。用户接管设备时停止输入。

## 亮屏范围与收尾

- 构建、长分析、等待其它代理或处理环境问题前，先保存现场所需证据，停止本任务前台输入，然后熄屏；无需让用户为了后台工作保持亮屏。
- 将已准备好的前台采样集中执行；每个场景开始前核设备、解锁、焦点及目标页，步骤间检查实际页面结果，不能用批量盲发替代观察。双击等连续手势的内部事件保持正常时序，不在事件之间插入慢速状态查询。
- 阅读器探针先正常离开阅读器触发onStop，等待完整JSON，再停止本任务的隔离包。强停后不能补造丢失的导出。
- 关闭探针，清理本任务持有的录制/trace/logcat进程，保留用户数据和正式安装。最后执行下列明确熄屏命令并读回Asleep；失败或取消也遵循这个收尾。

2026-11-04持续优化R1中，SLEEP后立即读到`mWakefulness=Asleep`但`mWakefulnessChanging=true`，包装断言未通过；随后只读复核变为`Asleep/false`，没有重发唤醒或输入。后续收尾可对这一过渡状态做有界只读等待，确认收敛再报告完成，不能把即时断言失败直接称为产品故障。原件为`.test-tmp/reader-open-goal-20261104/r1/final-device-cleanup.json`及`final-power-settled.json`，结果见[持续优化记录](../evidence/reader-startup-autonomous-2026-11-04.md)。

```powershell
& $adb -s $serial shell input keyevent KEYCODE_SLEEP
& $adb -s $serial shell dumpsys power
```

## 安装与编译条件的实测经验

本轮保留数据更新使用已核包名、证书及SHA的隔离APK，目标仅`app.mihon.desktop.fork.benchmark`。正式fork、官方`app.mihon`和其它探针安装不覆盖，不清数据、不重新运行prep改写既有SAF路径。控制与候选都保留R8、资源压缩和non-debuggable；具体构建仍走项目协调器。

`cmd package compile -m speed -f <目标包>`的Success不是状态核验。PCE-W30的`dumpsys package dexopt`曾exit0但为空；本机成功采用设备已有oatdump读取实际目标`oat/arm64/base.odex`头。包路径来自当次`pm path`，不使用旧安装目录。命令形状与原始输出见P3各`*-compile/oat-header-after.json`。

仅以头部独立行`compiler-filter = ...`、`compilation-reason = ...`记录产物状态，不能把后面`dex2oat-cmdline`的请求参数冒充实际header。例如本次安装头是`install/verify`，虽然旧请求参数可包含`speed-profile`；成功强制编译头是`cmdline/speed`。

本机实际成功的头部读取命令如下；PowerShell单引号保留设备shell的变量语法，设备当次`pm path`决定安装目录：

```powershell
& $adb -s $serial shell 'p=$(pm path app.mihon.desktop.fork.benchmark | cut -d: -f2); /apex/com.android.art/bin/oatdump --header-only --oat-file="${p%/*}/oat/arm64/base.odex"'
```

此形状只验证过本机arm64、单base APK布局；路径、ABI或oatdump不存在时保留失败，不拿旧安装的输出充当当前状态。

本机出现过厂商`HwBackgroundDexOptServiceEx`以`Foreground dex2oat confict`终止当前编译进程。熄屏下既有成功也有失败，**熄屏不是保证成功的方法**；禁止按此经验关闭系统服务或无限重试。若控制与候选实际编译状态不同，可使用同样正常安装、同样实际状态的新对照，采样前后读回确认；旧speed组与新verify组分开，不能把时间差称为产品净收益或编译问题已修复。

重装后曾短暂出现GrantPermissionsActivity使Main焦点守卫失败。该次未进入Reader，不计为有效样本；保留前置失败记录。重新核实没有权限弹窗、Main焦点正常后才能使用新label采集，不盲目授予权限。该历史组采用相同Main-only前置，不预热Reader。

2026-11-05的O系列采用另一种明确标注的边际对照：每个A/B安装块均保留数据重装、立即读回实际OAT，再按模式进行一次不录制的Reader预热、强停、新进程首次及同进程再次采集。它测的是“安装后已预热的新进程首次”，不能冒充升级首次、冷磁盘或原正式基准。A1/B1实际读回为`verify/install`；重装前曾因odex的0640权限读不出，必须保留UNKNOWN，不拿装后状态追溯填补。条件文件按安装块绑定，样本须核相同arm、APK SHA和执行时序；条件label不必等于每次capture label。原始probe/trace/MKV不得为修正关联错误而改写。

## 已找到并实际使用的本机工具

先查项目记录、实际路径、用户PATH或软件管理器入口；当前进程PATH缺失不证明工具未安装，禁止跨盘递归搜索。

| 工具 | 本轮可用入口 | 依据与边界 |
| --- | --- | --- |
| ADB | `D:/Android/Sdk/platform-tools/adb.exe` | 核指定serial，不操作其它实例 |
| Python | `C:/Users/feeli/AppData/Local/Programs/Python/Python314/python.exe` | UTF-8；后台控制台使用CREATE_NO_WINDOW，保存日志/退出码 |
| FFmpeg／ffprobe | `C:/Users/feeli/AppData/Local/Microsoft/WinGet/Links/ffmpeg.exe`及`ffprobe.exe` | Winget独立Gyan FFmpeg 8.1.1；用户PATH含Links但本次进程未继承，已核符号链接实际目标和版本 |
| Perfetto trace processor | `.test-tmp/upstream-original-trace-20261102/tools/trace_processor_shell.exe` | 本轮成功复核P2/P3原trace；它是本机保留工具，不保证新机器存在，缺失先报告具体路径 |

提取视频保留原生帧号和PTS，不重采样为固定fps。原表四边界定义、探针语义及跨钟限制见[性能基准](../evidence/reader-startup-performance-baseline-2026-11-02.md)和[探针维护说明](../architecture/reader-open-latency-probe.md)。源帧分类与区间算术分开验证；主机时间、归零MKV PTS、probe MONOTONIC和trace BOOTTIME不能直接混算，使用同一trace的clock snapshot。实际输入起点必须注明是原表较早deliverInputEvent还是应用MAIN_TOUCH_DOWN，不能同名混用。

近黑四图必须是“最后可辨详情、首近黑、末近黑、首正确漫画”。P3曾误选触摸涟漪帧，虽然PTS算术正确，仍把正常详情页停留算成黑屏；独立看图后撤回并从同一视频连续帧更正。成功复核方式是同时看边界附近连续帧，并与控制组暗淡程度对照，再核原生PTS；错误选择保留为不可报告记录，不重录择优。触摸涟漪只能单独描述输入响应。系统开始启动的旧表端点是异步`StartActivity:<包名>/<ReaderActivity>`，也不能用`startActivityInner`替换。

R2已验证真正双图的设置与恢复：隔离作品Volume04进入阅读器，中央点击显菜单→“设置”齿轮→“阅读模式”→“双页式（从右到左）”，在其下实际核对“自动跨页匹配”复选框。该卷source3/4为左侧World Map、右侧卷标题；封面page0是单张宽图，不能当双图样本。首次翻入该单元可显示锚点4/187，正常离开后以两张已绘页的末页保存index4，详情为“第5页”；新进程恢复显示5/187且仍是同双图。配置probe `targetPage:4`只选观测对象，不代替UI导航。每组设置后结束准备进程，正式首次样本重新启动，避免误把准备进程的重进当首次。

O系列另确认：设置后的红青导航提示不能证明启动提示开关为ON。修改阅读模式/导航本身可显示它，`ReaderNavigationOverlayView`由触摸触发淡出，不会自然定时消失。正确核验是在新进程打开Reader后、再次进入设置之前看实际原图；若仍有提示，再经正常全局“点按区域提示”设置处理。自动匹配复选框以实际截图为准，不能只相信UI层级中的`checked=false`。准备脚本收尾会熄屏，正式采集前仍须正常唤醒并核焦点；漏唤醒的输入前失败保留为EXCLUDED，不重录已完成的有效请求。详见[O系列实测记录](../evidence/reader-open-o-series-2026-11-04.md)。

R3准备中，Back关闭设置后立即点中央曾使阅读器工具栏仍显示；此时XML没有`5/187`，导航器出现两个`187`，不代表跳到第187页。成功恢复是先读回实际控件/原图，只有确认Reader菜单仍在时，发送项目已支持的`KEYCODE_MENU`收起，再截图、核实际源图与页码，正常退出并重建正式采样进程。使用新准备label保留失败原件，不能放宽页码断言或盲目重复点击；入口为`.test-tmp/reader-open-r3-20261104/prepare_mode.py`及B1 ON恢复准备记录。

同轮批量展示三张PNG时，工具曾把第三张呈现为黑图；单独重开同一原PNG明确显示正确双图。必须先独立重开原件核实，像素统计只作旁证，不能直接宣布设备黑屏、丢弃样本或重采择优。该次误报曾暂停宿主编排；原采集子进程和after条件均正常exit0，恢复时核精确进程记录，从未完成步骤继续，不重采已完成样本。采样原件、暂停历史与更正均保留。

双图视频须分别记录首部分正确漫画与首完整单元。已显示一张图后不能继续算近黑，配置目标也不保证是先出现的一侧。使用带原帧号／PTS的有界连续缩略图独立审查，逐个图格确认两侧，不能把相邻图格拼成同一帧。R2曾将OFF首次完整帧误认作109，原帧显示109仍只有右标题、110才补齐；单页候选端点也经连续图前移。未采用候选及原因保留在`analysis/video-selections.json`，原件入口见[持续记录](../evidence/reader-startup-autonomous-2026-11-04.md)。

R2正常ON往返还发现控制／候选均有5→6→4、回翻后标题左／蓝漫画右、详情保存第4页的错单元。不能仅因4/5都是可能锚点就判正常；必须核实际左右内容。截图应先保存再判页码，首次失败曾因先assert而丢图；固定sleep1秒加uiautomator也不证明动画停稳。此次最小异常核查用A/B各一次连续视频、每步有界轮询直到相邻两张间隔0.7秒的中心像素相同，再独立看内容，证实异常在停稳后仍存在。像素相同只证明所采区间稳定，不是原生IDLE或配对正确性证明。两包异常与未定位根因见同一结果；失败时保留XML、缺图和label，不改页码断言循环试到成功。

Gradle只用精确完整类名和方法选择器。P3曾因类名前置通配符配合forkEvery=1启动大量空测试JVM；按协调器停止后使用完整类名正常完成，不能把这个环境/筛选失败当作真实行为RED。逐任务只复验直接受影响范围，不因流程收尾运行整模块。

## 已验证的临时内部状态查询

P3曾在隔离benchmark构建中加入按需只读snapshot provider，成功读到当前holder、native解码状态、回调有效性、内容身份、呈现票据和可见区域；它用于区分“未加载”与“已就绪但未显示”。只接受shell或本UID，固定本测试章节及有焦点Reader，最多读取3个holder；UI线程仅读既有字段，文件写入在调用线程，不调用会改变呈现状态的frame/prepare/capture。入口已从最终源码移除，不能假定普通APK支持以下调用。

已成功的调用形状是`adb shell content call --uri content://app.mihon.desktop.fork.benchmark.reader-state --method snapshot --arg <唯一安全label>`，结果写入隔离应用外部目录`files/reader-open-real/snapshots/<label>.json`。必须核JSON的status、目标页及身份，而非只看content命令exit0；入口缺失时曾出现命令exit0但提示Could not find provider。输出禁止覆盖旧label。

完整源件及manifest/keep/build输入保存在`.test-tmp/reader-open-p3-20261103/runtime-predicates/final-source-archive/`，成功调用脚本为同根`check_flip_snapshot.py`；后续若确需复用，先审查并重建临时诊断包，不直接安装旧仪器覆盖当前候选。该构建的耗时不能混入普通探针性能对照。快照只证明采样时状态，不证明此前或此后实际执行过onDraw；locked/drawn也可能是历史记录，不能冒充当前屏幕像素。

本轮连续两次tap没有改变scale/center，未证明系统识别了双击。因此不把“命令已发送”写成“已强制重绘”；交互干预必须从实际缩放、画面或其它可观察状态确认生效。

### R4H：正常双击输入的成功时序

2026-11-04，R4H在同PCE-W30/USB、1920×2880当前视口中再次发现：串行启动两次`input tap`只显示菜单，fit/zoom画面几何未变；旧`experience-a-single/`及`experience-a-dual-off/`保留为输入无效尝试，不能计作缩放通过或产品故障。每次`input`的进程启动耗时会使主机安排的间隔与实际触摸间隔不同，不能用两条命令成功推断双击。

本机成功方式是在同一次正常设备shell中，让两个input进程错开120ms启动并等待结束：

```powershell
& $adb -s $serial shell 'input tap 650 1440 & sleep 0.12; input tap 650 1440; wait'
```

这是当前双页地图位置的示例坐标，不是其他尺寸/模式的通用位置；操作前确认正确Reader和目标视口。两次触摸之间不插入uiautomator/状态查询。成功依据是`.test-tmp/reader-open-r4h-20261104/gesture-proof-a-dual-off/{fit,zoom,pan,reset}.png`：实际文字与页面放大、平移后图形位置改变、再次双击恢复完整地图左/标题右，正常退出后熄屏。主代理另独立查看zoom原图确认实际放大。仍须检查实际画面/状态是否收敛，不把此方法当作所有设备必然成功的保证；无效时保留原件、停止盲跑同种手势，不以降低断言或无限重试制造通过。
