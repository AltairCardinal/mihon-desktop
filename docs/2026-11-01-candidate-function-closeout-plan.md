# 历史候选功能闭合与双端验收包

2026-11-01；基线 5e04aa34be；用户本轮明确要求逐项闭合并构建包，其中 Android 白缝由模拟器验收。此授权覆盖五项产品待办 F01/F02/F03/F04/F09 及 F11 验证，不恢复已淘汰产品或 R10 性能研究。

## 执行和归属

- 主控：integration 工作树；F09 双页即时空帧、F11 开关连续性、独立审查、整合/提交与最终构建。
- Android 实施者：candidate-android-closeout-20261101；F01 白缝、F02 水波、F04 设置；仅 Android reader/设置/对应资源测试，旧版本号不回迁。
- Feedback 实施者：candidate-feedback-closeout-20261101；F03 共享/双端反馈与服务必要闭包；只接入已确认缺失的四处 reader 诊断调用，保留原图请求/加载逻辑，不整包合旧分支。与 Android 实施者的 pager/settings 文件归属无重叠。
- 两实施者不是唯一写者；尊重他人工作，不回滚或覆盖。共享资源冲突由主控净差异合并。
- 本任务的重型 Gradle/模拟器与构建串行：实施者先提交精确命令和成本请求执行窗口，主控统一发放。窗口外进行代码/夹具/文档/轻量 Node 测试。禁止裸跑、并发堆积、全局清进程。
- 独立审查一轮和必要修复复审一轮。产品行为先真实失败再最小修复，focused/wiring 与 scoped 格式闭环，不运行全模块/全仓。

## 任务与验收

- [x] A F01：当前源码接线下重现/确认原 tile seam 缺陷；保留共享 fit、贴脊、镜像、奇数像素、resize 与缩放，旧补丁按需适配。模拟器真实绘制和像素证据覆盖典型手机/平板、原回归尺寸边界；不以几何单测冒充像素验收。用户无需再提供真机白缝验收。
- [x] B F02：核清最新指令“保留上一版+方案A可选”，核原方案正文后实现当前偏好、设置入口、首次截图/更新时序与滑动直接翻页；覆盖单/双页、默认/备选、取消、无尺寸恢复，不以删测试绕过缺失接口。技术/UI接线按本轮定向测试验证；汉王实际显示体验由用户验收，不外推设备刷新驱动保证。
- [x] C F04：Android默认/自动说明对齐边距和主题，双页匹配选项移到模式下方，不出现多余单页标题；单页/条漫条件正确。真实设置接线和 scoped 格式通过，APK供用户验收。
- [x] D F03：分类代标题、类别专属模板、设备默认勾选、按类别诊断、按需临时记录、截图 JPEG 转换进度/压缩/预览/移除；按既定原型，保留当前主干反馈能力。共享协议/服务迁移/附件身份与访问控制独立审查，双端入口和失败路径 focused 验证。只使用本轮虚构夹具；没有新服务部署/真实上传授权时用隔离服务端完成验证，给出远端服务未升级的明确交付边界，不冒称线上闭合。
- [x] E F09：保留 c54 单页修复，先复现 dual=true 八参数即时空帧，区分真实绘制/当前导航 intent/夹具时序，修实际根因，原像素断言不放宽；同方法所有相关参数和快速/中途关动画回归通过。
- [x] F F11：真实 mounted 双页切自动匹配，观察合法页/像素、进度、位置，符合则记录不用旧补丁，失败则最小修复。不能以内部display-unit改变本身判故障，不能锁死应变化的合法配对。
- [x] G：整合已验证提交到本地main，保留用户 AppVersion/附件；统一检查资源冲突及相关生产接线，复用未受影响测试。Windows main-preview、Android candidate（连续原签名/新版号）从固定源码构建，产物清单核对。
- [x] H：模拟器对最终 Android 候选做适用冷启动与真实 UI/白缝验收；Windows 隔离候选运行检查与反馈UI验证；实际路径与短清单交给用户，明确待用户体验项。

