# Windows 阅读器 D6 实施与裁决

2026-11-06，本批已完成实施、定向验证、独立审查及外部裁决；C1/C3按局部工作量优化采用，未证实稳定首开毫秒收益。用户授权按[roadmap](../roadmap/2026-11-06-desktop-reader-d6-roadmap.md)执行至完成，依据为[候选分析](desktop-reader-d6-optimization-analysis-2026-11-06.md)。实现/定向验证由Sol medium负责，限定读取与离线统计由Luna medium负责，主代理先定范围、独立审查、唯一桌面输入并统一提交。无全量、正式版本、主干整合或推送。

## 基线与单次执行范围

工作树D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105，分支codex/desktop-reader-first-open-20261105，执行起始a5fb088dfccfe3c0c578bf973ced6496ae7bf396。该提交仅文档，产品逻辑同42531460df及D5控制E0；D3/D4保留，D5提前encoded准备候选已撤回。本轮C1/C3各自真实RED/GREEN，C2满足机制与完整依赖门槛才实施；C4/C5/C6各一次有界审查，没有安全可失败假设时不实施，C7不启动。

过程原件限定本树.test-tmp/desktop-first-open-d6-20261106/{sol,luna,actual}/；代理不修改用户原目录、操作桌面或提交。组合包如包含多项局部优化，只能报告组合的外部F，不拆分每项毫秒，也不相加内部重叠区间。

## 逐项结果与采用裁决

| 项目 | 当前状态 | 必要证据与采用边界 |
|---|---|---|
| C1旧身份查找 | 局部采用；未证实稳定F收益 | 模型真实acceptSessionState连续两次：8页旧identity读取44→16、187页17,765→374；方向、版本、重排、代次、关闭保护通过 |
| C3重复下载判断 | 局部采用；只计回调去重 | 正常readerRequest→refs→ReaderNavigator接线每远端章2→1，source0仍0，下次打开重查；不计入source0黑屏收益 |
| C2重复投影 | 本轮不实施 | 旧原件缺同输入调用数/独占成本；configure/Mode已同值guard、认证已有memo，frame准备依赖动态位置/locked/painted/restore等，未取得安全完备键或真实冗余RED |
| C4 Ready后交接 | 本轮不实施 | 当前Ready经owner/job校验直接发布StateFlow，未证实另一处可删除的排队/交接；大wall区间保留为后续问题 |
| C5首次入口/页表 | 本轮不实施 | 进度活动身份及单次materialize承担必要职责，未证实同次重复物理定位或可删除依赖 |
| C6等价decoder工作 | 本轮不实施 | 原图栅格用于原图/四旋转样本，输出栅格承担方向/采样及premul显示，未证明可以等价省略；不能用总时长减readPixels冒充冗余 |
| C7纯小读IO | 不立项 | 当前单次纯IO不足以解释主要窗口，无新的重复大读证据 |

## 测试、独立审查及构建

C1只在sameOrder成立时按ordinal取旧内容，并继续检查pageId/contentVersion；否则保留原查找与默认方向行为，没有新缓存/版本/锁或调度。C3每章只建立本次局部downloaded值，两个字段复用，不跨打开保存。

真实RED：c1-red.xml测试2/失败1，实际8页44、187页17,765次读取；c3-red.xml测试2/失败1，每远端章{1=2,2=2,3=2}而要求1，导航及值断言已先通过。首次C3夹具误判next方向与Windows wrapper入口错误单列保留，不作为产品RED。最终GREEN为6/6、无fail/error/skip，约21秒，coordinator d6-c1-c3-green。选择器为 `*ReaderIdentityReuseProductionTest*` 两方法、MangaDetailScreenModelTest两个新增readerRequest方法及原filtered/duplicate方法、LibraryScreenModelTest原library/detail过滤一致方法；准确命令及XML见sol/focused-evidence.json、{c1-green,c3-green,library-wiring-green}.xml。未跑模块全量或不存在的Desktop Spotless；git diff --check通过。

