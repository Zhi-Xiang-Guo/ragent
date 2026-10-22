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

## 数据与服务运行

`datasets/bit-policy.json` 包含 22 条模拟业务回归用例（20 个正例、2 个无答案问题），引用仓库已有比特严选政策文档的哈希与原文片段。标注非穷尽、未经独立人工审核，只用于开发回归。
运行前需部署对应语料；工具不自动导入、清库或重启。源文件哈希检查可发现本地语料变更；**不代替数据库索引完整性检查**。

准备 manifest.json：

```json
{"documents":[{"collectionName":"实际库名","docId":"内部文档ID","key":"return","sha256":"与数据集一致的哈希"}]}
```

必须映射所有标注来源；可继续添加其他已部署文档映射。相同文件名不自动合并。配置快照显式填写部署 commit/镜像标识、检索预算/通道/阈值及模型名称，不包含密码或 API key：

```json
{"deploymentRevision":"镜像或commit","retrieval":{"topK":10,"channels":["vector"]},"models":{"embedding":"模型名","rerank":"模型名"}}
```

配置快照由操作者核对实际部署后提供，不能把仓库默认配置当成运行时配置。认证通过环境变量 `RAGENT_EVAL_TOKEN` 传入 Authorization 原值，工具不保存 token。服务启用 `ragent.eval.enabled=true`。

```sh
python3 resources/regression/retrieval-evaluation/runner.py run \
  --dataset resources/regression/retrieval-evaluation/datasets/bit-policy.json \
  --manifest manifest.json --config-snapshot config.json \
  --base-url http://127.0.0.1:9090/api/ragent --variant baseline --limit 2 --k 5 \
  --output resources/regression/retrieval-evaluation/artifacts/baseline
```

先加 `--dry-run` 核对用例，再小批量实跑。`--tags price` 可筛选标签。输出目录必须不存在，防止覆盖。支持 `--capture-stages` 请求后续版本的阶段快照。
仅对单子问题结果计算指标；自动拆成多个子问题时标记 INVALID_RESULT，避免伪造全局排名。遇到未映射结果保留为 unjudged 噪声，不静默丢弃。
请求失败、格式错误计入错误数并返回退出码 2；无答案样本单独统计是否返回证据，这不是答案拒答准确率。
每次运行保存 responses.jsonl、dataset.json、manifest.json、run.json、scores.json、summary.json、report.md。

```sh
python3 resources/regression/retrieval-evaluation/runner.py replay --run artifacts/baseline --output artifacts/replay
python3 resources/regression/retrieval-evaluation/runner.py compare --baseline artifacts/baseline --candidate artifacts/candidate --output artifacts/comparison
```

重放只读本地响应，不调用服务。对照要求数据集、映射、用例顺序、评测单位、K 和指标口径相同；配置变化保留在对照结果中。报告失败状态、逐题分数变化及丢失的相关文档，不把失败样本当作通过。

## 阶段评测

使用 `--capture-stages` 获取 channel、融合截断前后、重排、证据门槛、元数据富化的快照。
manifest 文档项可增加 `chunkIds` 数组，在尚无 docId 的早期阶段按 `(collectionName, chunkId)` 归属文档；映射必须来自本次固定索引。
报告增加 stages.json 与阶段对照表，同时列出完整候选集合 Recall 和固定 K Recall。去重阶段不是融合排名，其 MRR/nDCG 不计分。
ERROR/TIMEOUT 阶段不计分，EMPTY_OR_DEGRADED 保留不确定性标记。缺少快照表示没有采集或没有执行，不代表阶段成功。

summary.json 单独统计 requestsWithStageWarnings 与 stageWarnings。HTTP 成功和指标可计算不等于模型调用成功；未采集快照时无法推断阶段健康。