## 退出与证据

每项记录真实运行key、源码版本、执行/失败/跳过数量、复用边界。完成一个功能节点向用户报告进展与剩余；不按微小测试向用户停工。资源问题先检查本任务进程，环境未变不反复重试。仅真实外部阻塞才报告，独立工作继续。最终不宣称用户已经验收、不发布网站或推送远端；若服务部署成为F03实际必要门槛，先做完可审查实现和隔离验证再明确提出最小部署决定。

唯一执行计划为本文，完成报告在本文追加，不另建状态系统。实际开始：三棵隔离工作树已建立，主目录用户改动受保护。当前节点：用户已验收通过，正在完成限定范围的主干推送；以下过程证据按时间保留，最终结论以文末“最终交付与边界”为准。

## 过程证据（持续更新）

- 接手主干AppVersion用户差异SHA256仍为555d42abf87335aa333182df5118c1ad9cdb43ebb93da8b5eed2e182055e6ee5；未暂存/覆盖。
- F01/F04：Android RED3实际6项2通过4失败（两个1283×1000原生瓦片白线、两个设置布局）；GREEN实际16项全部通过，0错误/跳过，12几何/瓦片/zoom+4设置。来源提交f1530393d1/6a4c06309b/e5eced9598，整合提交3407b98f49/03176f572c/d2ffc4c3b9。F01 PixelCopy模拟器验证尚未运行。
- F09：closeout-desktop-f09-red实际16项8单页通过/8双页空帧失败。observe实际18项10失败；8空帧同现，观察到所有目标presentation holder已Ready，排除未解码。新F11测试另外2失败是对内部visiblePageIds缓存必须不清空的过强要求；实际连续像素已保持。测试改为读取真正mounted draw receipts与像素行、逻辑位置及进度，而不锁内部结算缓存。原8空帧断言未放宽。候选是使exposed可观察，以在离屏空绘制转可见时失效；尚待绿验证，不宣称因果已证。
- F03：Node58项、同版本真实workerd/D1四迁移/虚构JPEG与日志/幂等及BLOB读取验证通过。用户已授权客户端验证后更新现有反馈服务，但只读核验证明线上nativeV2/screenshots已启用、0001–0004已迁移；不重复迁移。记录旧版本3d0834d2-90a7-40dd-b202-141bd40744c8，最终是否重部署以固定候选核对；保留secrets/现有vars、不上传真实反馈、不部署网站。客户端core13+domain35已通过，data/Desktop/Android仍在验证。
- Android签名预检通过（原证书），尚未分配新正式候选版本或构建APK。
- 已建立本任务独立AVD mihon-candidate-closeout-20261101（API36 x86_64，2200×1400，2GiB，软件GPU），未启动、不触碰用户设备。AVD创建器对其他SDK的devices.xml报错但目标ini/config与实际system/ramdisk均核实存在，不能只用退出码判启动成功。
- 同机其他会话独立Gradle在运行，本任务不操作其进程；这里的串行约束只控制本任务队列。已发现旧late Spotless extension target不能可靠窄化；最终改用实际实现task.setTarget并断言完整白名单，不把原格式结果称只检查六文件，不提交无关排版。
- F03 Desktop GREEN实际41/41通过、0错误/跳过，包含持久停止失败后保留已收到回执和可见重试。共享诊断另补 disabled probe 契约：临时私密日志不能意外启用禁用的外部probe/clock，正在最小红绿验证。Android客户端仍待验证。
- F09候选已由非实施者交叉审查，无票据/取消安全阻塞发现，但绘制因果仍须像素GREEN。F11夹具进一步为目标两页分配独有GREEN/CYAN像素，其他页RED，避免相同颜色掩盖错误换页；draw receipt仅作累计挂载证据，不宣称逐帧新回执。
- 原F01 NativeTest依赖的旧扩展/捕获响应不在当前已知保留目录，正在改用本任务虚构本地下载章节；尚未完成该改造或原生验收，不声称旧夹具可复现。
- F03 disabled-probe RED实际1项因意外调用外部probe失败；加最小禁用门禁后ReaderIo契约3/3通过，0错误/跳过。既有Desktop41项组合证据有效，不重复运行整个Desktop范围。
- F02 RED实际16项10通过/6预期失败（默认视觉、同索引旧截图、关闭叠层、无尺寸回收、全局设置条件及真实dual接线）。10文件严格格式通过，正复验相同16项；原断言未放宽。
- F01原生夹具已改为LocalSource虚构四页PNG，通过真实存储绑定、仓库种子、ReaderActivity和PixelCopy，无旧扩展/网络响应依赖。仍待编译、交叉审查和实际模拟器运行；不改变生产代码或R8规则。
- F02后续复验组合已15/16通过，剩余dual夹具漏复用既有Windows原图PNG解码器边界；已确认实际缺失JNI，而非以替代采样/发布绕过产品断言。默认、取消、无尺寸、单页wiring及真实Compose设置持久化已绿；待补最后一项和格式后交付。
- F09 green首次止于私有委托属性setter与既有setExposed签名冲突，已改字段名；green2编译成功，但本任务自选资源guard在总private commit约4100.79MiB触及4GiB软阈值提前终止。当时系统仍余6668MiB，单进程峰值2188.64MiB。旧XML时间7:19，不能冒充本轮结果；仅日志已见原8空帧仍失败及2项stable失败，后者尚未核基线。故障原样归档desktop-green2-resource-fault.json；下轮按实测改job7680/process5120MiB、系统保留2048MiB/commit4096MiB，不重复等待不必要的硬内存门槛。
- F09后续开发交原Android实施者在完成F02后接管integration树Desktop reader，主控暂停修改这些文件，负责审查和native/交付。重点核ZoomablePageBox坐标门禁在读取曝光状态前短路的真实绘制失效；不修改配对许可或放宽原像素断言。F11随之复验。
- 正式Android候选分配前已按35个可访问登记工作树的元数据及各自浅层artifact.json核对，当前最大versionCode126/versionName0.19.4-aex.108；尚未分配127，最终构建前再查主干和竞争保留。
- F02来源提交1dc0f483c2完成，组合16项均通过、strict格式10文件通过；独立复审另发现同索引重组可能覆盖预先准备的水波方向，正在补真实RTL/LTR按键向量红绿，不以原16项通过掩盖该区分性缺口。
- F03 Android共享core17/domain36/data6全部通过。首app编译因1280m堆GC thrashing由Gradle主动停止，已保留日志；按实测约12GiB余量调本次调用至2560m/metaspace512m，无全局配置修改。随后app production编译通过，30项focused实际29通过/1旧About夹具缺FeedbackDraftMemory绑定失败；production AppModule已注册，原copy/close/不发送断言保留，正在最小补验。
- F09 B仅改变ZoomablePageBox既有坐标容器为neverEqualPolicy的Snapshot状态；坐标读取仍只在draw门禁，更新在原layout回调，不加每帧任务或放宽任何票据许可。A候选已撤回，仍待完整行为结果，不预先确认修复。
- 主干期间推进至dcea04d9aa，实际相对5e04净差异仅8份文档（含另案nonblocking设计、删除旧清理清单），无产品差异；已整合为6ee891cb92，尊重另一任务文档变更，不恢复其已删除账本。AppVersion用户差异指纹再次核实未变。
- F02额外真实6方向向量全通过，未证实静态同索引方向风险，故不改生产逻辑、不扩大追查；新增覆盖来源2ca60c89c1。F02来源1dc0f483c2与该覆盖已整合为68e5e22107/701c100daa。
- F03来源55119b06ba已整合为29ffe87e8c：JVM core13/domain36/data6/Desktop41、Android core17/domain36/data6/app30组合、Node58及真实workerd/D1隔离验证均通过；严格格式76文件通过。中英文合并后XML/键唯一性再次通过。
- 按用户授权已更新现有Worker为255fc2a9-ee46-48de-9c24-072dae4b7c63，活动版本查询核实。keep-vars保留配置，数据库迁移未重复执行，8项既有assets无更新上传；无反馈上传/网站部署。首次urllib GET遇403，未据此归因服务；同代理一次curl GET实际200、accepting/nativeV2/screenshots均true且限额一致。日志/版本/config均存本任务ignored证据目录，回退点仍3d0834d2-90a7-40dd-b202-141bd40744c8。
- F09 B16a完整XML为16项/7失败/0错误跳过，原空帧仍存在；不把唯一多绿一项当确定收益。当前在真实绘制门禁增加可撤区分性观测，另一现有代理仅只读查许可侧拒绝条件，两者不重复扫描、不并发Gradle。

