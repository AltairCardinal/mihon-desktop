# 三个页面契约与固定验收 v2

冻结日期：2026-09-25，基线 `407ec81428c5070c10758da51cf209e25da2c7ed`。本文件在v2实现前确定验收；修改范围需说明原因与影响，不得因实现缺失取消必做项。来源标签见[入口](README.md)。源码事实与组件值由[参考表](desktop-reference.md)支撑。

## 共同环境与边界

Windows/Android各在独立iframe中，数据为本地样本、无网络请求。只共享应用外主题指令，不共享导航、选择、焦点、计时器或偏好；刷新允许重置。只保留三页和本地阅读预览入口，其他底栏禁用。阅读预览不是图片引擎；删除仅改假下载状态；同步设置不启动真实后台任务。

深/浅色、Desktop宽窗口及Android 320px；200%字号验证按钮和滚动可达。每端：窗口外壳提供平台栏/底栏，当前页提供唯一顶栏及内容滚动，弹层独立滚动。使用稳定data-testid定位，不把翻译文本作为唯一定位手段。

## L：书架、搜索、筛选（root）

所有者：本端应用状态。保存字段：query=null或字符串、未读筛选三态、书架滚动位置；从详情返回恢复。筛选不保存到真实磁盘。无真实异步请求，loading/network error非本样本状态。

| ID | 前置 → 用户动作 | 状态、反馈及验收 |
|---|---|---|
| L1 | 普通书架 → 搜索 → 输入/清空 | 输入聚焦；清空仍处搜索；空结果保留顶栏并能解除限制；Escape退出搜索 |
| L2 | 全部 → 连点未读筛选 | 全部→仅未读→排除未读→全部；标签/语义状态/可见实体一致；与查询叠加 |
| L3 | 已查询并筛选 → 打开漫画 → 返回 | 恢复原query/筛选/可见集合/滚动；焦点回原卡片；重置筛选不偷偷清查询 |
| L4 | 筛选面板打开 → Escape/关闭入口 | 先关闭筛选，保留筛选结果，不直接退出搜索或离开页面 |

L1来源UI-11；L2来源UI-12+Desktop三态业务；L3/L4为PROJECT_POLICY在HTML中的明确契约。HTML不照搬Android软件键盘请求。

## D：漫画详情、章节选择、阅读预览（detail）

所有者：本端应用状态。实体键为bookId+chapterId。章节显示顺序固定并明确，范围选择锚点指向ID；开始新的作品选择时清除旧作品选择。普通点击打开本地阅读预览；假阅读状态按作品隔离。

| ID | 前置 → 用户动作 | 状态、反馈及验收 |
|---|---|---|
| D1 | 普通详情 → 显式选择/长按章节 → 点选 | 有有效选中ID和计数；普通点击与选择点击分开；取消最后一项回普通模式 |
| D2 | 已选择 → 全选/反选 | 只作用当前可见集合；反选全选集合得到0并退出；不修改下载/阅读状态 |
| D3 | 锚点章节 → Desktop Shift点另一行 / Android长按另一行 | 包含端点的当前有序范围被选择；反向范围成立；不误触发阅读、滚动后无串选 |
| D4 | 普通详情 → 继续阅读FAB | 有候选时进入对应本地阅读预览；无未读候选时不提供无效继续动作；选择模式隐藏FAB；返回详情并保留阅读位置 |
| D5 | 选择混合下载状态 → 移除 | 确认写明已选/适用数、本机下载影响；取消集合不变；确认只删除快照内已下载对象，反馈实际数和跳过数；另一本不变 |
| D6 | 选择/确认/阅读预览 → Escape或顶栏返回 | 确认先取消；选择先退出；预览回详情；普通详情才回书架，恢复L3 |

D1/D2/D5对应UI-08/09/17；D4对应UI-07/27。当前Desktop ChapterSelectionState只有toggle/selectAll/clear，故D2反选与D3是PROJECT_POLICY/HTML_ADAPTER要求，不能宣称已在production实现。范围规则：保留已有选择并加入锚点至目标的闭区间，锚点失效则以目标单项建立锚点；此具体规则应与SOURCE差异明确记录。

## S：同步及设置（modal内部导航）

