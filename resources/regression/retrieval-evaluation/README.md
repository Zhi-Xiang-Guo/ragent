# 检索评测

Python 3.10+，仅标准库，无额外依赖。离线计算与后端模型调用分离。

## 离线指标

```sh
python3 -m unittest discover -s resources/regression/retrieval-evaluation -p 'test_*.py'
python3 resources/regression/retrieval-evaluation/metrics.py results.json --k 5
```

输入 JSON 数组，每项包含 `id`、有序 ID 数组 `ranking`、相关性映射 `qrels`。
相关性取整数 0～3；0 明确不相关，1～3 为相关程度。未标注结果按 0 gain 计算，同时报告 unjudged 与 judged_fraction。
去重保留首次出现，再截断 K；Precision 分母固定 K。Recall 分母为标注中的全部正例。
nDCG 使用 `(2^grade-1)/log2(rank+1)`，IDCG 来自该问题完整 qrels。
无正例时 Recall/MRR/nDCG 为 null，不参与相应宏平均；不能把无答案问题当作满分。
MRR 仅查看 TopK 内第一条相关结果。指标报告各自的有效样本数。

输入必须使用同一评测单位：文档或 chunk，不能混用。文档模式将已召回的 chunks 按文档首次出现顺序折叠，不代表检索后端执行了文档 TopK。