## 已验证源码与交付阶段

- F09/F11已提交0d026770ef83b9fec840882d8744d5e0822a8558。原16独立通过；PageTurn50全部通过，含独色两向匹配连续性；相邻7项3通过/4失败，四项在29ffe87e8c原production字节下同样全部失败，错误一致，不是本次新增回归。四项为zoomed resize、partner retry、optional-header ready、forward drag；保留原断言，不把57称全绿。strict3格式通过，临时probe/A/B已撤。
- 正式包前再次查36个可访问登记工作树，发现另一隔离任务已保留127/0.19.4-aex.109；本轮改为保留128/0.19.4-aex.110，避免冲突。原证书及应用身份不变。首次预分配检查因该竞争保留安全停止，未写入127。
- 新增NativeTest使用虚构本地PNG、真实DI SqlDriver及普通Android组件Intent，避开不可靠Release仓库/Companion ABI；导航教学覆盖关闭后原值恢复。单文件严格格式已通过，原生编译/运行仍待本阶段，不能以格式或JVM结果冒充模拟器通过。
- 当前交付剩余：固定本地main，签名Android candidate128、原生APK ABI/真实PixelCopy白缝，Windows main-preview固定快照与独立headless runtime；最后核对实际产物/配置/进程并更新本文。不推送Git远端，不正式发布、不改公共能力说明。用户物理汉王体验及Windows/Android功能体验留在验收清单。