入口：书架顶栏同步。所有者：本端面板会话与本端startup/periodic已提交值。无密码/授权/任务状态样本；不扩展至真实同步。现有产品双端底部面板语义保留，不把Android平板居中规则直接覆盖它。

| ID | 前置 → 用户动作 | 状态、反馈及验收 |
|---|---|---|
| S1 | 同步 → 设置 → 切换两个开关 | 本端立即提交；另一端不变；关闭重开值保留；刷新重置有说明 |
| S2 | 设置 → Escape / 返回；同步 → Escape | 第一次回同步，第二次关闭；关闭/遮罩可直接关闭整个面板；关闭焦点回书架触发器 |
| S3 | 任一模态打开 → Tab/Shift+Tab → 取消/关闭 | 入焦；完整循环不入背景；背景inert；取消回触发器且无业务副作用，确认框同样适用 |
| S4 | 输入/选择/设置/滚动中 → 切深浅主题或窗口尺寸 | 已声明状态及滚动不变；本端输入节点保持，不抢对端或外层主题按钮焦点；主题令牌生效，重新打开不会回放旧通知 |

S1/S2来自现有批准DEMO及DesktopSyncPanel包装；S3/S4是PROJECT_POLICY的浏览器可执行细化。面板最大宽560、高720，受视口约束；内容过长内部滚动。

## V：可核验的视觉约束

| ID | 必须验收 |
|---|---|
| V1 | 书架与详情封面按平台实际宽高比：Windows 7:10、Android Book 2:3；保留Material图标原fill/stroke，禁止全局清零描边 |
| V2 | 深浅色primary/surface/onSurface/surfaceContainerHigh使用源码值；深浅截图分别检查文本、禁用、选择、弹层 |
| V3 | Android 320px和Desktop宽窗口无内容横向溢出；200%字体下确认/关闭/返回可到达；实际滚动容器能展示全部内容 |
| V4 | 保存双端书架、章节选择和同步设置/确认的少量截图，注明主题、尺寸、语言和版本；主代理独立看图，不能自封为原生基准 |

V1/V2为SOURCE常量的HTML映射；V3为PROJECT_POLICY；V4为证据约束。其余尺寸若是HTML适配值，应在参考表或原型README说明，不虚构源码出处。

## 通过条件

L1–L4、D1–D6、S1–S4、V1–V4全部有实际证据；可由一个行为测试覆盖多个ID，但不得只检查DOM存在。截图/纯源码检查不能代替行为断言。保留首轮失败和修复记录；最终完整浏览器测试0失败0跳过，脚本语法及git diff检查通过。生产能力、动态色、RTL、原生读屏/IME/系统恢复不在此HTML实验完成声明内。

## 基准纠正记录

V1初稿统一写2:3，后在实现期间经源码独立复核发现Desktop `LibraryComponents.kt`和`MangaDetailComponents.kt`显式使用0.7f，Android `MangaCover.Book`才2/3。故按SOURCE纠正为分平台比例；没有把成品偏差反向写成要求，也未减少覆盖项。当前Desktop视觉差异是否将来统一需另行产品决策，本轮不修改生产外观。


## 2026-09-30 分类管理子页契约（RI02）

- PROJECT_POLICY：更多 → 分类是直接管理入口；已收藏作品详情 → 更多 → 编辑分类 → 编辑复用普通 `CategoryManagementScreen`。编辑跳转丢弃未确认归属草稿，返回原详情。书架及书架设置不新增管理按钮；分类0由默认投影管理，不显示为自定义分类卡片或归属复选框。
- SOURCE（本轮同批提交）：每次管理页面持有独立 Voyager key 和现有 LibraryScreenModelFactory 创建的 ScreenModel；离页销毁 owner。分类列表来自 GetCategories，拖动投影为会话临时状态，停止拖动只提交一次，失败清投影并回权威值。手柄聚焦后 Alt＋↑／↓调用同一持久化排序动作；这是 Desktop adapter 的键盘选择，不把 HTML 键位提升为上游唯一要求。
- 名称规则沿用上游 trim 与区分大小写的精确重复比较，重命名排除自身。空、重复及未变名称禁止确认并解释；写入失败保留草稿供重试。删除确认显示分类名称，取消不写；正常删除清既有偏好引用并使无归属作品回默认，保留作品。跨 DB／Preference 部分失败恢复由 RI12负责，不声称本批跨存储原子化。
- Escape 每次消费一层：弹窗关闭并还焦，管理子页回上一页，详情归属编辑返回后还焦 More。归属弹窗自定义分类使用受父约束的最多320dp滚动区，末项及编辑／取消／确认保持可达；管理列表留出 FAB 底部空间。
- 验收：`CategoryManagementScreenTest` 的真实 More／详情导航、pointer 拖动、键盘事件、12项原生离屏与 SQLite 测试；相关旧动作、设置搜索和 DI CRUD保护合计73项通过。正式发布运行及硬件证据统一 RI18，不以离屏或 HTML 通过替代。


