# 有序对象的局部分组与增量认证：独立研究包

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

详细的本次验证范围写在交付版末尾的“验证范围”中。验证原系统所需的映射、原始源文件、路径和本地工具不属于本包。

## 使用约定

- 把 `reference.py` 的完整信息结果作为等价替换路线的参考答案；它是确定的局部策略，不是已证明的全局最优目标。
- 研究替代策略时允许输出不同结果，但须另列新目标、最小差异实例、保留性质及待决策的语义变化。
- 标量输入域是研究接口的扩展域。跨事实相关性与可实现性须按 `PROBLEM.md` 处理，不能把任意组合当成实际数据。
- 失败、缺失和已知空值具有不同语义。依赖集合变化也可能影响下游行为。
- 没有实际耗时分布，不得把查询数或步数换算成用户延迟；可报告操作计数和条件性成本界。
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
- `fact` 包含 `state=NARROW/WIDE/UNKNOWN/FAILED`、几何 `width/height`、`rotation=0/90/180/270`、`orientation` 和 `side=A/B/UNKNOWN`。本包以几何状态模拟两种占用类型，不提供特征提取器。
- 数学文字的 `NEUTRAL` 对应 **已计算分析中的 `side="UNKNOWN"`**；它与 `analysis.status="unknown"` 的未观测状态不同。
- `edges` 条目如 `{"first":0,"second":1,"status":"computed","score":null,"version":1}`。`score:null` 是已知空值；缺少条目或 `status:"unknown"` 是未知。失败示例为 `{"first":0,"second":1,"status":"failed","failures":{"0":"RESOURCE"}}`。
- 边证据只在相邻且端点分析已计算时可见；完整占用端点会导出已知空值。`direction=AB/BA`，槽A/B是固定标签；`opening=AUTO/SINGLE/PAIR`。
- 设置默认：`automatic=false`、`opening=AUTO`、`hard_isolates=[]`、`pinned_links=[]`、`revision=0`。身份含 `generation/order_revision` 与各对象的 `content_version/rotation/orientation`。
- 读集快照核对观察输入，包括算法版本和内容身份。它不是最小支撑集求解器，也没有实现一个跨请求的复用缓存容器。

这是低层研究接口，**没有完整的外部约束合法性校验器**。合法性预检是调用方前置条件。测试保留了重叠指定连接等接口扩展域行为，以检查分支保真；此类输入不能计入正常场景收益。研究者新增生成器时须显式区分合法域与接口域。

## 合成协议接口与限制

导入 `protocol.run_event_trace(trace)`，输入含 `capacity`、初始 `version` 和 `events`；事件 `op` 为 `request/cancel/finish/consume/version/publish`。

任务以 `(version,key)` 标识，`request` 的 `required` 只是需求标签；同身份在途提升复用任务。`finish` 后仍占名额，直到 `consume`；取消的运行任务等待实际 `finish` 才释放。`version` 变更拒绝旧完成。`publish` 事件携带 `target/unit`，只检查版本与同一target的发布值是否冲突。

每步返回 `outcome/occupied/version/required/published`。该模拟器实际覆盖H6/H7的切片、旧版本拒绝和同target不变检查；**不验证证书真伪、跨target分区一致性、真实观察或确认消费规则，也不完整实现H2/H3/H8/H9**。`consume` 是任务完成接纳，不是H9中的确认消费。核心快照接口与协议模拟器未接成真实运行系统。

## 验证范围

交付者使用冻结原实现直接执行独立核心差分，共1,806组：222组来自原生成实现处理的合成见证与有界缓存状态，1,584组覆盖结构、几何、约束、标签、缺失和失败的接口组合。全序列扫描输出、基准认证、扩窗所选结果及扩窗边界/成本均逐组一致。接口组合事实未齐时，其全序列扫描输出可以是临时计划，不是完整世界的认证答案。这些不是用户资料或自然数据集。

另对每组检查5类读集有效性事件：不变控制、上下文代次、对象顺序修订、规则修订和首对象header变化，共9,030项对照。header变化是否影响证书取决于它是否被实际依赖，不能把所有事件概括为“必须失效”。

独立差分曾发现单对象槽标签映射错误，补充回归后已修正；最终该差分域无剩余差异。匿名golden子集随包交付，预期结果来自独立原实现，不由本包Python计算生成。

golden共有7条，其中G002/G005/G006来自原生成映射处理的合成见证与有界缓存状态，G001/G003/G004/G007是结构性接口组合。每条的`case`用于部分认证，`full_case`用于全序列扫描对照；`expected.full_units`是该输入的参考输出。G002/G005/G006的`full_case`事实齐全；G001/G003分别保留未知或失败，扫描输出是临时计划；G004/G007在接口域中事实齐全。这些历史字段名本身不保证事实齐全，不能把临时计划用作部分信息认证的稳定性oracle。

本差分域未覆盖全部原因标签、非默认旋转/方向变换、负秩或所有失败枚举；实际失败类仅包含`READ`。5类有效性事件也不穷尽全部失效组合。包内其他合成单元测试不能自动扩大真实核心差分的覆盖声明。

上述结果是有界保真证据，不是全状态等价证明。特征生成器、完整见证缓存、真实异步调度、发布链路及实际性能分布未移植或验收；协议测试只验证上面明示的合成切片。新算法仍需证明一般性质并验证未覆盖层级。