## 最终交付与边界（2026-11-01）

本批六项保留功能的实现、审查、定向验证和本地提交已经完成；复选框代表工程交付，不代表用户已接受界面、物理设备效果或正式发布。历史分支/stash/其他工作树仍保留，本轮没有清理或整包合入历史研究。本节覆盖前述过程中的临时假设和“尚待”状态。

| 条目 | 本批处理结果 | 验证与剩余边界 |
|---|---|---|
| F01 Android 双页白缝 | 已修复共享缩放/贴脊和奇数尺寸舍入；原图瓦片不再在测试尺寸露白 | 原真实红测后16项组合通过；最终133包真实SSIV/PixelCopy原生1测试、12尺寸×5列逐像素全部通过，0失败/错误/忽略。用户无需再验白缝。 |
| F02 水波 | 默认保留旧8×40ms阴影线，并可选方案A；单/双页接线、偏好、取消与恢复完整 | 16项组合通过，额外真实6方向向量通过；不是一次22项全套重跑。汉王厂商刷新/实际观感仍由用户体验。 |
| F03 反馈 | 分类模板、设备摘要、分类临时诊断、JPEG截图进度/预览/移除、隐私同意与服务配套 | JVM core13/domain36/data6/Desktop41；Android core17/domain36/data6/app30为有效组合证据；Node58、真实workerd/D1、双端真实JPEG解析及严格格式通过。隔离夹具不冒充真实用户反馈。 |
| F04 阅读设置 | 默认/自动说明边距与主题、双页匹配位置、多余单页标题及模式条件已整理 | 原4项设置验证；与另一任务更强设置测试合并后，最终4/4、0失败/错误/跳过，严格单文件格式通过。 |
| F09 Desktop 即时空帧 | 离屏绘制层进入可视区时使实际绘制失效并重新录制，保留原许可/绘制回执门禁 | 原16参数通过；主干后续缩放边界整合后，真实ReaderPageTurnPresentationTest最终50/50、0失败/错误/跳过，未复用旧XML冒充本次运行。 |
| F11 匹配开关连续性 | 实际像素/位置已满足，不恢复旧历史补丁 | 真实双向开关用例包含于上述50项，保留首次正确槽位、独色像素行与逻辑位置要求，不锁内部缓存表示。 |