根独立审查读2 production+2 tests及真实RED/GREEN、接线与C2/C4/C5/C6相关生产源一次，通过，无修复请求。计数夹具仅将真实owner.identity.content包装为同元素只读CountingList，用真实acceptSessionState计数，未复制查找算法；方向事实通过capture/accept真实入口建立。C3通过正常readerRequest和ReaderNavigator核过滤/下一章，后续打开再次调用。4文件SHA冻结于root-source-freeze.json，审查结果root-review.json。结构等价不等于首图已提前。

上述44/17,765/16/374为一次真实accept中旧content的总读取，包含查找之外的后续比较，不是ensure调用次数或纯firstOrNull比较数；连续两次accept分别得到相同计数。它与分析中仅按嵌套查询推导的17,578次/调用口径不同，不混作同一个计数。

最终GREEN经协调器执行的精确范围如下；构建状态看后续独立build JSON/log/manifest，focused-evidence.json里构建前的“not yet”字段不是最终构建失败：

```powershell
gradlew.bat :app-desktop:jvmTest --offline --tests "*ReaderIdentityReuseProductionTest*" --tests "*MangaDetailScreenModelTest.readerRequest reuses one download decision per remote chapter for navigation and filtering" --tests "*MangaDetailScreenModelTest.readerRequest local source never invokes remote download checks" --tests "*MangaDetailScreenModelTest.readerRequest marks filtered and duplicate chapters for shared reader skip policy" --tests "*LibraryScreenModelTest.library continue and detail entry skip the same chapter filtered by manga metadata"
```

唯一候选preview为20261106T120253Z-d4b69292，源码a5fb088dfc加冻结dirty diff，productSource `4e1f898b62e33032136d5bcb926b15fab6e17b25e7c173cf1465ef7eee255af5`（2404文件）；完整artifact树 `03523acefb76f334b3ac49185f4600ee081562e872b793e51db4fc7ef2b6260a`，387文件/275,126,653字节。[组合候选EXE](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261106T120253Z-d4b69292/Mihon-Desktop-0.11.24.6.a5fb088-unpacked/Mihon%20Desktop.exe)。通过coordinator执行 `pwsh -NoProfile -File scripts/build-windows.ps1 -Preview`，desktop-d6-c1-c3-preview exit0；build/sourceIntegrity/productionRuntime PASS，nativeInteraction构建时NOT_RUN保留。源SHA构建前后匹配审查冻结。可选CDS因参数过长未生成，和旧控制同类警告保留，不为此扩修或宣称完整发布就绪。

已冻结控制E0为preview20261106T025455Z-23e6a21a，productSource 889551df123de1690d5a882c183132cffab857e11388e962efd53e7922d2a908，完整app tree adf29ae03ef8a527f4916e9121ccf88142d4d007f1f60c40e3a733b687d66cc2。实际[控制EXE](D:/Codex/worktrees/mihon-desktop-reader-first-open-20261105/app-desktop/artifacts/preview/windows/20261106T025455Z-23e6a21a/Mihon-Desktop-0.11.24.6.86c5628-unpacked/Mihon%20Desktop.exe)。本轮已核对两臂相同点击锚点、细观测OFF、正常内容触发及相同runtime/bin/server/jvm.dll（SHA256 9dac8ae1dfe88cb107e27ee952071682f48ab67266920cbecf03b15bb02ce181）。不把旧包sourceRevision改标为本轮最终提交。

## 外部测量与结果

本轮采8份有效样本、10个逐页端点，全部是D6/actual新raw；复用旧E0的是EXE，不是旧测量数据。相同SHA的史莱姆04卷187页，RTL，正常详情章行恢复index4，隔离profile；fine observation OFF、两臂均有低成本callback锚点。控制/候选各single first/warm，候选双页OFF/ON first及往返翻页，然后补单页反向fresh first各一份。没有在首图前GET状态，没有离线分析与活跃采集并行。

### 真实首见与无内容间隔

