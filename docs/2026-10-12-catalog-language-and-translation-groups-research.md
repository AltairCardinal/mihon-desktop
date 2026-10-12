# 通用漫画搜索的语言与翻译组识别研究

日期：2026-10-12。初始研究代码基线：`c2da62a163`。范围：跨图源的中文优先语言识别、翻译组提取、标签显示和筛选，包含 Android 与 Desktop。本文保留资料调研、源码核对和离线实验依据；后续产品接线与逐项验证以[实施计划](roadmap/2026-10-12-catalog-filter-implementation.md)为准，合成实验统计不能替代产品测试或真实来源准确率。

建议沿用现有语言断言与搜索链路，增加作品版本级的可解释证据。明确语言标记和翻译署名能覆盖一部分中文版本；中文译名、纯汉字标题或单一中文 OCR 都不足以保证正文是中文。筛选必须保留未知、冲突和未扫描范围，疑难条目按需检查正文并允许用户更正。翻译组是版本署名，不等同原作者、创作社团或上传者；不同译本继续独立保留，不自动合并或删除“重复作品”。

## 需求及完成边界

| 原始需求 | 通用目标 | 本轮证据与剩余边界 |
|---|---|---|
| 图源没有语言筛选也能筛中文 | 应用侧按阅读语言筛选，不依赖扩展原生筛选；中文优先，同时保留其他语言和未知 | 双端原生搜索已接共享规则，真实图源精度尚未测定 |
| 识别显式语言和隐含中文翻译线索 | 覆盖语言标签、翻译组、双语标题、正文对白，并区别证据强弱 | 60 个合成边界样本和 7 张普通对白样本；不能据此宣称任意来源“精确识别” |
| 翻译组作为标签显示和筛选 | 搜索卡片／列表显示署名，筛选可组合 | 本批提供派生标签及过滤选项；独立详情证据编辑、持久标签和手工纠错未接入 |
| 与作者研究共享信息 | 共用原始标题、角色和证据边界，避免重复解析与作者污染 | 双方已互读设计与实施计划，通过文件分工、共享构建交接；翻译证据保持独立命名空间 |

两类典型输入保留为通用模式：`[创作社团 (作者)] 正题 [Chinese] [某某漢化]`，以及`日文正题｜中文译名 [某某汉化组]`。实验使用自制普通题名和对白，没有访问图源目录、下载作品图片或读取用户书库。

## 首批产品行为与维护边界

图源内搜索 → “语言／翻译组” → 选择中文（含显式简繁变体）及翻译组 → 应用。原关键词与扩展筛选树保留；各语言选项 OR、各组 OR，两类条件 AND。未知语言或未识别组可单独包含。条件只属于当前浏览会话，重置草稿后应用恢复已加载结果，不修改图源原始标题、genre、creator 或章节 scanlator。

卡片／列表显示语言及组名，按用户反馈移除“推断”字样；内部仍保留证据类别、原始证据和冲突状态。`SourceResultMetadata`、`CatalogResultFilter` 共用于两端；标题证据扫描器只识别语言／翻译署名，不解析 creator/circle。原始名称精确保留，只有每个分量都有完整翻译角色的 `×` 联合署名才分别列出，不把简繁或近似名字自动合并。现有 detector 的规则置信值没有按真实来源校准；任意 genre 的来源语义仍可能不可靠，不能据此建立原作语言或作品版本等价关系。

筛选范围是已加载结果。Android 保留原始页项以维持分页边界，再在呈现层按URL去重／筛选；Desktop 保留现有查询 reducer。启用筛选后的下一页由用户显式触发，过滤后暂无匹配不会被当成图源穷尽，也不自动连续抓取。网络错误沿用现有重试／登录路径，清条件不重新请求已加载页。

扫描有界：最多4096个Kotlin字符单位的标题、512个字符单位的单标记、8层括号；不完整／不匹配括号保守弃权。同级冲突不选第一个语言。裸“汉化”提供语言线索而不造组织；纯汉字不直接认定中文，假名不从翻译署名中泄漏到阅读语言。正文OCR只保留本报告实验，未进入产品自动判定；无法保证标题推断等同实际正文语言。

## 当前代码可复用的能力

以下为 SOURCE，位置按上述基线核对；同工作树中另一会话正在实施的作者署名改动不视作已验证能力。

