# 有序对象的局部分组与增量认证：工程化匿名研究包

本版在冻结的扫描核心之外补入生成器、会话模型、固定基准和历史成本数据。研究目标是可靠性约束下的
完整请求成本，而非仅增加认证数量。所有业务名称、真实内容、源位置及宿主身份均不属于交付范围。

## 本版从哪里开始

1. 阅读本页、[ENGINEERING.md](ENGINEERING.md) 和 [COST_MODEL.md](COST_MODEL.md)，分清实测、移植和模拟。
2. 阅读 [PROBLEM.md](PROBLEM.md)、[CONTRACTS.md](CONTRACTS.md)；这些约束仍然适用。
3. 运行下方测试与短基准，确认环境；再阅读 [prior/NOTES.md](prior/NOTES.md) 中的既有候选和缺陷。
4. 按 [RESEARCH_TASK.md](RESEARCH_TASK.md) 自主完成一次研究；不需要业务解释，不等待逐项选择方向。

```text
python -m unittest discover -p "test_*.py" -v
python benchmark.py --quick --output run.json
```

完整固定基准：`python benchmark.py --output run.json`。新算法通过候选适配器接入，具体协议见
`benchmark.py` 的接口说明。新运行得到的时间与包内历史观测来自不同执行环境，不能直接拼接或相除。

候选接口的可执行示例（重复接入包内 scoped 策略以检查适配）：

```text
python benchmark.py --candidate benchmark:build_scoped --quick --output candidate-run.json
```

`build()` 返回 `certify(case, target)`、可选 `validate(result, case)` 和可选 `metrics` 字典。
没有标准读集的新证书必须提供自定义有效性检查；两者都没有时不会命中缓存或授权发布。
自定义检查是研究者的证明义务，不是返回 true 就能替代正确性证明。回调是受信本地研究代码，
不是安全沙箱。

默认策略为 baseline、E、scoped、conservative。quick 用12对象及每场景10步，混合事件比例
0/20/100%；完整配置用48对象及100步，比例0/1/5/20/100%。hot/mixed 的计时包含预观察，
所以 `python_wall_ns` 是整个场景批次，不是纯热请求单次延迟。操作微计时和注入服务时间另列。

新增材料：

| 文件 | 用途 | 主要边界 |
|---|---|---|
| `generator.py` | 从共同潜在见证派生事实和可观测知识 | 合成见证不代表自然分布，准确移植范围见 ENGINEERING |
| `runtime.py` | 会话、缓存、知识变化及发布的可执行模型 | 只覆盖明确建模的生命周期，不冒充完整异步宿主 |
| `benchmark.py` | 固定输入、事件轨迹与候选比较 | 本机重测与模拟服务时间分栏 |
| `prior/` | 上一轮已修正的探索性算法 | 并非当前宿主候选的逐行移植，不默认执行无预算枚举 |
| `evidence/` | 中性化历史实测和已知成本 | 无自然触发频率或最终用户时延证据 |
| `MANIFEST.json` | 交付文件大小和摘要 | 用于完整性检查，不是正确性证明 |

以下保留原核心接口说明；其中“没有生成器/缓存容器”的描述仅指冻结的 `reference.py` 本身。
本版新增模块的证据范围以 [ENGINEERING.md](ENGINEERING.md) 为准。

本包是一个匿名算法问题、可执行参考模型和自主研究任务。无需知道它的应用领域，也无需访问任何外部仓库。

目标是在明确约束下发现不同机制的优化，或提出更好的替代算法。现有方案和研究历史都可以被反例推翻；不要把历史推荐当成指定答案。

## 阅读与运行

1. 先读 [PROBLEM.md](PROBLEM.md)，理解目标和信息边界。
2. 再读 [CONTRACTS.md](CONTRACTS.md)，分清必须保持的性质、参考策略和可改机制。
3. 运行本包的测试，阅读 `reference.py` 和 `protocol.py`。
4. 读 [RESEARCH_HISTORY.md](RESEARCH_HISTORY.md)，避免重复发现或重复犯错。
5. 按 [RESEARCH_TASK.md](RESEARCH_TASK.md) 自主完成一次完整研究，不等待提供者逐项选择方向。

运行环境：Python 3.10 或更新版本，仅使用标准库。在本目录执行：

```text
python -m unittest discover -p "test_*.py" -v
```