## 2026-09-30 外观与共享消费者契约（RI03）

本节固定[最终设计第7节](2026-09-30-interaction-final-design.md#7-全部主题与外观页面)的原生 AP01–AP15／T01–T08，覆盖产品及真实消费者；不继承上文HTML样本的无网络、刷新重置或三个页面边界。完成证据统一见[本轮迭代证据](../../evidence/desktop-interaction-iteration-2026-09-30.md)，本节不另行声明通过。

- SOURCE：共享 AppTheme／AppThemeColorScheme／BaseColorScheme 是两端色表权威，固定身份与顺序沿上游归档。主题卡114dp、9:16、边框4dp、外圆角17dp／内13dp；卡内虚拟 Android Book 封面2:3，产品真实 Desktop 书架／详情仍7:10。选中使用匹配 RoundedFilled.CheckCircle；主题模式消费原生三段单选值，不能复制演示CSS或第二套颜色JSON。
- PROJECT_POLICY：更多 → 设置 → 外观；窄屏进入普通子页，宽屏沿统一设置宿主双栏。只有当前页持有标题与返回。模式、卡片、语言和即时开关提交真实偏好，更新主题／语言不能重建 Voyager 导航或业务owner，不丢查询、选择、草稿、焦点及滚动。旧列数偏好保留到RI05迁移并接有效搜索入口。
- Windows平台边界：无真实动态色provider时隐藏可用MONET卡；旧MONET原值保留，渲染安全回退DEFAULT，外观显示原因。手动LIGHT隐藏整行纯黑但保留值，SYSTEM即使当前浅色也显示，只有实际深色消费纯黑覆盖。不能新增壁纸服务或改系统外观设置。
- 语言目录由打包的非空moko资源派生，显示默认及自称名称排序；不永久锁死数量、不复制DEMO语言JSON。选语言留在语言普通子页，资源及日期即时刷新，存储／locale应用失败沿DesktopLocaleAdapter回滚与协调反馈。实际生成目录必须跟随资源输入变化，不能在Gradle配置阶段写入产物。
- 平板四值沿共享TabletUiMode身份。DesktopUiDependencies持有唯一启动快照，Home／Settings／Detail通过同一窗口adapter判断资格；自动最短边阈值竖屏700dp／横屏600dp。保存只影响下次启动并明确提示手动重启，当前owner布局不变，不改变物理窗口、不kill运行任务。RI03详情仅补真实资格消费，RI08继续完成450dp／65%公式及独立滚动。
- 日期六值沿UiPreferences，默认空值保持locale默认格式；真实章节消费所存格式与相对时间。相对时间用本地日历日：未来1–7日、今天、过去1–6日，其余绝对；关闭全部绝对。共享日差规则被Android包装和Desktop实际组件消费，跨午夜／时区／DST／闰日不用24小时除法。无上传日期不伪造今天。
- SOURCE描述语义沿Android MangaInfoHeader.descriptionAnnotator：文本换行有效，HTML标签按字面文本保留；关闭图片以可点击图片URL和替代文字表示，不产生图片请求。解析flavour、marker及描述AST片段由现共享模块持有，Android／Desktop共同消费；平台Material样式、LinkAnnotation／inline图标和图片adapter各自适配。Desktop图片经现Coil真实源typed模型及认证网络链，成功真实显示、失败保留文字与可点击链接，同mounted详情响应开关；没有新HTTP客户端或第二份parser。
- 日期／平板弹窗只有标题、选项和取消；选新值即时保存并关闭，选当前值保持，失败回权威旧值且反馈。Escape／遮罩／取消只关一层且不写，入焦及Tab／Shift+Tab留在弹窗，关闭回触发器。320dp和200%字号下所有选项、取消与横向主题条可达。
- 固定视觉证据覆盖14个Windows可用静态主题的浅／深／纯黑，检查实际ColorScheme全部角色与真实原生组件。截图注明系统、Compose／Skiko、窗口、density／fontScale和来源，主代理独立看图；离屏和HTML不能代替RI18正式发布及运行验收。

## 2026-09-30 四布局与续读契约（RI04）

本节在RI04实现前固定原生验收，沿[最终设计4.2](2026-09-30-interaction-final-design.md#42-展示与直接能力l01l08)及书架A01–A06／A10–A11；上文三页HTML样本不替代本节真实DB／Lazy／导航／图片链路。

- SOURCE：沿LibraryScreenModel／LibraryPageWiring和嵌套LibraryNavigationHost，实际getChaptersByMangaId的scanlator filter与nextUnreadChapter构造Reader请求。普通续读最早适用未读优先于旧已读同步章，全已读隐藏；只有目标匹配才附同步page／snapshot。Desktop网格／详情封面7:10，列表图片48dp方形；AndroidBook2:3。封面沿已认证typed源Coil、自定义文件解析与coverLastModified版本键。
- PROJECT_POLICY：四布局各有真实右侧可拖Scrollbar和唯一Lazy滚动所有者。当前Library owner以稳定分类ID、有效作品ID及有界offset保存临时浏览位置，切类与详情push／pop恢复；改变排序／布局／列数／尺寸以作品锚点而非旧下标恢复，被删除／过滤锚点和删除分类有明确有界回退。位置不写偏好或跨进程承诺，last_used_category既有索引协议不随本批改变。
- PROJECT_POLICY：1000作品真实pointer拖动至尾／回顶，不启动更新。布局重排及返回保持query／筛选／有效选择与导航owner；仍通过原动作入口操作作品，不用测试专用假滚动容器。
- PROJECT_POLICY：四布局与详情消费同一个custom-priority／typed／版本化cover request；自定义替换立即更新、源版本变化不压过自定义、删除回最新源，同URL版本变化能产生新key。临时文件、真实Coil请求／绘制与磁盘失败保留／反馈验证，不另建cache或改变源／代理策略；重新owner可读现已保存cover。
- 文件失败边界：DesktopCustomCoverStore在封面目录暂存完整数据后原子替换，写入中途失败保旧文件并清理暂存；原子替换不可用时报告失败，不降级为直接覆盖。文件替换与数据库版本失效仍是两个边界，不宣称跨存储原子性。实际Windows写入／删除权限和半写故障有测试；POSIX权限分支须在RI18 macOS运行中验证。
- SOURCE角标：四布局未读使用secondary／onSecondary，下载使用tertiary／onTertiary；用独立角色期望检查实际背景与文字像素，保持网格7:10与列表48dp尺寸。
- PROJECT_POLICY：继续按钮开关与角标开关独立。角标隐藏仍按真实适用未读章节提供可执行入口，目标匹配的同步页码保留；陈旧／被过滤／外部恢复不能伪造普通Reader目标，全已读不强加入口。选择模式的小按钮执行既有选择动作，绝不push Reader；真实按钮→嵌套Screen导航及chapterId／index／page／snapshot必须有集成证据。
- PROJECT_POLICY：唯一自定义分类仍显示标签，唯一默认0不强加；0／1／多分类、显示开关、搜索与重排沿共享语义。续读、标签、封面、滚动是本批边界，统一面板／列数迁移、Ctrl／Shift和详情完整布局由后续原责任批次完成。
- 验收固定为四布局真实事件＋位置恢复、文件／Coil、真实Reader请求、标签／比例，且深浅主题下边界一致。缺少真实wiring或失败处理不视为通过；原生视觉注明来源／窗口／density／fontScale，最终发布runtime仍在RI18。
