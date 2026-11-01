# research/ — 新增研究材料（原参考文件未改动）

| 文件 | 内容 |
|---|---|
| `model.py` | 独立可能世界模型：知识解析（分支α/β）、秩代表网格、穷举oracle `possible_units`、随机抽样反证 `sampled_units`、世界/知识生成器、合法域检查 |
| `branching.py` | 分支认证器 C-SL（逐步局部、多项式）与 C-PW（路径敏感、精确），以及保持失败合同的组合包装 `certify_contract` |
| `validity.py` | 作用域化、精化容忍的证书有效性校验（D3） |
| `protocol_interaction.py` | E7：证明支撑集收缩时在途任务的 CANCEL/KEEP 策略，在 `protocol.py` 合成切片上穷举交错 |
| `experiments.py` | E1–E9 实验；固定种子；结果写入 `results/*.json` |
| `test_research.py` | 新增对抗测试与变异检验（错误变体必须被抓到） |
| `results/` | 本次运行的结果 JSON |

## 运行（包根目录，Python 3.10+，仅标准库）

```text
python -m unittest test_reference test_protocol -v                 # 原测试（17项）
python -m unittest discover -s research -t . -p "test_*.py" -v     # 新增测试
python research/experiments.py all          # 全部实验（2核约30–40分钟）
python research/experiments.py E1 --quick   # 单项、缩小规模
python research/protocol_interaction.py     # E7
```

所有实例均为**抽象扩展域**（直接指定标签与标量），频率是构造域频率，不是实际负载比例；只有 golden 中 G002/G005/G006 来自原生成映射。