单位ms，区间是实际相邻WGC帧及输入时点的不确定边界，不是置信区间。R为可辨载入文字和spinner；F是正确目标漫画，双页取完整页对。K按每侧ROI独立报告；本组首图前只有一个近黑段，累计与最长相同。callback是应用回调，晚于系统输入；工具输入包络保留为另一口径。

| 样本 | 显示页序index | R，callback起点 | 完整F，callback起点 | 完整F，工具输入包络起点 | 各页K，最长/累计 |
|---|---|---:|---:|---:|---|
| E0-single/first | [4] | 37.979–179.644 | 546.305–587.971 | 551.363–679.737 | 4:366.661–549.992 |
| E0-single/warm | [4] | ≤143.482（仅上界） | 126.814–143.482 | 128.008–234.750 | 4:66.668–100.001 |
| D6-single/first | [4] | 39.008–172.339 | 489.003–522.336 | 493.354–620.242 | 4:316.663–483.328 |
| D6-single/warm | [4] | 34.679–59.679 | 134.680–151.347 | 136.603–252.990 | 4:75.001–116.668 |
| D6-dual-off/first | [4, 3] | 33.869–125.534 | 558.863–600.531 | 561.946–696.599 | 4:433.330–566.662 / 3:433.330–566.662 |
| D6-dual-on/first | [5, 4] | 42.307–108.974 | 642.298–658.965 | 645.415–755.473 | 4:449.992–599.991 / 5:533.324–616.658 |
| D6-single-reverse/first | [4] | 23.370–123.370 | 340.034–356.700 | 343.759–450.327 | 4:216.664–333.330 |
| E0-single-reverse/first | [4] | 24.169–115.839 | 357.496–374.162 | 361.127–479.228 | 4:241.658–349.993 |

E0 warm未匹配到完整载入文字，R仅由首漫画给出≤143.482ms的callback上界，不能把126.814ms当作真实响应下界。工具输入起点的R过渡区间另在[10行逐页CSV](desktop-reader-d6-2026-11-06.csv)；它与“首张拍到响应的时刻减输入包络”的点值范围不同，不能互换。ON首次页4单独F_callback为558.966–642.298ms，页5为642.298–658.965ms；不能把地图先出现写成两页齐全。

首组single first候选较早，按两端名义差为57.302/65.635ms，保守可分辨差23.969–98.968ms。反向组名义差17.462ms，保守差0.796–34.128ms。两组都观察到候选较早，但绝对首次耗时与差值明显漂移；反向组中间发生Esc中断，末份E0在恢复后fresh profile/JVM采集，与候选相隔约528秒。机器其他用户程序未关闭，负载、温度与磁盘缓存未控制；fresh JVM不等于磁盘冷态。不能据此给C1记固定17/60ms或宣称稳定因果收益。

warm F区间重叠，候选两端名义约晚7.866ms，保守差允许候选慢24.533ms到快8.802ms，未证实改善或可分辨退化。K区间也重叠。双页只有候选自身端点与正确性，没有本轮同条件控制，不宣称双页提速或完整跨模式性能不退化。C3在source0夹具短路，不能解释这里的F差异。因此采用按局部等价工作量门槛，而非复杂呈现候选的严格可见收益门槛；未追加其余6份矩阵追噪声。

### 同次内部阶段账

下表起点同为callback；相邻阶段闭合到应用完整首绘确认，不能与外部F或不同样本的细诊断相加。双页开流取最早目标，末段到两页均应用确认，包含另一页的等待/解码/交接。DECODE仍是调用前事件，不代表解码结束。

