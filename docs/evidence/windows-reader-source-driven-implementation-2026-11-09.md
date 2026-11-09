# Windows 阅读器 source-driven 首批实施证据

日期 2026-11-09；实施起点 `f85790f2f2`，产品基线 `1c9e51125787ac1bdf18464ac3f683a1c17faab3`。本文记录 P1–P3 的机制实现和 focused 验证；P0 独立校准另附记录。尚不代表 V1 外部采用、用户体验验收或正式发布，未更新公共能力说明。

## 实现与边界

- P1：已知下载且固定 RTL/LTR 单页的 context 捕获资格；持续观察同一 session，晚到页表后解析恢复位置并挂载真实 Queued holder。真实 draw 与正常进度先于无额外输入路径的完整 model 创建；模型创建与视觉交接分离，等待配对/内容状态并核对页/代次/attempt/encoded ref。Screen composition 的 session bridge 在模型解锁后即连接，不恢复 D7 的 IO 迁移。
- 同一 movable registry 保留 holder/lease，逐 render 检查实际目标像素；父级重组及跨首绘活动 pinch/pan 保留 owner，release 后保留缩放和平移。早期原始键鼠/相对翻页在 Loaded 后交给原 handler 一次消费；非法恢复、页表失败、退出与在线/条漫/双页/AUTO/拆页回到原反馈/入口。
- Screen 夹具通过真实 `DesktopReaderEncodedPageStore.store`/staging file 原子写、`contains` 查找及 `read` 读取后交 Skia。187 页使用由 index 区分的像素，第5页为蓝；正常交接目标 encoded read 为1。源数据为合成 fixture，不冒充真实 CBZ 性能内容。
- P2：静态整页无 split/source bounds/裁边时在 Ready 组合直接 retain 原资产。RememberObserver 覆盖 forgotten/abandoned；回调核对 composition lease 和实际资产身份。动画/裁边/拆页继续异步，旧代/替换结果与资源清理由原 owner 契约保护。
- P3：factory 在真实 placement mutation 前求值一次，缓存 adapter/null/初始化异常；普通 windowed 生命周期不构造 native。所有原显式注入改为 factory，禁用 native 显式 `{ null }`；原 retained-frame、revision 与 Compose fallback 保持。

## 真实红绿

| 记录 | 命令范围与结果 |
| --- | --- |
| `source-p1-red2` | `:app-desktop:jvmTest --tests mihon.desktop.ui.reader.ReaderFirstCompositionIntegrationTest --offline`：旧实现真实失败 `Full model must start after actual target draw`。原 XML 保留 `.test-tmp/source-driven-red/P1.xml`。首次误用不存在的 `:app-desktop:test` 只为任务名错误，不作红测。 |
| `source-p023-red` | 真实旧 no-op 首轮组合返回 null；真实普通 windowed controller 的 default adapter 创建次数1（目标0）。两份原 XML 保留 `.test-tmp/source-driven-red/`。同期 P0 的临时 profile 清理失败由其校准分工修正，不能算产品红测。 |
| `source-p0123-green2` | 最初共同范围通过，33秒；随后补齐真实文件、像素、输入、配对与生命周期边界，不用该早期结果代替最终补验。 |
| `source-p0123-focused8` | 11个指定类135项，134通过、1失败、零跳过。唯一失败是非法恢复反馈覆盖中心像素，与首屏可连续呈现断言的适用条件不一致；保留恢复状态断言，连续像素断言限定真实优化准入场景。没有 production 修复或取消恢复面板。 |
| `source-p1-focused9` | 仅复验受上述测试修正影响的真实 Screen 类18项，全部通过，22秒；production 与 focused8 一致。 |

最终证据是 focused8 中未受后续测试断言影响的通过项，加 focused9 的18项 Screen 补验，不宣称最终源码重跑模块全量。无完整模块/全量、Android、正式构建或原生窗口操作。所有 Gradle 均由本 worktree coordinator 串行执行；源码/导入修正引起的中间编译失败保留在对应日志，不冒充行为失败。

