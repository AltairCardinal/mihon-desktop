# Windows 首开 R3：Ready 到解码接续实验

日期：2026-11-10。已完成，性能候选 A 未达到冷态采用门槛，已精确撤回；仅保留默认关闭诊断及缓存缺失反馈修复。范围与停止条件见 [R3 计划](../roadmap/2026-11-10-windows-reader-startup-r3.md)。

## 固定输入与边界

执行工作树为 `D:/Codex/worktrees/mihon-windows-reader-source-driven-20261109`，分支 `codex/windows-reader-source-driven-20261109`，起点 `fd85f9d0c799ad986ede7decd3350962fb2cbbe0`，初始暂存区与工作树干净。该提交仅增加 R3 设计；其产品/测试沿用 R2 撤回后的输入修复，不以已撤回候选包作为控制。

仅研究已确认下载、固定 RTL/LTR、单页、非 AUTO、无自动拆页、同一 opening 的真实轻量首屏。继续沿原 scheduler、文件 encoded store、presentation owner、region/pipeline 与实际 draw；不改变配对、解码尺寸/颜色、原图事实、并发和预取预算。完整 model 订阅保留原接线。

内部细观测为默认关闭、有界内存设施。B/C 使用相同诊断，机制采样开启，正式外测关闭。文件 Preferences 后端的同条件结果只说明该隔离环境，不代表普通注册表启动绝对耗时或相对官方净差。

既有 seed 的下载 CBZ 为 41,380,343 字节，SHA256 `b3d7c4e56e58dfee6d9da7d5afafe12b267ccc6d50dd48cd3660456b8d2b4677`；ZIP 内第5个自然数字顺序图片成员 `005.webp` 流式摘要为 `3b43ed0beb3f6f1b832e57ac5ad7c830c00850c8b79fa0c9a708ebf08ccbce14`，与既有目标原图相同。目标 chapterId=906004、index=4、187页。SQLite 只读核对 source=0 及原 local-source-root 存在，其本地章节副本与下载副本字节相同；下载与本地副本字节相同；新事件也未暴露精确副本路径，因此不宣称已证明 production 读取哪份。

## 行为验证与独立审查

真实 RED 已确认：协调器 `r3-red` 运行 `:app-desktop:jvmTest --tests "*ReaderFirstCompositionIntegrationTest.committed queued target*" --offline --console=plain`，退出码 1，1 项测试失败、无错误/跳过。真实 Screen 的 Queued holder 已 apply，目标 materializer 完成并发布 Ready，未再次 render；断言目标原内容读取次数应为 1、实际为空，失败消息为 `Committed target must enter the original content OPEN before another Compose frame`。原 XML 保留在 `.test-tmp/r3/red-result.xml`。此为旧生产链依赖下一轮重组的行为失败，不是编译/环境失败。

最终 B 的8份生产文件原字节及补丁保存在 `.test-tmp/r3/baseline-source/`、`baseline-source-manifest.json` 与 `baseline-diagnostics.patch`，C9份保存在对应 candidate 文件中。B patch 已在独立 HEAD 副本通过 `git apply --check`。主代理核对并按逐文件 SHA 选择 B8 构建，再恢复 C9 构建，没有覆盖其他改动或并发运行 Gradle。

验证组合：`r3-complete-green` 运行 Screen27、owner12、observation3、startup viewport3、替换2，共47项通过；另有 Unavailable3 中1项因协程堆栈恢复复制异常而不满足 `assertSame`，该轮退出1。只修改取消测试为类型、消息及原异常 cause 链断言后，`r3-cancellation-final` 补验 Unavailable3 全过、退出0。这是候选阶段的50项组合证据，不是撤回后最终代码的验证结果。原 XML 见 `.test-tmp/r3/complete-green-xml/` 与最后补验结果。只读 ktlint 1.8.0 格式通过，精确 baseline 仅忽略 HEAD 已有 owner 第85行 `backing-property-naming` 一项，未关闭其他规则；`git diff --check` 通过。未运行模块全量、仓库全量、Android 或正式发布矩阵。

替换 RED 的2项真实 composable 测试均因仍携带旧完整 key 而失败，修复后核对新 ref/provenance、目标 BLUE 像素、两次替换的旧 holder 关闭及最终释放。无重组 Screen GREEN 覆盖首次已 Loaded、晚页表、安装前 Ready、非协作迟到、退出/retry/换章/完整入口提前打开；原 F11/Space/Ctrl 与手势/交接用例继续通过。在线、WEBTOON、双页、AUTO、拆页断言没有新注册。独立审查及修复复核已完成，无剩余阻塞；这些为历史候选验证；最终裁决及撤回后验证见后文。