### 可安装/运行的实际产物

- 最终 Android：[0.19.4-aex.115 / 133 APK](D:/Codex/worktrees/candidate-integration-closeout-20261101/app/artifacts/android/0.19.4-aex.115-vc133-8b16f1d61c-release/Mihon-Fork-0.19.4-aex.115-vc133-release-universal.apk)。源码 `8b16f1d61ca8d2f16418b6727cfd287ab8f2a39f`，clean；[构建清单](D:/Codex/worktrees/candidate-integration-closeout-20261101/app/artifacts/android/0.19.4-aex.115-vc133-8b16f1d61c-release/artifact.json)。SHA256 `a2a958fc7c2ab3d7c630373b108f87491cd188d7aa323b2e68459f32a3b5a208`；原证书 `bd8e3af75921fc4356deacabd44a3d491fda8439ffbc7d073c363974a648cae3`，身份 `app.mihon.desktop.fork`。
- 独立AVD `mihon-candidate-closeout-20261101` / API36 x86_64 / 2200×1400 / 软件GPU：实际132→133覆盖安装，未卸载/清数据；版本、安装APK哈希、证书、appId及首次安装时间核对，133冷启动成功。此处不扩展为一般数据库迁移或真机验收结论。最终native实际11.036s、1测试/12尺寸全部通过；[原始记录](D:/Codex/worktrees/candidate-integration-closeout-20261101/app/artifacts/android/0.19.4-aex.115-vc133-8b16f1d61c-release/native-acceptance/native-acceptance.json)包含升级记录、runner、logcat、测试APK、12张新截图。主审独立核对APK哈希和本次1283×1000截图；先前128包两张截图亦独立查看。相同测试源码及R8 mapping支持复用已适配测试APK，但最终133 runner确实重新执行。
- 先前128候选也通过原生验证，但另一任务基于旧源码生成了132（缺本批功能）；为避免升级倒退，正式验收改交上述133。128仍保留为过程证据，请勿用其覆盖132。
- Windows：[实际 EXE](<D:/Shell/Github/mihon/app-desktop/artifacts/preview/windows/main-89326a642fed4db0aa4ec809404dd90f/Mihon-Desktop-0.11.24.6.5dd165c-unpacked/Mihon Desktop.exe>)；[清单](D:/Shell/Github/mihon/app-desktop/artifacts/preview/windows/main-89326a642fed4db0aa4ec809404dd90f/preview-manifest.json)。固定clean detached主干源码 `5dd165c734ef92d3cc6d49c103d1975574bf61b5`；required F03/F09及最终几何整合提交均核对。完整应用384文件/272579808字节，摘要 `cff910b7dcd9cf056b7f1e0f50e10b4ec1cdd36829c6c2042cd112297eaa13c7` 已独立重算匹配。build/sourceIntegrity/productionRuntime PASS，原生交互 NOT_RUN；不是正式发布包。后续Android版号和文档不改变该Windows输入；随后另一任务的全局搜索聚焦提交d659e70e0b不在本包，本轮不追赶其独立功能。
- Windows实际EXE全新profile headless：health/state 200、testMode=true；shutdown 202且端口关闭，但进程未及时退出，已核对EXE/profile后精确停止自己的runtime。正常关停不标PASS，也不将其扩大纳入本批修复。[HTTP记录](D:/Shell/Github/mihon/.gradle-coordinator/candidate-closeout/windows-5dd-headless-result.json)。可选CDS缓存因命令长度未生成，实际production runtime正常通过，不宣称启动性能提升。

### 服务、源码与环境收尾