focused8 原始 XML 已固定到 `.gradle-coordinator/source-p0123-focused8-xml/`，focused9 日志为 `.gradle-coordinator/source-p1-focused9.log`。二者均为本地过程证据，不是新增产品能力或发布产物。

| focused 类 | 项数 | 覆盖 |
| --- | ---: | --- |
| `DesktopReaderAnimationPresentationWiringTest` | 3 | 真实动画帧像素、静态图无帧job、实际呈现与释放 |
| `DesktopReaderPresentationImageOwnerTest` | 12 | 真实呈现资产owner/代次/attempt及lease生命周期 |
| `DesktopReaderChapterTransitionIntegrationTest` | 15 | 完整Screen的章交接/恢复/页表迟到接线 |
| `ReaderFirstCompositionTest` | 1 | production host同一startup调用点及holder先挂载 |
| `StartupSingleViewportTest` | 3 | 真实Queued→Ready同holder接手、活动拖动owner、旧代/attempt |
| `ReaderFirstCompositionIntegrationTest` | 18 | 真实Screen/文件store/目标像素/先draw后model/晚到页表/恢复/失败/早期输入/父重组/活动pinch-pan/关闭 |
| `ReaderFullscreenFramePresentationTest` | 11 | mock窗口下真实controller的prepare/绘制/完成/异常清理顺序 |
| `ReaderFullscreenTest` | 20 | 普通创建0、真实切换缓存1、null/throw fallback、初始全屏退出及队列生命周期 |
| `ReaderPreferencesBackendCalibrationTest` | 1 | P0两种后端独立进程的配置/计数/时序与私有节点清理 |
| `ReaderPresentationTransformOwnerTest` | 8 | 首轮Ready可绘、forgotten/abandoned、同identity真实资产替换、异步取消/裁边拆分/释放 |
| `SharedDualPageNativeStablePresentationTest` | 43 | 43项真实Compose双页frame/像素/裁边/缩放/重试/尺寸与资源接线；窗口均为mock，无桌面操作 |

## 格式、文档与剩余门槛

缓存 ktlint 1.8.0 按项目 `.editorconfig` 检查15个已跟踪 Kotlin 变更文件：本次修改行违规0；未改旧行存在79条规则诊断，未做无关全文件格式化。完整只读日志 `.gradle-coordinator/source-modified-ktlint.log` 和逐文件/修改行摘要 `.gradle-coordinator/source-modified-ktlint-summary.json`。`git diff --check` 通过。P0新增文件由校准分工另作全文件格式核验。

长期约束同步至架构/UI入口、`desktop-reader-startup-observation.md` 与 `desktop-reader-fullscreen.md`，保留D7历史反例。没有改变偏好缓存语义、配对算法、schema、Android行为、版本或发布配置。主要diff超过400行源于真实Screen夹具、生命周期断言、必要factory注入口迁移与说明，仍为同一首屏批次。

独立审查、P0最终格式及集中 V1 B/C 外测由协调者收口。本实施者未提交、勾选roadmap或推送；外部首漫画/近黑收益与普通注册表绝对性能仍待相应实测，不从组件或内部顺序推算。

## 协调者独立审查

主协调者未参与production实现，已检查初始资格与持续订阅、模型解锁和视觉交接、早期输入与恢复、同holder/lease身份、组合放弃、实际资产替换及fullscreen factory/null/失败生命周期。审查发现的父重组提前退场、已释放direct lease旧回调仍有效及显式null注入口兼容风险均在本批修正，并由对应真实Screen/资产/controller回归覆盖。真实encoded store接线与不同页像素断言补齐后，复核focused8 XML为135项/1失败/零跳过，focused9为18项/零失败，production未再变化；本批所选范围的有效组合已通过。修改行ktlint0违规、P0新文件单独格式通过、diff空白检查通过。P0–P3可以固定为候选源码；性能采用仍须V1。