新增边界及解释：

| 事件 | 实际边界与关联 |
| --- | --- |
| INITIAL_PROJECTION_BEGIN/END | 初始设置/状态投影前后；目标准入沿用原事件 |
| RUNNER_WORK_TAKEN、PREPARE_BEGIN/END | Desktop port取出工作及prepare；END在finally记录，包含早退、异常和取消，表示退出而非准备成功 |
| ENCODED_STORE_RETURN | 已验证 store 返回 Stored ref，晚于原子提交及收尾 |
| READY_PUBLISH_BEGIN/END | session 正常 materialization Ready 的 StateFlow 发布前后 |
| COMPOSE_READY_SEEN | 原 presentation composable 首次计算该完整 decode key |
| RUNTIME_READY_REGISTERED/SEEN | 真实 apply 后注册及运行时消费；注册不代表触发 |
| RUNTIME_READY_LAUNCHED/REUSED | 分别为实际启动原 holder 任务、原链已启动后的幂等接用 |
| RUNTIME_READY_REVOKED/REJECTED | 注册失效或原 owner 拒绝绑定；不报告首图/进度 |

`openingToken` 为 session 的进程内递增身份，解码观测的 `contentIdentity` 为完整 key（ref、provenance、尺寸均在内），仅 ON 时构造字符串。其余既有事件不补造关联字段，按最新正常点击/OPEN 时间窗及真实章/页/attempt/ref 核对；没有 token 的记录不称直接精确 join。不同线程或嵌套 CPU 区间不相加。

以下为已撤回候选的历史审查，不是现行生产架构。主代理独立检查了 production factory 的 `presentationScope=parentScope`、startup-only CompositionLocal、真实 apply 注册、StateFlow 当前值及后续值消费、bind 后再核当前发布、原 owner 单次完整 key 绑定和 acquire 幂等，以及完整入口的 movable holder 接用。观察任务沿既有 scope 派发，无 UNDISPATCHED、轮询或定时器；不迁移完整模型订阅。

| 所属/顺序 | 生命周期与锁边界 |
| --- | --- |
| continuation registration gate → presentation owner → holder → 原 region owner | 验证令牌及完整目标后沿原 bind/acquire；StateFlow.value 为原子读取，不取得 session 锁 |
| session 发布锁 → StateFlow 发布 | 原 publisher 保持原锁；后台 collector 正常派发，不在该锁内执行 bind/acquire |
| composition dispose / runtime close | 先撤销注册令牌，再取消自有 collector；runtime 随后沿原 owner/pipeline/session/content 关闭顺序 |
| 真实 viewport holder/region | 图片 lease 始终归原 holder/region；取消 collector 不关闭仍挂载 holder 的 lease；内容身份变化由新 holder 接管、原 effect 关闭旧 holder |

候选覆盖产品接线、文件准备观测、UI 消费和同链测试，文件数超过8是同一首屏协议的内聚改动，不拆为独立 capability。主要风险为异步身份、取消、内容替换与误报绘出；不影响共享配对/解码质量/资源预算，验证限相关 focused 路径。

### 缓存缺失反馈的已证实缺口

新增真实 Screen 缓存缺失用例在首轮最终范围中失败：目标已 Ready、原 OPEN 计数为1，但 holder 仍为 Loading。确认 `DesktopReaderSharedPageContent.withContent` 将 `content.await()==null` 抛为 CancellationException，region/holder 按取消语义传播，因而未发布正常失败。这是原链已有的错误分类；不归因为性能候选重复读取。

最小修复只改该 Desktop pipeline 方法：await 后仍先在原锁下检查 closed、保留关闭/真正取消的 CancellationException，再将 null 内容作为普通 IllegalStateException。原 region/holder 随后发布 Failed，实际 draw/进度仍不发生。补真实 content→pipeline→region→owner 的缺失反馈、原取消对象传播及 await 中关闭/lease 退出三个 focused 用例。修复两臂共有，B 的精确定义调整为原基线加相同诊断及这一错误反馈修复，C 仅增加接续 A；不以正常内容采样判定此异常修复的性能。

## 两包与原生采样