Windows 终端若需显式设置编码，可在运行前设置 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8` 和 `PYTHONDONTWRITEBYTECODE=1`。无需网络、下载、账户或外部服务。

## 文件与证据层级

| 内容 | 用途 | 不能据此声称 |
|---|---|---|
| `PROBLEM.md`、`CONTRACTS.md` | 研究的目标、硬约束与边界 | 全部约束已被当前程序满足 |
| `reference.py` | 有序扫描与有限入口认证的可执行参考 | 整个宿主系统、全部数据采集过程均已移植 |
| `protocol.py` | 资源与发布协议的合成状态机 | 原有异步运行时已通过等价验证 |
| `test_*.py` | 可复现的有限实例与反例 | 对无限输入的完备证明或真实使用频率 |
| `fixtures/golden.jsonl` | 从独立原实现对照中抽取的匿名输入与预期结果 | 包含完整潜在见证、覆盖完整生成域或真实频率 |
| `RESEARCH_HISTORY.md` | 已知候选、反例与成本线索 | 历史全部候选已进入参考默认路径 |
| `RESEARCH_TASK.md` | 一次性自主研究的交付要求 | 必须沿某个预设算法方向继续 |

原核心的历史验证范围保留在本页末尾；本版新增模块的验证与模型边界见 ENGINEERING。
原始源文件、映射、位置和本地验证工具不属于外发包。

## 使用约定

- 把 `reference.py` 的完整信息结果作为等价替换路线的参考答案；它是确定的局部策略，不是已证明的全局最优目标。
- 研究替代策略时允许输出不同结果，但须另列新目标、最小差异实例、保留性质及待决策的语义变化。
- 标量输入域是研究接口的扩展域。跨事实相关性与可实现性须按 `PROBLEM.md` 处理，不能把任意组合当成实际数据。
- 失败、缺失和已知空值具有不同语义。依赖集合变化也可能影响下游行为。
- 包内有历史底层耗时，但没有实际取证/发布等待分布；不得把查询数或步数换算成用户延迟。
- 不要从本包寻找业务身份。研究结果使用这里的中性符号即可。

## 核心接口与输入

`reference.py` 既可导入，也接受标准输入中的 JSONL；每行一个请求，每行输出一个 JSON 结果：

```text
python reference.py < requests.jsonl
```

上面是支持重定向的终端写法；PowerShell 可用 `Get-Content -Encoding utf8 requests.jsonl | python reference.py`。

| 接口 | 返回 |
|---|---|
| `complete_plan(case)` / `op="plan"` | 完整单元列表；只有事实完整时才是完整信息oracle，部分事实下调用不构成稳定证书 |
| `certify(case, target, lookbehind=2)` / `op="certify"` | `status/unit/needs/failures/first/steps/dependent_items/reads` |
| `certify_strategy(case, target)` / `op="strategy"` | `selected/baseline/retry/retried/adopted/total_steps`，扩窗为显式可选策略 |
| `reads_current(result, case)` | 已观察读集是否仍与当前输入匹配 |
| `first_changed_read(result, case)` | 首个变化的观察键或空值 |

最小请求如下，保存为 `requests.jsonl` 后可直接执行：

```json
{"op":"certify","target":0,"case":{"items":[{"id":"i0","content_version":0,"analysis":{"status":"computed","version":1,"fact":{"state":"NARROW","width":100,"height":200,"rotation":0,"orientation":1,"side":"UNKNOWN"}}}],"edges":[],"settings":{"direction":"AB","automatic":true,"hard_isolates":[],"pinned_links":[],"opening":"AUTO","revision":0},"generation":0,"order_revision":0}}
```

- `items` 的 `id` 唯一；`target`、单元 `item`、`first/second` 以及约束集合使用零起点序号。
- 每对象可带 `header` 和 `analysis`。分析状态为 `unknown/computed/failed`；`computed` 带 `fact` 和算法 `version`，`failed` 带 `reason`。有效已计算分析优先于header。
- `fact` 包含 `state=NARROW/WIDE/UNKNOWN/FAILED`、几何 `width/height`、`rotation=0/90/180/270`、`orientation` 和 `side=A/B/UNKNOWN`。冻结核心直接消费这些事实；新增 generator 提供受限潜在栅格的派生公式，不提供编码格式或解码器。
- 数学文字的 `NEUTRAL` 对应 **已计算分析中的 `side="UNKNOWN"`**；它与 `analysis.status="unknown"` 的未观测状态不同。
- `edges` 条目如 `{"first":0,"second":1,"status":"computed","score":null,"version":1}`。`score:null` 是已知空值；缺少条目或 `status:"unknown"` 是未知。失败示例为 `{"first":0,"second":1,"status":"failed","failures":{"0":"RESOURCE"}}`。
- 边证据只在相邻且端点分析已计算时可见；完整占用端点会导出已知空值。`direction=AB/BA`，槽A/B是固定标签；`opening=AUTO/SINGLE/PAIR`。
- 设置默认：`automatic=false`、`opening=AUTO`、`hard_isolates=[]`、`pinned_links=[]`、`revision=0`。身份含 `generation/order_revision` 与各对象的 `content_version/rotation/orientation`。
- 冻结核心的读集快照核对观察输入，包括算法版本和内容身份；runtime 在其外提供跨请求缓存模型，不是最小支撑集求解器。

这是低层研究接口，**没有完整的外部约束合法性校验器**。合法性预检是调用方前置条件。测试保留了重叠指定连接等接口扩展域行为，以检查分支保真；此类输入不能计入正常场景收益。研究者新增生成器时须显式区分合法域与接口域。

## 合成协议接口与限制

导入 `protocol.run_event_trace(trace)`，输入含 `capacity`、初始 `version` 和 `events`；事件 `op` 为 `request/cancel/finish/consume/version/publish`。

任务以 `(version,key)` 标识，`request` 的 `required` 只是需求标签；同身份在途提升复用任务。`finish` 后仍占名额，直到 `consume`；取消的运行任务等待实际 `finish` 才释放。`version` 变更拒绝旧完成。`publish` 事件携带 `target/unit`，只检查版本与同一target的发布值是否冲突。

每步返回 `outcome/occupied/version/required/published`。该模拟器实际覆盖H6/H7的切片、旧版本拒绝和同target不变检查；**不验证证书真伪、跨target分区一致性、真实观察或确认消费规则，也不完整实现H2/H3/H8/H9**。`consume` 是任务完成接纳，不是H9中的确认消费。核心快照接口与协议模拟器未接成真实运行系统。

## 冻结核心的历史验证范围

交付者使用冻结原实现直接执行独立核心差分，共1,806组：222组来自原生成实现处理的合成见证与有界缓存状态，1,584组覆盖结构、几何、约束、标签、缺失和失败的接口组合。全序列扫描输出、基准认证、扩窗所选结果及扩窗边界/成本均逐组一致。接口组合事实未齐时，其全序列扫描输出可以是临时计划，不是完整世界的认证答案。这些不是用户资料或自然数据集。

另对每组检查5类读集有效性事件：不变控制、上下文代次、对象顺序修订、规则修订和首对象header变化，共9,030项对照。header变化是否影响证书取决于它是否被实际依赖，不能把所有事件概括为“必须失效”。

独立差分曾发现单对象槽标签映射错误，补充回归后已修正；最终该差分域无剩余差异。匿名golden子集随包交付，预期结果来自独立原实现，不由本包Python计算生成。

golden共有7条，其中G002/G005/G006来自原生成映射处理的合成见证与有界缓存状态，G001/G003/G004/G007是结构性接口组合。每条的`case`用于部分认证，`full_case`用于全序列扫描对照；`expected.full_units`是该输入的参考输出。G002/G005/G006的`full_case`事实齐全；G001/G003分别保留未知或失败，扫描输出是临时计划；G004/G007在接口域中事实齐全。这些历史字段名本身不保证事实齐全，不能把临时计划用作部分信息认证的稳定性oracle。

本差分域未覆盖全部原因标签、非默认旋转/方向变换、负秩或所有失败枚举；实际失败类仅包含`READ`。5类有效性事件也不穷尽全部失效组合。包内其他合成单元测试不能自动扩大真实核心差分的覆盖声明。

上述结果是有界保真证据，不是全状态等价证明，描述的是原核心包的历史范围。本版新增采样生成器、
知识模型和会话合同实验，不因此获得完整异步宿主、外部解码、实际资源路由或用户等待的验证。
其新增有限对照、测试和未覆盖项见 ENGINEERING；新算法仍需证明一般性质并核验未覆盖层级。
