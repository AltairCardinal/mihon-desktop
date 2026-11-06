# D8：删除同次恢复入口的重复准备

执行入口为[D8计划](../roadmap/2026-11-07-desktop-reader-d8-restore-prepare.md)，基线main `5a4b48625c91a4cf21c5872336af4608b20ab31b`，工作树 `D:/Codex/worktrees/mihon-main-reader-delivery-20261106`。Sol medium负责production及真实RED/GREEN，Luna medium负责限定旧诊断提取，根负责一次独立审查和本地提交。没有修改另一工作树的Android未提交内容。

## 发现与最小变化

`acceptSessionState`在生命周期锁内调用`ensureDualSession`，检查恢复状态后又进入`beginPairingRestore`，后者再次调用`ensureDualSession`。构造函数也有先准备、再进入恢复入口重新准备的结构。独立重试和保存冲突恢复没有这份刚准备的owner，仍须重新准备。

D7三份当前主干诊断在首次SESSION_ACCEPT窗口均观察到2轮identity、3轮projection，稳定暴露的章/页/代次/线程相同：

| 历史fine ON样本 | 第二轮identity | 第二轮projection | 第三轮projection |
|---|---:|---:|---:|
| 单页 | 0.3122ms | 1.2786ms | 1.6930ms |
| 双页OFF | 0.2806ms | 0.9010ms | 0.8967ms |
| 双页ON | 0.3785ms | 0.9747ms | 0.8141ms |

这些是旧诊断中的inclusive墙钟，不是D8前后实测净收益，也不能据此证明owner全部输入恒定。OFF/ON全trace丢弃1/82个事件，所列区间端点仍保留；区间内另有其他线程等待publisher锁。可删除性由下面的真实行为RED及同锁调用关系证明，不靠这些时间戳作因果结论。

唯一实现是：初始化将准备与恢复放在同一生命周期锁内；初始化和页表摄入显式向私有恢复helper传递刚准备的owner。helper继续核对dispose、owner当前性和关闭状态；独立retry及保存冲突入口不传owner，继续沿原入口ensure。没有跨调用缓存，没有移动订阅线程，也没有改变scheduler或实际物理页启动时机。

恢复请求建立后的RESTORING投影、成功/失败回调投影、请求版本与迟到结果检查全部保留。候选只删除恢复入口之前的第二次准备，不把所有publish一律压成一次，不涉及图像质量、解码或原图认证算法。

## 真实行为与采用依据

新增回归执行真实ReaderScreenModel、SharedDualPageSession和DesktopChapterPairingCoordinator。repository读取被暂时挂起，以固定准备/RESTORING窗口；先确认读取一次、共享RESTORING、释放后READY、保存的forcedSingle={3}、revision=4和页身份正确，再断言实际production阶段计数。constructor直接Loaded与空页后accept两个入口均覆盖；没有复制ensure逻辑、扫描源码或断言绝对ms。

| 同次恢复进入，两个入口均一致 | 修改前RED | 修改后GREEN |
|---|---:|---:|
| identity准备轮次 | 2 | 1 |
| held阶段projection发布轮次 | 3 | 2 |
| 实际repository读取次数 | 1 | 1 |

RED两个入口的恢复结果先通过、最后因期望identity=1而实际=2失败；修改后通过。保留的两轮projection分别为必要的准备发布和RESTORING状态发布，repository返回后的正常发布不计在held窗口。计数减少50%/33%仅指这两个局部轮次，不是总启动耗时比例。

根独立审查production和新增测试一次，确认owner仅在同锁、同次准备后传递，空页初始化不启动恢复，独立入口保留ensure，必要状态发布及异步迟到结果边界不变。没有要求修复或第二轮审查。

## 性能结论与成本控制

按确定冗余工作减少局部采用。**本轮没有测定外部漫画首见或近黑的净改善，不能报告“首开快了1–2ms”或达到≤60ms。** 上表历史第二轮约1–2ms只能说明该小切口的数量级。D7的单页F/近黑仍是已有外部参考，不从它扣除本轮阶段计数。

本次没有构建控制/候选preview，没有启动GUI、ADB或录制，没有为亚帧级假设重复12份采集。现有可执行包没有因此自动更新；该改动只在本轮最终源码内，须随后续正常构建进入应用。没有新增依赖、全模块/全量测试、正式发布或推送。

过程原件保留在本树 `.test-tmp/desktop-first-open-d8-20261107/`：Sol的RED/GREEN XML、focused结果及前后源码SHA位于`sol/`；Luna的15行阶段CSV和关联JSON位于`luna/`。原始D7诊断未改写，未将旧raw当作D8性能A/B。

## 最终验证与交付边界

新增回归先单项GREEN，再纳入10个不同focused：DesktopChapterPairingModelIntegrationTest选择8项，覆盖两个优化入口、RESTORING ticket/首绑定、失败不被普通发布误重试、当前owner重载去重/旧页拒绝、旧章迟到读取、dispose后已接受保存与重开顺序、同章页数改变/旧保存回调、错误作品身份与临时文件；ReaderIdentityReuseProductionTest两项覆盖方向/版本/重排/代次/关闭及8/187页读取上界。共10/10通过，新增回归先前单项GREEN也是通过；没有执行模块全量。

实际验证经协调器的d8-restore-red/red-both、d8-restore-green、d8-restore-focused串行执行。最终命令为 `.\gradlew.bat :app-desktop:jvmTest --offline --no-parallel --max-workers=2` 加上述8个恢复方法和 `*ReaderIdentityReuseProductionTest*` 的精确 `--tests` 选择器；完整参数数组、工作目录、退出码0及进程身份保存在`sol/d8-restore-focused.json`。最终两个XML分别8/8、2/2，errors/failures/skipped均0。计数针对测试repository调用，不是物理数据库读耗时。`git diff --check`通过；没有为提交追加重跑或不存在的Desktop Spotless任务。

根核对RED中两入口的结果检查先于计数失败、GREEN的1/2/1输出、最终10项XML和源码SHA，并确认自有验证进程已退出。唯一production修改为ReaderScreenModel，另改真实模型/coordinator集成测试；没有改共享核心或增加DI/导航入口。

最终冻结：

- ReaderScreenModel.kt：`4108b7b35be919b606667973fb387317228fd2aba12efab13be6d5e42ee2444c`。
- DesktopChapterPairingModelIntegrationTest.kt：`4fb029c8988563c0f7f6c678b0af9e8be451d9e58ba9df0d07563134b0f2f258`。

按确定工作量减少局部采用，代码、测试、计划checkoff及内部维护说明同批本地提交；不推送。单页/双页原生打开、外部F/K、Android和其他平台没有在本轮重测，不能将恢复接线通过扩大成完整模式或体验验收通过。
