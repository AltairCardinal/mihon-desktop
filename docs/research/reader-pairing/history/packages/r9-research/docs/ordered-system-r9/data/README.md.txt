# 匿名原始投影

JSONL 每行对应一条源记录，不筛选结果。六文件的行数与来源性质见 `../COST_EVIDENCE.md`。`recompute.py` 仅用标准库；运行 `python data/recompute.py --check` 验证已保存摘要，运行不带 `--check` 可重建。

通用键：`process_index` 为匿名进程索引，`seed` 为原实验种子，`rep`/`round` 为记录轮，`scenario` 为抽象负载，`config`/`candidate` 为配置，`n` 为有序 item 数量。原绝对 UTC 删除。CSV 数字精确解析为整数；JSON 数字保持数值与含义。`elapsed_ns`/`policy_lifecycle_ns` 为纳秒；`thread_allocated_bytes` 为当前线程分配；`structure_weight` 无字节单位；`*_u` 为注入虚拟服务单位。`checksum`/`output_sha256` 为原可观察输出签名，`trace_sha256`/`trajectory_sha256` 为原抽象事件签名，非文件哈希。文件 SHA 独立放在摘要中。

`owner.source` 的 legacy/corrected/current 是历史实现对照；其中配置 default/no-index/no-compact/no-stamp 则是同源消融。其他数据的配置都是研究比较，不等于默认宿主策略。`partial` 中 alpha/beta/nonnegative/source-grid 是不同证明域，不可池化采用率。`kernels.kind` 划分 score、complete_lifecycle、L2_migration、sampling_stages：最后两种并非普通计时重复，不能与前两种混算。`warmup=true` 的数据不参与 aggregate；未提供的字段保持缺失而非补零。

摘要包含 pooled min/median/max、每个匿名进程的中位数及相对指定基线的比值。owner 历史来源使用独立 JVM，无相同进程配对，因此其 `same_process_median_ratios` 可为空；不能伪造历史配对。其他来源同进程对照保留逐进程比值，不将汇集中位比误称各进程比值中位数。不同证据层不能合并成一个加速数字。

整数计数包括 hits、runs/attempts、reads/exactReads、steps、gates、candidates、supported、evaluations、frontier、stops；它们沿用各实验的工作定义，不在不同实验间直接相加。`structure_weight` 的 capacity 可因策略不同而改变。`-1` 是原未计数哨兵。scheduling 的 starts/completions/cancellations/stale 是真实队列生命周期事件计数，但 required/optional wait 与 starts_u/completions_u 使用虚拟时钟；其纳秒不包含真实外部服务。