反馈Worker已按用户明确授权更新，当前活动版本 `255fc2a9-ee46-48de-9c24-072dae4b7c63`；API版本查询及GET配置200核实。保留vars/secrets，迁移0001–0004已在线，不重复迁移；没有上传真实反馈或部署网站。回退版本 `3d0834d2-90a7-40dd-b202-141bd40744c8` 保留。首次urllib 403与替代curl 200均有记录，不擅自归因。

主要实现/整合提交：F01 `3407b98f49`，F04 `03176f572c`，F02 `68e5e22107` / `701c100daa`，F03 `29ffe87e8c`，F09/F11 `0d026770ef`，最终原生夹具 `0767730aea`，最终版号 `89c9e244a5`。它们均已进入本地main；未推送本任务Git提交或正式发布。用户AppVersion差异SHA256始终 `555d42abf87335aa333182df5118c1ad9cdb43ebb93da8b5eed2e182055e6ee5`，附件目录未动。

本批重型任务串行，未运行模块/仓库全量。保留内存保护、ORPHANED、参数拆词及夹具ABI/全局idle前置故障，不计作行为红测或通过。最终采用分段编译/持久无窗口启动，不反复等待任意空闲内存；所有本任务Gradle/AVD/headless实例均已回收，其他会话和实体设备未操作。

已知非新增失败仍明确保留：相邻stable7项中的zoomed resize、partner retry、optional-header ready、forward drag四项，在本批F09修复前同一基线production下同样失败；未放宽断言、不称57项全绿。此范围以外的旧缺陷及Windows headless正常退出缺口不能因本批交付而视为关闭。

### 用户验收清单

- [x] Android双页白缝：由本任务模拟器完成上述实际133包像素验收。
- [x] 安装133 → 单页/双页阅读 → 水波默认阴影线与方案A切换、快速翻页及中断动画；汉王实际刷新观感请在设备上体验。
- [x] Android阅读设置 → 默认/自动说明边距和颜色、双页匹配位置；切单页/条漫检查选项显示条件。
- [x] 双端设置/关于 → 反馈 → 分类模板、设备摘要默认勾选、诊断开始/暂停/停止、截图转换/预览/移除及附件同意；无需为验收提交真实反馈。
- [x] Windows用上述实际EXE打开漫画 → 单/双页立即翻页、快速翻页及开关匹配 → 图像连续、页码/位置正确；关闭窗口检查日常交互退出。原生用户体验尚待本清单，HTTP冒烟不能替代。

## 用户验收与限定推送（2026-11-01）

用户已明确“验收通过，合入主干并提交推送”。Android133已无线安装至华为平板，官方安装入口回读校验版本、证书及APK哈希通过，未清数据。当前本地主干仍含其他会话未交付的搜索及性能改动，因此本次以实际远端25c5ff2ad043c6d9d6642aa0fcdf6f6f27d62c18为基线，在隔离副本中只整合本批六项及直接关联的回归证据、133版号和验收后双语公共说明。已有远端功能和资源键保留，其他本地改动及未提交文件不推送。

整合冲突仅将设置测试/ZoomablePageBox恢复为已验证候选对应字节；XML合并保留两组不同键并检查唯一性。全部受影响production Kotlin/Worker/版本文件与已验收8b16f1d源码逐项比较；差异仅来自既有远端无关资源/文档，未重写产品实现。复用原focused与原生包证据，不宣称新提交重新打包或全量通过。双语公共文档构建与内部链接检查在推送前执行。用户验收状态不抹去文中已知相邻基线失败或自动关停缺口。

补充源码核对：远端还缺少验收候选既有的Android单页实际绘制后结算修复6aeb27cb9e，已连同其42行真实行为回归保留，以避免将onImageLoaded冒充实际绘制完成。整合后全部Android reader、Desktop reader与共享reader相关production路径与验收8b16f1d保持一致；不带入后续搜索和评分/请求复用优化。繁体资源desktop_ui_missing_chapters重复键在远端与验收源码均已存在，本批未修改，核验只声明无新增重复键。