两包均由本树 coordinator 串行执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-windows.ps1 -Preview`，`r3-preview-b/c` 均退出0；build/sourceIntegrity/productionRuntime 均 PASS。版本沿用 `0.11.24.6.fd85f9d`，dirty=true，未分配正式版本。两臂使用同一 JDK 21.0.11.10-hotspot；可选 CDS 均因既有命令行长度限制未生成，不将跳过记成 CDS 通过。

| 冻结项 | B | C |
| --- | --- | --- |
| preview ID | 20261109T171252Z-0b32db05 | 20261109T171413Z-eee46796 |
| production source digest | d969c8e03014e8039112e7bdf7c5453aa862b33d0dc6f8528a2355f9d165d045 | 10b9b42b943da41ec3782cc38dc4c0bd610162e7a71bf7b1c1117d0b57301d78 |
| diff SHA256 | f3b0cbc5d327ec68db91ac9f704bf9cd906373ddc0f75de934ace0099a6de412 | e43e0033092cef4d5acb45fdb08e1b9ad296ac1bef55bd651d1f7ca29f00c99b |
| 完整应用摘要 | c53fc46e60c760f64de4ddacb1ac482e9c9d307c8867583a30637608e09c53e3 | 4bd8b155200f3c90e42cdabc24fb7aab77dd1073d0ad3dd718793a640cc49400 |

实际产物：[B EXE](../../app-desktop/artifacts/preview/windows/20261109T171252Z-0b32db05/Mihon-Desktop-0.11.24.6.fd85f9d-unpacked/Mihon%20Desktop.exe)、[C EXE](../../app-desktop/artifacts/preview/windows/20261109T171413Z-eee46796/Mihon-Desktop-0.11.24.6.fd85f9d-unpacked/Mihon%20Desktop.exe)；各目录附 `preview-manifest.json`，其中 nativeInteraction=NOT_RUN 保留，不由后续原生结果回改。构建成功不是性能验收。

只读预检当前主屏 bounds=(0,0,3840,2160)、scale=150%/DPI144；副屏=(3840,1296,1920,1080)、scale=100%/DPI96。每份实例仍重新核对实际窗口、进程完整路径及 profile，旧位置不代替当前身份。

## 实际批次、有效性与正确性

内部细观测 ON：Bi1/Ci1/Bi2/Ci2，各为新进程正常章行点击，共4份，目标地图正确且 dropped=0。两份 C 均实际 RUNTIME_READY_LAUNCHED 后进入原 OPEN/decoder。正式细观测 OFF：B1/B2r/B3 与 C1r/C2/C3 的首次及同进程暖态，共12份。另保留 C1 首次有效样本，所有13份均纳入下表，不择优删慢样本。

原 C1 暖态点击工具返回后仍在详情、mountedReader=null，没有暖态回调锚点，判为技术无效；未重发输入。B2 在书架准备时工具报告用户输入干扰，立即停止、关停自己的实例和辅助进程，尚未录制。用户明确允许继续后使用全新 B2r 和完整 C1r 冷/暖对；旧原件均保留。实际首次顺序为 B1、C1、C2、跨批次中断、B2r、B3、C3、C1r。替代使对照时间间隔变长，不能称充分随机或同一连续负载。

所有录制 received=written、missing=0、帧ID连续，逐帧文件存在；主动停止均保留 `PARTIAL/native-capture-exit:1; see native-stderr.log`，不将其改成整段采集成功。下表只使用局部转换的实际帧时间，未补插缺帧、未假设固定60Hz。全部13份覆盖 F 后1秒，所采帧未再黑；间隙内及物理显示扫描输出仍不可证明。

窗口曾因原位置被用户置顶图片遮挡，仅平移自己的窗口600px；各正式新 profile统一 x4488,y1344、1024×768，实际采集扩展边界[4495,1344,1010,761]。本批使用 `IsolatedDesktopPreferencesFactory` 的隔离文件后端（`DesktopTestProfile.kt` 实际接线及 profile持久化值已核对），不是普通注册表。

原生确认 C3 单页[4]→[5]→[4]，地图与下一页实际漫画正确；F11进入副屏[3840,1296,1920,1080]并恢复原窗口/地图。双页匹配OFF为[4,3]→[6,5]→[4,3]，匹配ON为[5,4]→[7,6]→[5,4]，实际两侧漫画均核对，仅作正确性负控。退出后各正式profile数据库 last_page_read=4。所有本批应用、WGC与QPC辅助进程已按身份退出；记录在 `.test-tmp/r3/native-batch-completed.json`。

## 外部逐样本区间

以下单位ms，起点是**应用正常章行回调**，不是操作系统原始输入送达。工具输入包络另保存在各 `input[-warm].json`，不得把调用起止中点当精确点击；包络宽约94–116ms。每次点击以六轮新QPC校准关联采集时钟。

主代理独立查看所有转换联系表及原始边界帧，并用 `.test-tmp/r3/primary-metrics.json` 核验离线 `offline-audit.json/csv`。R为首次可辨阅读器响应，其前一帧仍为旧详情；F为正确地图首见，参考ROI(x300,y150,w150,h200)与稳定地图MAE=0（门槛≤3）。K按固定ROI近黑门槛17检测，原始首阅读器帧ROI亮度均为0，前一旧详情约36.303；人工确认此处无内容区间与近黑区间一致。每份只有一段，故最长K=累计K。区间来自相邻实采帧，不缩成单点。

初稿曾误取同进程首个draw导致暖态负时长，已按本次intent窗口修正；曾漏填首图前黑段，已按固定ROI重算。C2暖态原始880/881仍为旧详情，最终R明确为882，F884不变，非880。没有以这些错误草稿支持裁决。

| 样本 | 状态 | R | F | 最长/累计K | 最大帧隙 | R/F帧 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| B1 | 首次 | 44.048–110.715 | 194.047–210.713 | 83.332–166.664 | 66.666 | 784/790 |
| B1 | 暖态 | 45.147–61.813 | 111.813–128.480 | 49.999–83.332 | 24.999 | 797/801 |
| B2r | 首次 | 51.321–117.986 | 201.318–217.984 | 83.332–166.663 | 66.665 | 841/847 |
| B2r | 暖态 | 43.342–60.008 | 110.007–126.674 | 49.999–83.332 | 24.999 | 807/811 |
| B3 | 首次 | 42.861–76.194 | 201.190–217.856 | 124.997–174.995 | 58.331 | 860/866 |
| B3 | 暖态 | 45.001–61.667 | 111.667–128.334 | 50.000–83.333 | 16.667 | 812/816 |
| C1r | 首次 | 51.860–118.526 | 176.858–193.525 | 58.332–141.665 | 66.666 | 809/813 |
| C1r | 暖态 | 42.382–59.048 | 92.382–117.381 | 33.333–74.999 | 25.000 | 804/807 |
| C2 | 首次 | 50.256–116.922 | 191.921–208.587 | 74.999–158.331 | 66.666 | 825/830 |
| C2 | 暖态 | 48.337–65.004 | 90.003–106.670 | 24.999–58.333 | 25.000 | 882/884 |
| C3 | 首次 | 51.605–118.269 | 201.601–218.267 | 83.331–166.662 | 66.664 | 824/830 |
| C3 | 暖态 | 35.689–52.355 | 85.690–102.357 | 33.335–66.668 | 25.000 | 812/815 |
| C1 | 首次 | 43.898–127.233 | 185.566–202.232 | 58.333–158.334 | 83.335 | 804/808 |

最大帧隙统计窗口为回调前100ms至F后1秒；C1首次为额外有效样本，不替代正式配对。新进程首次仅为process-cold，未清系统文件缓存，不能称disk-cold。三组样本不足以报告P95。

配对差定义为C−B区间 `[C下界−B上界, C上界−B下界]`，负值较好；跨0为未分辨。单位ms：

| 配对 | 状态 | F差 | K差 |
| --- | --- | ---: | ---: |
| B1/C1r | 首次 | -33.855–-0.522 | -108.332–58.333 |
| B1/C1r | 暖态 | -36.098–5.568 | -49.999–24.999 |
| B2r/C2 | 首次 | -26.062–7.270 | -91.664–74.999 |
| B2r/C2 | 暖态 | -36.671–-3.337 | -58.333–8.334 |
| B3/C3 | 首次 | -16.256–17.077 | -91.664–41.665 |
| B3/C3 | 暖态 | -42.644–-9.311 | -49.999–16.668 |

## 内部边界与裁决

内部ON样本只验证机制，不能替代正式OFF外测。时间均相对正常回调，单位ms：

| 边界 | Bi1 | Bi2 | Ci1 | Ci2 |
| --- | ---: | ---: | ---: | ---: |
| INITIAL_PROJECTION_BEGIN | 41.872 | 39.067 | 39.113 | 43.160 |
| INITIAL_PROJECTION_END | 46.258 | 43.804 | 43.452 | 47.821 |
| FIRST_COMPOSITION_TARGET_ADMITTED | 64.538 | 45.646 | 45.504 | 49.791 |
| COMPOSE_SLOT_APPLY | 99.038 | 86.767 | 90.286 | 94.112 |
| RUNNER_WORK_TAKEN | 96.845 | 84.906 | 88.143 | 92.031 |
| PREPARE_BEGIN | 98.309 | 86.054 | 89.640 | 93.297 |
| PREPARE_END | 99.361 | 86.906 | 90.599 | 94.231 |
| ENCODED_STORE_RETURN | 113.768 | 102.169 | 106.394 | 109.962 |
| READY_PUBLISH_BEGIN | 113.922 | 102.317 | 106.570 | 110.211 |
| READY_PUBLISH_END | 113.952 | 102.365 | 106.587 | 110.261 |
| RUNTIME_READY_SEEN | — | — | 112.012 | 118.649 |
| RUNTIME_READY_LAUNCHED | — | — | 114.263 | 121.716 |
| COMPOSE_READY_SEEN | 132.888 | 117.480 | 113.744 | 121.409 |
| HOLDER_CONTENT_BOUND | 135.284 | 118.866 | 113.783 | 121.265 |
| OPEN_PAGE | 142.386 | 123.577 | 116.237 | 123.908 |
| DECODER_CALL_BEGIN | 145.076 | 126.349 | 119.481 | 126.727 |
| DECODER_CALL_END | 172.092 | 153.259 | 146.267 | 154.993 |
| HOLDER_READY | 174.147 | 155.264 | 148.335 | 157.380 |
| FIRST_PAGE_PRESENTED | 189.311 | 173.382 | 175.687 | 185.931 |

Ready发布begin→Compose看到，B约18.966/15.163ms、C约7.175/11.198ms；Ready→OPEN为B28.464/21.260ms、C9.667/13.698ms。C实际启动分别晚于Compose看到约0.519/0.307ms：观察较早但派发后才赢得原acquire竞争，不能声称整段UI等待均被消除。邻页推动同StateFlow可产生第二次合法SEEN，应核当前完整目标而非把首次SEEN当启动。跨样本差不是可直接相减的因果收益。

prepare约1ms，storeReturn→ReadyBegin约0.15–0.25ms；prepare结束→store返回约15ms仍含归档派发、copy与原子提交，不能全部算Compose。归档copy只有chapter/detail4，无openingToken/generation，按本次OPEN时间窗关联，不能假装完整精确join。不同线程/嵌套CPU区间未相加。

**不采用A。** 正反次序中的第二、第三组首次F与K均重叠；第一组替代C1r只有F微小分离，原C1首次仍与B1重叠。暖态部分F改善不能替代冷态门槛。R≤100ms、K≤60ms整体预算也未通过；不宣称普通Windows追平官方。机制成立并不保证端到端净收益，本轮不追加样本、不改试B/C。

## 精确撤回、最终验证与交付边界

保留B的默认关闭诊断和缓存缺失反馈修复；删除A接续helper、注册/collector和仅候选适用测试，owner/runtime恢复HEAD原字节，不保留新等待协议。候选9份源码及候选测试在 `.test-tmp/r3/candidate-source/`、`candidate-source-manifest.json`、`candidate-tests/`归档。最终8份生产边界与冻结B逐文件SHA一致（其中owner/runtime本就等同HEAD），实际长期产品改动为6份；其他生产文件没有本任务差异。上述B体验包与最终生产源码相符，无需再构建第三包；C仅供已撤回实验追溯。

最终 `r3-withdrawal-green` 串行执行（退出0）：

```text
./gradlew -I .test-tmp/r3/format.init.gradle.kts r3Format :app-desktop:jvmTest --tests '*ReaderFirstCompositionIntegrationTest' --tests '*DesktopReaderPresentationImageOwnerTest' --tests '*ReaderStartupObservationTest' --tests '*StartupSingleViewportTest' --tests '*DesktopReaderUnavailableContentTest' --offline --console=plain
```

44项全通过、无错误/跳过：真实Screen23、owner12、observation3、startup viewport3、Unavailable3。最终XML及统计在 `.test-tmp/r3/withdrawal-final-xml/`、`withdrawal-final-summary.json`，协调器日志 `.gradle-coordinator/r3-withdrawal-green.log/json`。格式检查通过，精确忽略仅HEAD已有owner第85行命名规则；`git diff --check`通过。未执行模块/仓库全量、Android、发布矩阵；撤回后不以文档收口触发重复测试。

roadmap逐项收口：R3.0诊断与输入固定、R3.1真实RED、R3.2历史候选GREEN、R3.3独立审查及冻结、R3.4机制/外测及异常原件、R3.5裁决撤回及本地提交。勾选表示实验约定完成，不代表性能预算或用户验收通过。原件为本机过程产物，不提交庞大采集目录；本报告保留可维护的逐样本结果及限制。最终仅提交本任务源码、真实测试和内部文档，不推送、不发布、不更新公共能力说明；后续B/C仍需另行决策。