| 样本 | →会话激活 | →页表就绪 | →首目标开流 | →完整应用确认 | 总计 |
|---|---:|---:|---:|---:|---:|
| E0-single/first | 43.228 | 208.200 | 183.451 | 100.556 | 535.435 |
| E0-single/warm | 5.707 | 11.194 | 33.808 | 51.485 | 102.194 |
| D6-single/first | 39.870 | 176.648 | 181.915 | 81.708 | 480.141 |
| D6-single/warm | 12.326 | 11.511 | 25.302 | 48.840 | 97.979 |
| D6-dual-off/first | 33.308 | 75.565 | 317.817 | 141.325 | 568.015 |
| D6-dual-on/first | 33.508 | 73.708 | 135.957 | 382.912 | 626.085 |
| D6-single-reverse/first | 34.571 | 75.020 | 138.521 | 69.757 | 317.869 |
| E0-single-reverse/first | 33.742 | 88.922 | 145.788 | 74.470 | 342.922 |

首组控制到候选的页表前阶段也提前约35ms，而C1主要替换页表摄入后的旧身份查询；这进一步限制了“全部外部差值来自C1”的解释。真实UI的ensure调用数、每次exclusive CPU/锁时间仍未测，本轮计数来自真实production接线夹具，不能换算UI毫秒。