| 环节 | 现有事实及入口 | 本项如何复用 |
|---|---|---|
| 双端搜索 | [SourceMangaSearchService](../domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceMangaSearchService.kt#L12) 调用安装的 source；Android [SharedSourcePagingSource](../app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreenModel.kt#L377) 和 Desktop [SourceBrowseQueryCoordinator](../app-desktop/src/main/kotlin/mihon/desktop/ui/browse/DesktopSourceQueryCoordinators.kt#L73) 共用它与 reducer | 新增应用侧结果投影，继续沿用扩展、代理、登录、限流和错误处理，不直连站点另写客户端 |
| 语言识别 | [MangaLanguageDetector](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/MangaLanguageDetector.kt#L41) 已处理显式字段、genre、假名／韩文、单语来源回退 | 增强这条共享能力；普通搜索和作者发现消费同一结果，不各写一套中文正则 |
| 语言证据 | [CreatorArchiveV2Contract](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/model/CreatorArchiveV2Contract.kt#L295) 已区分 READING/ORIGINAL、证据、确定性、人工裁决、撤回和同级冲突 | 优先扩展现有契约和存储边界，附加原文、规则版本、作用范围与输入指纹 |
| production 接线 | [CreatorDiscoveryService](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CreatorDiscoveryService.kt#L928) 将检测结果转为阅读语言断言；[AuthorsScreenModels](../app-desktop/src/main/kotlin/mihon/desktop/ui/authors/AuthorsScreenModels.kt#L383) 已有语言确定性筛选语义 | 新增证据不能破坏作者发现、展示分组和关注的既有语义；本轮不顺带改通知政策 |
| 图源语言 | [CatalogueCreatorDiscoverySourceAdapter](../domain/src/commonMain/kotlin/tachiyomi/domain/creator/service/CatalogueCreatorDiscoverySourceAdapter.kt#L66) 区分 catalogueLanguageTag 与 readingLanguageProfile | `Source.lang=zh` 不能自动证明每个作品正文都是中文；多语来源尤其如此 |
| 书目元数据 | [SManga](../source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/model/SManga.kt#L8) 和 [Manga](../domain/src/commonMain/kotlin/tachiyomi/domain/manga/model/Manga.kt#L10) 没有通用的书目级翻译组字段，已有 memo | 通过有语义约定的可选能力或 memo adapter 取得类型化证据；不能把任意 memo 或 genre 视为已可信 |
| 章节翻译组 | [Chapter.scanlator](../domain/src/commonMain/kotlin/tachiyomi/domain/chapter/model/Chapter.kt#L15)、[章节排除表](../data/src/commonMain/sqldelight/tachiyomi/data/excluded_scanlators.sq#L1) 和既有选择对话框 | 复用交互习惯和章节证据，但书目筛选不写入章节排除表，不改变阅读器可见章节 |

现有检测器对标题与简介里的任意假名给出日语线索，因此“保留日文原标题的中文译本”需要新的阅读语言证据优先判断。已有 [MangaLanguageDetectorTest](../domain/src/jvmTest/kotlin/tachiyomi/domain/creator/service/MangaLanguageDetectorTest.kt#L67) 则明确保护“纯汉字不直接判中文”，应继续保留。

现有投影是单个 `tag`，同优先级不同语言会成为冲突；它尚不能完整表达真实双语正文。扩展时须把“多语言内容”与“相互矛盾的断言”分开，不能直接往旧投影塞两个 tag 就称支持双语。当前 STRUCTURED_METADATA 会投影为 CONFIRMED，新增标题推断不得冒用该类别；这里的 CONFIRMED 是现有契约语义，并非经过正文认证。

## 中文优先的识别规则

本节为拟实施的 PROJECT_POLICY。Unicode 明确说明汉字编码本身不能可靠区分中文、日文或韩文；需要结合文本语境。[Unicode 中日韩文字 FAQ](https://unicode.org/faq/han_cjk.html)

| 线索 | 示例 | 建议结论及防误判 |
|---|---|---|
| 明确的作品阅读语言字段 | `language=zh`、类型明确的版本语言 | 最优先的自动证据，但保存来源；字段必须指阅读语言，不是原作语言、站点界面语言或作者国籍 |
| 完整语言标签 | `[Chinese]`、`【中文】`、`[中国翻訳]`、`[zh-Hans]` | 支持中文判断，显示“标题标注”；全半角和大小写仅在解析视图归一化 |
| 明确简繁标签 | `[简体中文]`、`[繁體中文]` | 可表达 `zh-Hans`／`zh-Hant`；只写 Chinese 时保留 `zh`，不猜地区或字形 |
| 明确翻译署名 | `[青叶汉化组]`、`[星空☆漢化]`，前缀或后缀 | 提取署名，作为中文强线索；原日文标题仍有假名不能覆盖它，但署名本身不是正文语言的绝对证明 |
| 日文与中文译名并列 | `旅の記録｜我们的旅行日记` | 支持“疑似中文”；可能只是站点翻译了题名，单靠这点不进入严格正文确认集合 |
| 纯中文句式题名 | `我们一起去看海` | 可作弱线索；标题语言与正文语言分开，不能擅自推断译者 |
| 纯汉字或名字 | `東京旅行`、`山田太郎` | 未知；同一个可见标题可以对应中文或日文正文 |
| 模糊简称 | `[CN]`、`[CH]`、`[繁]` | 未适配时未知；只在该来源语法已核验后解释，避免把社团缩写或题名当语言 |
| 否定、招募、请求 | `[未汉化]`、`[非汉化]`、`[求汉化]`、`[汉化组招募]` | 不能命中中文，也不能据此生成翻译组 |
| 英文组名或普通 group | `[Azure Scans]`、`group=星舟社` | 名称本身不决定语言或角色；有明确翻译角色时可收录署名，仍不自动判中文 |
| 原版、机翻与修正版 | `[RAW]`、`[中文机翻]`、`[修正版]` | RAW 不等于日语；机翻是制作方式，修正版是版本信息，分别表达，不冒充翻译组 |
| 冲突或不完整输入 | `[Chinese] [Japanese]`、截断括号 | 冲突／未知；取得完整详情后再判断，失败保持原状态 |

语言代码采用 BCP 47；中文不限于简体，`zh` 匹配中文总类，`zh-Hans` 和 `zh-Hant` 仅在有字形证据时细分。未知字形不自动进入“仅简体”或“仅繁体”。[W3C 语言标签说明](https://www.w3.org/International/articles/language-tags/)

翻译组词典可补充没有“汉化”后缀的已知署名，但必须带来源和角色依据；不建立“凡名字像汉化组就是中文”的全局名单。公开个人／社团别名不能自动转为翻译组别名。词典、规则或模型输出都可撤回，不能覆盖用户已保存的更正。

## 翻译署名的结构与身份

每条署名至少保存原始名称、原始片段／字段、角色、来源作品键、证据类型和版本；规范化检索键与展示原文分开。作品可有零个、一个或多个翻译署名。个人翻译、汉化组、联合制作、扫描和转载角色分别表达。

- `[青叶汉化组×白云汉化组]`：当来源允许此合作语法且每段角色明确时，保留两个署名及联合关系；选择任意一个组都能命中该版本。
- `[青叶×白云汉化组]`：存在一个完整品牌名与两组合作两种解释，先保留完整署名候选，不任意按 `× / & +` 切开。
- `[翻译：Azure Team]`：能说明翻译角色，不能说明目标语言一定是中文。
- 同一组简繁、空格、大小写或改名：经有依据的别名确认后再归并；不能用 NFKC 或简繁转换静默改变全局身份。
- 一部连载不同章节由不同组制作：详情显示“包含这些章节翻译组”，不能宣称整部作品全部由某一个组翻译。

[ComicInfo 文档](https://github.com/anansi-project/comicinfo/blob/main/DOCUMENTATION.md) 将 `LanguageISO` 定义为书籍语言，并将 `Translator` 用于翻译者／翻译组；`Teams` 是故事中的团队，`ScanInformation` 是扫描信息。支持这种角色分离，但不代表所有 ComicInfo 版本或现有 importer 已支持 Translator；实施时核对具体 schema 与读写路径。

## 离线标题实验

实验入口：[probe.py](evidence/catalog-language-2026-10-12/probe.py)。逐项输入、期望署名、输出和指标：[title-results.json](evidence/catalog-language-2026-10-12/title-results.json)。Python 3.14.4，60 个自制样本，其中 33 个设定为中文正文、27 个为非中文。

这些样本用于检验边界，规则和样本由同一研究者编制，没有独立留出集；设定的正文语言是合成 ground truth，并未下载对应作品。因此下面的数值只描述这个实验集，不能作为真实来源准确率或上线承诺。

| 方法 | 中文真阳性 | 中文误判 | 漏判或暂不判中文 | 非中文真阴性 | 精确率 | 召回率 |
|---|---:|---:|---:|---:|---:|---:|
| 标题出现汉字 | 27 | 18 | 6 | 9 | 60.0% | 81.8% |
| Chinese／中文／汉化等子串 | 22 | 12 | 11 | 15 | 64.7% | 66.7% |
| 完整括号、角色／否定排除、冲突保留 | 25 | 2 | 8 | 25 | 92.6% | 75.8% |

保守规则有 25 项处于未知或冲突；它们不是“已证实非中文”。表中的二分类统计把严格筛选未选中的中文计为漏判，以免用弃权隐藏召回损失。署名输出在 60 项上符合预设的保守提取规范，但没有据此测得真实翻译组识别率。

两项误判分别是 `wrong_site_tag`（标题写 Chinese，合成正文设定为英文）与 `credit_only_chinese`（中文制作组署名，正文仍是英文）。两类输入与正确中文样本的可见信息可完全相同，增加正则无法消除这类不可观测歧义。

八项漏判／弃权覆盖：只有译名、只有简繁中文句子、纯汉字、截断、语言冲突、未登记署名、拼音。后续应通过正文证据或人工更正提高覆盖，不能为了提高这组样本召回率把规则改成“有中文就通过”。

脚本只验证研究假设，不调用或替代 production detector；它不具备来源字段接入、原始 span 映射、持久化、并发、分页或 UI。正式实施必须在 Kotlin production 上重新执行行为红绿测试，不能把本 Python 探针挂进产品代替共享实现。

## 本地 OCR 实验及选型

使用已安装的 Windows.Media.Ocr，语言包为 `en-US` 和 `zh-Hans-CN`，OS 版本 `10.0.28000.0`。通过 Pillow 生成 7 张 1200×680 的普通文本气泡图，共运行 14 个“样本×语言包”组合。原始识别文字与耗时保存在 [ocr-results.json](evidence/catalog-language-2026-10-12/ocr-results.json)，调用脚本为 [ocr-probe.ps1](evidence/catalog-language-2026-10-12/ocr-probe.ps1)。

| 样本及使用的识别包 | 去空白后的字符编辑错误 | 观察 |
|---|---:|---|
| 简体对白，中文包 | 0/35 | 三句清晰横排文字完整识别 |
| 繁体对白，中文包 | 0/35 | 本样本识别成功，不代表全部繁体漫画性能 |
| 英文对白，英文包 | 0/73 | 本样本完整识别 |
| 日文对白，中文包 | 22/36 | 假名被错读或漏读，形成看似汉字的结果 |
| 中日混合对白，中文包 | 15/44 | 中文行较稳定，日文行失真 |
| 纯汉字封面，中文包 | 0/8 | OCR 文本完全正确，仍不能从“東京旅行 山田太郎”确定正文语言 |
| 英文对白加中文制作署名，中文包 | 1/59 | 中文署名可准确识别，但正文是英文，必须区分文本角色 |

OCR 调用记录约 7–30ms，只包括本机清晰样本的 RecognizeAsync，不包括下载、正文区域检测、模型安装、语言判断或真实页面端到端耗时。图片没有复杂背景、竖排、手写、低分辨率与弯曲文本；没有日语识别包，因此未进行多语种公平对照。本机 OCR 首次在 PowerShell 7 无法加载 WinRT 类型，随后用 Windows PowerShell 5.1 成功运行；交付脚本已明确运行环境。

Windows OCR 按本机语言包创建引擎，缺少对应语言时不能假设支持。[Microsoft OcrEngine 文档](https://learn.microsoft.com/en-us/uwp/api/windows.media.ocr.ocrengine.trycreatefromlanguage?view=winrt-26100)

| 方案 | 一手资料确认的能力 | 本研究的取舍 |
|---|---|---|
| Windows OCR | 本机语言包与 WinRT 接口 | 已实际试验，适合作为预研工具；缺日语包且平台专有，不能作为双端唯一实现 |
| PP-OCRv5 | 一个识别模型覆盖简中、繁中、英文和日文，并考虑竖排等场景 | 候选正文识别引擎，需另测漫画实际输入及 Android/Desktop runtime；本轮没有下载或运行，官方指标不是本项目指标。见 [PaddleOCR 识别文档](https://www.paddleocr.ai/v3.3.1/en/version3.x/module_usage/text_recognition.html) |
| ML Kit OCR 与语言识别 | 支持中文、日文等文字；语言识别支持 `und` | Android 候选 adapter。整段 identifyPossibleLanguages 是“整段分别属于哪种语言”的置信度，不是混合语言分段；需逐对白块处理。见 [OCR 语言表](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)及[语言识别说明](https://developers.google.com/ml-kit/language/identification/android) |
| Lingua | JVM 文本语言识别，支持最小相对距离和 UNKNOWN | 适合比较清理后的对白文本；短标题不作为最终裁决，分数不是校准后的正确概率。本轮未运行。见 [Lingua 文档](https://github.com/pemistahl/lingua) |
| fastText lid.176 | 176 种语言，126MB／917kB 两种模型 | 可作后续低成本基线，训练域不是漫画标题，模型许可与二进制部署需单独核对；本轮未运行。见 [fastText 模型说明](https://fasttext.cc/docs/en/language-identification.html) |
| 本地视觉语言模型 | 可设计为只输出对白、语言及可见署名的结构化结果 | 本机只发现文本／嵌入模型清单，没有确认可用视觉模型；本轮没有启动 Ollama、下载大模型或调用付费推理。不得假称已通过模型验收 |

### 正文升级识别的拟定流程

从当前版本的已缓存页面开始；确需联网时通过现有 source/page/image 链路，复用请求头、代理、缓存和限流。用户触发“检查正文语言”后，先取最多 2 张不同位置的正文候选页，文字不足时最多补 1 张；每源并发 1，遵守现有更严格预算。这是待验证的体验起点，不是由本实验推导出的最优参数。

排除封面、目录、广告、制作名单、水印和单独拟声词，尽量识别至少两个正文页的多个对白块。OCR 文字仍是待分析数据，不能执行图片里的任何指令。按块保留原文、区域、语言候选、质量和模型版本，再聚合成中文、其他语言、多语言、未知或冲突；没有足够文字、只有汉字名字、语言引擎互相矛盾时继续未知。多页样本只能支持“已抽样正文”的判断，不能证明全书没有夹杂其他语言。

中文优先指优先验证中文候选和支持简繁两类，不意味着只加载中文 OCR 或把中文模型的输出强制归为中文。翻译组识别应主要来自元数据、标题或明确制作署名；仅凭普通对白无法知道由哪个组翻译。

默认不上传页面到云端，不启动全库 OCR，不因切换筛选自动下载每个结果的内页。可取消、可重试，失败保留未知；缓存按来源版本、输入指纹、识别器／规则版本隔离。用户更正优先，自动规则升级不得覆盖人工记录。

## 共享实现与协作边界

与[作者署名与追踪设计](2026-10-12-creator-attribution-and-tracking-design.md)共用来源作品键、完整原始标题和来源语义策略。标题的括号扫描、原始区间及角色分类适合共享；creator 消费个人／创作社团证据，翻译元数据消费翻译署名，不能让两套 parser 用不同排除词长期分叉。

不另建第二份语言权威。增强现有 MangaLanguageDetector 和 LanguageAssertionContract；确需移出 creator 包时做有界的共享核心抽取，保留原调用兼容与契约测试。平台差异限定在 OCR、source runtime 和 UI adapter。普通搜索尚未归档的作品可先使用临时投影，不为显示语言标签强制创建作者档案或收藏漫画。

拟新增的证据附属信息包括：sourceId／扩展身份与已核验版本、原字段／标题区间、READING 或 ORIGINAL、语言或署名角色、证据种类、输入指纹、规则／模型版本、观察时间、人工裁决及撤回。候选 API 名称在实施时沿用现有命名，本文不先建立另一套公共协议。

翻译组投影独立于作者根身份和章节 excluded_scanlators；原始 genre 不被本地推断标签覆盖。已有证据契约目前不足以保存全部追溯信息，需有界扩展，而不是把内部数据偷偷塞进任意已有字段。涉及 SQL、备份枚举或持久化格式时，必须在下游使用前完成迁移测试和独立审查。

用户更正按来源作品版本持久保存，不自动传播到同名书、其他译本或镜像源。自动缓存可重建，人工更正需纳入备份兼容；尚未定义同步协议前明确“仅此设备”，不能声称双端共享代码意味着已同步数据。

协作对象为 `codex://threads/01a12c15-659a-7001-880d-f180ad4a6840`。最初通过本机队列建立过一次接口联络；此后按用户要求，以双方研究报告和实施计划交换范围、发现和 Gradle 执行权，不以往返消息代替文件。其在本工作树的未提交 production 改动全部保留，翻译证据保持独立命名空间。研究提交 `292eaf5682` 只包含本文和实验目录；产品提交范围及验证见实施计划。

## 搜索分页和成本控制

源码中的 [loadPageResult](../domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceMangaSearchService.kt#L17) 将空列表转为不含 hasNextPage 的 Empty；[SourceQueryReducer](../domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceQueryState.kt#L122) 将此视为无结果／结束。新增筛选若在这个判断前删掉非中文项，会把“本页没有匹配”误报为“图源没有更多”。本轮是代码风险分析，没有运行现场复现。

保留两层状态：原始请求的 page／cursor／hasNextPage，以及过滤后可见项／未知项。筛选不改变来源返回的耗尽判断；同一来源 URL 去重仍沿用现有 reducer，标题相似或同一作者不构成相同版本。

一次补页建议最多 3 个原始页或 20 秒，先到者暂停；复用现有更严格的源预算，不能按语言和翻译组各开一套额度。达到预算时显示“已检查 N 项，找到 M 项，仍有更多”，提供继续检查；只扫描到一部分时不能显示全站数量。不要直接调用没有页数／时限上限的 [searchAllPages](../domain/src/commonMain/kotlin/tachiyomi/domain/source/service/SourceMangaSearchService.kt#L47)。

默认仅对已获得标题和元数据做零额外请求判断。详情补取和正文识别是独立、可见且有界的动作；已缓存详情优先，同一版本去重请求。403／登录、429、500、解析失败、用户取消和真正零命中分别反馈。切换作者词、图源或过滤代次后，旧详情／OCR 返回不得污染新结果。

原生图源已有类型明确的语言筛选时优先复用，但能力由已核验扩展身份／版本决定，不能靠本地化控件名称猜测。原生筛选表示请求约束，结果仍可能需要证据展示；应用侧筛选继续可用。

## 双端用户界面契约

以下为完整研究方向的 PROJECT_POLICY，遵循 [Desktop UI 实施规范](design/mihon-desktop-ui/README.md)及现有组件。首批原生实现范围以本文“首批产品行为与维护边界”和实施计划为准：已有搜索入口、包含式筛选和派生标签；下表的排除组、详情证据编辑、持久更正及正文检查仍是后续方向。没有 HTML_ADAPTER。默认不改用户当前结果集，主动选中文后才过滤。

| 操作路径 | 行为与反馈 |
|---|---|
| 图源搜索／作者作品结果 → 筛选 | 应用侧“阅读语言”和“翻译组”始终可进入，即使图源不提供原生过滤器；与原生条件分组呈现 |
| 阅读语言 → 中文 | 匹配有足够中文证据的版本，简繁默认都含；“同时显示待确认”有明确开关，开启后待确认项单独标记，不能伪装为已确定中文 |
| 语言 → 日语／英语／其他／未知 | 通用语义一致；多语言版本命中其包含的语言，冲突仍保留待确认，不因中文优先改写事实 |
| 翻译组 → 选择一个或多个组 | 同类多选取 OR；与语言条件取 AND。未知组单独选择。排除任意选定组时，带该组的联合译本也排除，并在面板解释 |
| 搜索卡片／列表行 | 显示一个语言标记与精简署名，多个组可用“+N”；未知不堆多枚警告。标签不遮挡标题、阅读／下载状态和原有点击区 |
| 作品详情 → 语言／翻译署名 | 展示完整署名、依据、待确认状态；点击组在当前来源打开带该组条件的结果，不把组名直接当题名关键词 |
| 详情 → 更正语言或翻译署名 | 可指定、移除错误署名或恢复自动判断；保存有明确成功反馈，取消不写；不更改原始标题、作者、收藏和阅读进度 |
| 未知条目 → 检查正文语言 | 展示正在检查、成功／仍不确定／失败，允许取消；普通阅读入口始终保留 |
| 筛选无可见结果但仍有来源页 | 显示“已检查范围暂无匹配”，提供继续检查／包含待确认／清除条件，不能直接宣称全站无结果 |
| 从详情返回／关闭筛选 | 保留查询词、原生条件、应用条件、滚动和焦点；Desktop Escape 每次关闭一层，Android 返回先关弹层 |

Desktop 当前 [SourceBrowseScreen](../app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt#L349) 用原生 filters 是否非空决定按钮显示，且 [onApply](../app-desktop/src/main/kotlin/mihon/desktop/ui/browse/SourceBrowseScreen.kt#L401) 会清空 query。通用筛选不能照搬这两个条件，否则无原生筛选的来源没有入口，或筛语言时丢掉作者搜索词。

同语言不同组的版本仍同时显示，用户可以选择喜欢的组；不引入自动“最佳译本”、下载删除、版本合并或作者关注迁移。组可见数量只针对当前已检查集合，改变过滤不改变作品身份。

## 后续实施批次与固定验收

当前实现以[通用筛选实施计划](roadmap/2026-10-12-catalog-filter-implementation.md)为准，共享识别与双端搜索接线已完成本批50项focused验证（共享25、Desktop8、Android17，分轮证据）。下表保留完整研究方向；持久更正、正文 OCR 等升级不能因列入研究建议就视为已经实施。验证范围按实际行为选择 focused tests，不自动执行全量测试或发布。

| 批次 | 内聚修改与依赖 | 最小充分的验收与退出边界 |
|---|---|---|
| A 共享证据与保守识别 | 在现有 detector／断言上增加标题片段、语言／署名角色、冲突与双语表达，协调作者解析接口 | 先红测真实 Kotlin 实现：简繁、否定、纯汉字、双语、截断、联合组、来源语言≠正文语言；复验作者语言契约，不启动历史全库重算 |
| B 双端搜索筛选闭环 | 依赖 A，接入现有 search service／reducer，保留原始分页；双端入口、卡片、详情、组过滤和无结果反馈 | 真实点击→条件→结果；首原始页全被过滤而第二页命中、连续零可见页、预算、旧代次、取消、重试；导航／DI 接线和受影响平台一条原生路径 |
| C 持久更正与恢复 | 依赖 A、B，按作品版本保存语言与翻译署名更正，接入既有备份边界 | 真实 SQLite、旧格式恢复、撤回、刷新不覆盖人工、跨版本不串用；数据格式须独立审查后启用，不顺带定义新同步协议 |
| D 正文升级识别 | 依赖 A–C，选择通过真实样本对照的 OCR adapter，接 production page/image 获取和缓存 | 模型可用／缺失、正文／署名分离、竖排、简繁／中日／英文、多语、无字页、取消、源错误和用户更正优先；没有合格模型时保持未知，不挂假成功 |

A、B 的搜索闭环已通过本批日常 focused 验证；完整详情与持久化方向并未因此完成。D 的准确度不能作为未实现时就宣称“正文精确中文识别”的理由。初始研究阶段使用 1 个只读架构研究子代理；后续实施使用 2 个实施子代理，主代理对共享识别作一轮独立审查并完成限定修复复验，详见实施计划。

优先扩展已有的 `MangaLanguageDetectorTest`、`CreatorArchiveV2ContractTest`、`SourceMangaSearchServiceTest`、`BrowseSourceScreenModelBehaviorTest`、`DesktopSourceQueryBehaviorTest`、`SourceBrowseCanonicalResultWiringTest` 和 `SourceBrowseFilterParityTest`。若改变 HTTP／详情／图片适配，增加 MockWebServer 成功、空／缺失、403／429／500 和畸形响应；仅规则单测不能代替 production wiring。

代表性验证集应按“来源与版本”隔离开发集／留出集，包含正文人工核验的普通漫画样本，并按同一原作聚类分割，防止不同译本泄漏到两侧。分别报告严格中文精确率、中文召回率、未知／冲突占比、组提取及身份准确率、每命中的请求数和延迟；简繁、无显式标签及中日混合单独报告。

建议将严格中文精确率至少 99% 作为正式能力的候选门槛，仍需用户接受覆盖率取舍；例如独立抽查至少 300 个“被判为中文”的版本且零误判，单侧 95% 二项下界才约到 99%。这不替代逐来源、负例与未知覆盖，也不能套用到当前合成集。现阶段没有满足此门槛的证据，不能承诺 100% 或“已精确识别”。

## 研究阶段复现与核验

以下命令只执行研究探针，不运行 Gradle、不调用图源、不更改应用数据。标题实验只需 Python 标准库；渲染依赖本机已安装 Pillow 和指定 Windows 字体，OCR 依赖已安装的系统语言包。`$sampleDir` 可换成任意专用临时目录。

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:PYTHONDONTWRITEBYTECODE = '1'
$sampleDir = Join-Path $env:TEMP 'mihon-catalog-language-probe'
$evidenceDir = 'docs/evidence/catalog-language-2026-10-12'
python "$evidenceDir/probe.py" --render-ocr-dir $sampleDir
if ($LASTEXITCODE -ne 0) { throw 'Probe failed' }
$ocrOutput = Join-Path $sampleDir 'ocr-results.json'
& 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' -NoProfile `
    -File "$evidenceDir/ocr-probe.ps1" -ImageDirectory $sampleDir -OutputFile $ocrOutput
if ($LASTEXITCODE -ne 0) { throw 'OCR failed' }
python "$evidenceDir/probe.py" --ocr-results $ocrOutput `
    --output (Join-Path $sampleDir 'title-results.json')
if ($LASTEXITCODE -ne 0) { throw 'Result aggregation failed' }
git diff --check -- docs/2026-10-12-catalog-language-and-translation-groups-research.md `
    docs/evidence/catalog-language-2026-10-12
```

研究阶段（`292eaf5682`）实际执行：60 项标题对照、7 图×2 包的真实 OCR，并在增加 CER 汇总后复算一次相同标题范围；识别规则未为结果“变绿”修改。最后核对指标、文档链接和 UTF-8，执行限定文件的差异检查。该阶段没有运行产品测试、Gradle、应用交互或正式／预览构建，实验结果不作为它们的通过证据。后续 Kotlin 红绿与双端接线验证单独记入实施计划，不能套用本节实验精度。

研究交付为本报告及同目录实验脚本／逐项数据，没有第二份研究报告或状态系统；后续产品实施另有一份计划。逐项 JSON 使研究变更超过 400 行，但该提交范围仅为可复现研究证据；其行数不能解释为后续产品实施规模。


## 2026-10-12 体验反馈修正：中文组合证据与版本署名

用户提供的标题 `代代木千金小姐 [轟媽無修正版] [40010試作型]` 及同组其他作品，补充了明确的角色信息：`轟媽` 是翻译组。登记该名称仅供语言／翻译署名扫描器使用，不能写入 creator/circle、自动关联个人或反推作品等价。

- `無修正` 也见于日文，不能单独作为中文依据；[日本国语词典“無”](https://kotobank.jp/word/無-140186)列有该复合词。`無修正版`／`无修正版` 在本实现中只是版本后缀。完整括号标记或括号外独立词段中的“已确认组名＋版本后缀”才提供中文和组证据；`轟媽無修正版` 提取组名 `轟媽` 并保留原始完整证据。已有显式汉化组名称也支持这两种后缀。未知前缀、嵌入式提及、嵌套作者标记、简介中提及组名均不据此造组。
- 没有明确语言或翻译署名时，增加组合规则：括号外标题含中文词句线索，且有界简介含至少两种不同中文词句，才判为通用中文 `zh`。简繁同词及重复词不重复计数，单纯汉字、单一中文标题或单独中文简介均不足。保留日文原题／名字不会压过该组合证据。
- 组合证据使用 `TEXT_DETECTED`、保留标题与简介原文；结构化语言、同级冲突与明确标题语言仍优先。不会据此猜简繁或译者，也不提升人物关系置信度。读取上限仍为标题4096字符单位、简介前4096字符单位。
- 双端卡片删除“推断”后缀，保留已识别语言和组名；语言未知时标签文字留空并保留标签框；没有增加导航、网络请求、OCR、数据库字段或新的公共 translator schema。筛选仍只作用于已加载作品，未加载的详情简介不会自动请求。

这是对用户提供样例的规则修正，不是正文核验或真实站点准确率认证。词句规则可能漏掉未覆盖表述，也可能遇到中文译名加中文介绍但正文仍为日文的条目；后续仍需正文证据或人工更正处理。红绿测试、双端真实页面和体验包证据统一记录在既有实施计划。


2026-10-12 第二轮界面反馈：双端卡片／列表的未知或冲突语言文字留空，已有翻译组仍显示组名，无多余分隔符。文字组件及背景框继续占位，单行空标签与已识别语言标签保持同样宽高；筛选面板中的未知语言选项继续可用。此变更只改变显示，不改变未知／冲突的识别结果或筛选语义。