后续优先分析页表就绪→当前页开流（候选反向single 138.521ms、OFF 317.817ms）与目标内容→实际可见的混合区间（ON从最早目标开流到完整应用确认382.912ms）。它们是机会窗口，不是已证实冗余。Ready直接发布并不等于EDT/状态传播/帧机会都已排除；C2缺完整键也不等于所有重复工作不存在。先取得同请求的可控依赖证据，再另定最小候选，不能复活D5提前准备或用删除身份/进度保护换速度。更新的排序见[分析末节](desktop-reader-d6-optimization-analysis-2026-11-06.md#7-d6实施后的剩余优先级)。

### 正确性、原帧复核与采集边界

主代理逐份核latest OPEN+chapterId+openIntentNanos唯一callback关联、输入覆盖、页序/模式/RTL、fine OFF、原始systemRelative100ns×100==QPC、帧序/PNG完整及R/F/K边界数学；8份/10页全部通过，4源文件冻结SHA仍匹配，GREEN XML仍为6/6。另目视核8份R前/命中及F前/命中：7份有载入文字/spinner，E0 warm保留上界；全部首图为正确漫画，ON确有地图先出现的原帧。raw与root-audit.json、root-raw-boundaries-{six,reverse}.jpg保留在本轮ignored目录。

8份均主动停止，manifest保留PARTIAL/native-capture-exit:1，received=written、missing=0；接收帧数依次1289、835、1568、1906、1718、1541、1713、1755。分析窗最大实际gap依次141.665、25.001、133.332、25.001、199.998、141.664、100.000、91.670ms。长间隙留在K不确定范围，不能拆成有内容或按60fps补帧。首图后未观测到再黑，不代表连续物理扫描已证明零黑帧。input包络86.709–101.436ms，时钟映射交集宽28–48微秒；不得把前者误称输入测量精度为几十微秒。

候选单页[4]→[5]→[4]、双页OFF[4,3]→[6,5]→[4,3]、ON[5,4]→[7,6]→[5,4]均正常，图像及方向与预期一致，未改decoder/分辨率/颜色路径。ON返回同一可见页对后scalar currentPage为5（UI6/187），不能写成scalar恢复index4通过，也未做对应控制的scalar对照，不能新归因为本轮缺陷。single及OFF正常退出回详情；末次控制退出详情显示第5页。迟到请求、换代/关闭、版本/方向保护按本次触点由真实focused覆盖，不宣称全模式或完整资源压力矩阵已验收。

### 工具准备及中断记录

Luna首次把四个临时helper/报告误写到历史D3 ignored工具目录，事前无文件清单，无法确定是否覆盖同名旧工具；后续停止触碰该目录，不视其工具完整性已核。正确D6副本从原D5重新复制，路径/模板及仅预期差异经核查、--help通过，未以错误副本启动应用或采集。正式raw不使用误写副本分析；原D5源只读。最后汇总曾误写一个`$summary`临时文件，代理确认已移除；正式CSV在正确D6/luna目录，核8条/10页。两者不是性能样本或产品回归。

另保留三个无章节输入的采集：E0-single/first-preclick-check、D6-single/first-preclick-check及prep-only-E0-single-reverse-interrupted/first，均无input.json，未计有效样本。前两次初始≤1秒日志新鲜度门槛过严；末次在检查准备阶段过期并被Esc终止，恢复后整份profile/raw归档，再以fresh profile/JVM采有效first，未替换任何慢样本。E0最早一份旧capture.log重启时曾被覆盖，不能声称所有准备日志完整；原帧/无输入事实仍保留。另有一次未采集的封面预览准备启动，不算章节打开。

已核录制器CompositorWriter对events.jsonl使用默认缓冲写入、不逐事件flush；文件tail距当前QPC约0.7–2.3秒不能单独证明录制已死。正式点击前以自有进程存活、manifest尚未结束、tail时间有界（≤5秒）、健康检查≤10秒组合把关，再以实际input→首图原帧覆盖复核；不改两臂录制器，也不把缓冲tail叫最新帧精确时点。成功方法同步[采集指南](../automation/DESKTOP_READER_LATENCY.md)。

## 最终交付

采用C1/C3：它们有真实计数RED/GREEN、短章负控、无新缓存/锁/调度、保持身份/方向/进度及实际三模式正常画面。**采用结论仅为工作量减少，未证实稳定F收益。** C2/C4/C5/C6完成有界条件裁决但未实施；C3位置复用及C7未启动，不把这些写成优化已落地。

R≤100ms、K≤60ms及追平官方目标仍未通过；本批任务完成不等于体验目标或发布就绪。官方Android是不同硬件/采集链的体验参考，不能拿它与callback数据直接相减。该结论与新raw/CSV及父roadmap一致。

全部自有测试应用、WGC及QPC helper已退出，正常阅读器退出保存进度；按完整EXE/本批helper命令核归属，不关闭用户应用、终端或改用户配置。源码、2测试、报告/CSV、roadmap及必要内部维护说明随本批本地提交；不推送、不合入主目录、不更新公共功能承诺。最终预览仍为上文冻结dirty输入包，不能把后续文档提交冒标成包源码；未为收口重建或扩大测试。

本批文件数超过8：2 production、2 tests、8份相互引用的内部文档/CSV，均围绕同一C1/C3行为及D6裁决；无独立能力、依赖或共享协议扩张。主要成本为一次preview、6项focused及8份窗口采集/离线分析；恢复只补最后一份控制，不新增性能候选。

## 验收后的主干交付（2026-11-06）

项目所有者明确验收本批Windows改动，并授权合入主干、提交及推送；随后确认扩展兼容也已验收并入主干。重新读取实际远端，主干已为85185543361292863fd1cba43fd5f1f6c334c928，包含扩展兼容验收后的提交，因此以该主干为整合基线，保留其内容，不再使用此前准备的旧远端隔离候选。

整合104851e5c54c696557c68820c2e2bf840d152bcd及其7个同任务前置提交（D0–D6），自动合并无冲突；公共功能/变更历史同步中英文，只说明重复工作减少，不扩张稳定毫秒收益、≤60ms或跨平台验收。既有未提交Android文件位于另一工作树，不纳入本次提交。上文“不推送/不合入”是实施阶段边界，已由本次用户明确授权更新。

本次不重新打包或分配版本，先前预览仍对应其冻结dirty输入；主干整合不能改标其源码身份。合并后的11项focused全部通过（0失败/错误/跳过，coordinator d6-main-delivery-tests，4分35秒），包含原6项及正常点击锚点、最新OPEN关联、真实CBZ无进展终止/晚到输入唤醒。双语网站VitePress构建通过，四个生成页面内容/实现SHA和内部断链检查通过，格式检查通过。此次Windows启动器路径写法错误发生在Gradle之前，纠正后仅执行这一次focused，不算产品失败；推送使用固定完整SHA与精确提交集合，并核实际远端包含关系。完整测试/正式发布未执行，本轮性能裁决与未通过体验目标不变。
