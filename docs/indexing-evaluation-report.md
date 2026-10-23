# Parent-Child / Sentence Window 落地与 Docker 评测报告

本轮已分别落在两个独立分支，保持现有文档、块实体及入库/检索主链路。文档表不新增列；策略参数使用已有 `ingestion_spec`。chunk 表只增加角色和父块/章节标识。本轮没有新增 UI。

| 实现 | 分支 | 重点 review 的业务实现提交 |
|---|---|---|
| Parent-Child | `codex/parent-child-retrieval` | `51110912` |
| Sentence Window | `codex/sentence-window-retrieval` | `11a9a0e7` |

两条分支基于任务启动时的 `b530fd63` 加公共评测能力，是独立实验版本，各自支持 STANDARD 和一种高级模式。原工作区随后出现了其他并行工作，本任务保留自己的隔离快照，没有为此重置或改写原工作区。它们未合并到原分支；直接切换实验镜像时应先转换配置并整篇重建。飞书链接当时不可读，本次依据仓库实际实体及调用链实现，没有假称已参考未读笔记。

## 数据与对照约束

原冻结集：45 文档、102 问题，含 20 篇 [all-in-rag 食谱](https://github.com/datawhalechina/all-in-rag/tree/cec956e80ae28d6c0ca84c9d39dddcf61ad633fd/data/C8/cook)、20 篇明确标注的合成运维文档、5 篇仓库已有模拟政策文档。分为 dev 20 / held-out test 60 / regression 22。原文 SHA-256、上游 commit、来源路径及许可说明均保留。

原集出现证据覆盖天花板后，额外冻结独立的开发边界复现集：8 篇合成手册、16 问。它让唯一动作锚点与相邻的通用恢复条件跨小块，仍在同一章节内。原 test 集及其文档未改动；边界题不能代表线上分布，也不冒充线上用户失败日志。

三组均使用同样的原文字节、embedding / rerank / chat 模型标识、向量召回、topK 10、召回预算 20、精排候选上限 40、最终证据区 8192 Unicode 字符预算。为隔离 indexing 效果，关闭 LLM 改写，采用相同的规范化/规则拆分。答案从一次检索返回的证据构建同一份生产 `KnowledgeSearchFacade` prompt，temperature 0、topP 1、thinking false。

- 基线：maxChars 512 / overlap 64，沿用现有结构分块与默认 toleranceFactor 3。
- Parent-Child：父块 2048、子块 384、子块重叠 64；父块强制按章节边界，只有子块向量化。
- Sentence Window：句子/结构单元上限 384，左右半径 2；不跨 section_id，重叠窗口合并。
- 实际模型：`nebius/Qwen/Qwen3-Embedding-8B`（1536 维）、`bge-reranker-v2-m3`、`deepseek-v4-flash`；通过相同本地适配器连接既有网关。适配器和运行 JAR 的 SHA-256 见 `results/image-fingerprints.json`；三组适配器与 runtime YAML overlay 已核对为相同，见 `runtime-overlay-parity.json`。

## 原冻结集结果

“完整证据覆盖”检查归属于正确文档的全部预设引用是否进入最终上下文；不是只看召回文档；分母为98个有答案问题，4个无答案问题不计入证据覆盖分母。“答案要点检查”是正则/数字与拒答辅助检查，不是完整语义准确率。其分母为 82；20 个已有模拟政策问题未设此自动答案检查，未被空检查误算为通过。

| 策略 | 有效请求 | 完整证据覆盖 | 答案要点检查 | 平均证据区字符 | P50 / P95 API 耗时 ms | 原45文档向量行数 |
|---|---:|---:|---:|---:|---:|---:|
| 普通分块 | 102/102 | 100.00% | 100.00%（n=82） | 4513 | 6035 / 11451 | 144 |
| Parent-Child | 102/102 | 100.00% | 100.00%（n=82） | 2784 | 5654 / 15224 | 356 |
| Sentence Window | 102/102 | 98.98% | 100.00%（n=82） | 978 | 5554 / 25209 | 1975 |

Sentence Window 的唯一精确引文未命中为 `return-3`（换颜色是否允许）。保存的上下文包含同文档 FAQ：“无理由换货默认仅支持同一 SKU。想换颜色、容量或型号，通常需要退货后重新下单。”主答案正确，属于精确引文匹配的保守漏计；原指标保留，不把它静默改成满分。复核见 `core-semantic-notes.json`。

开发、测试与回归分组的完整数据保存在 `results/summary.json`。文档排名使用每个子问题的原始后处理快照，文档覆盖取各组 top10 的并集，不拼造一个全局排名。该原题集较容易；证据覆盖满分不意味着已经证明普遍更高的回答准确率。耗时只来自这次顺序运行的 API 观测，未控制冷/热缓存和上游负载，不据此宣称性能提升。

## 独立边界复现结果

| 策略 | 文档覆盖@10 | nDCG@10 | 全部预设证据进入上下文 | 自动答案要点检查 | 逐题恢复条件主答案核对 |
|---|---:|---:|---:|---:|---:|
| 普通分块 | 100.00% | 1.0000 | 4/16 | 4/16 | 3/16 |
| Parent-Child | 100.00% | 1.0000 | 14/16 | 14/16 | 14/16 |
| Sentence Window | 100.00% | 0.8663 | 16/16 | 16/16 | 16/16 |

Sentence Window 在这组题的文档排名 nDCG 较低，但目标句仍在 top10，邻句扩展覆盖了所需条件。这次边界集 API 耗时 P50/P95（ms）分别为普通分块 10118/20909、Parent-Child 4407/9535、Sentence Window 16918/31713；平均证据区字符分别为 7913、8149、8053。句子窗口没有表现出耗时优势；不把证据覆盖的改善解释为性能收益。

逐题核对由本次 Codex 阅读保存的答案和源条款完成，没有独立人工裁判。判据是主答案是否给出该操作条目的正确等待时间、严格低于阈值以及两个条件同时满足；附注中提及正确数字不算主答案正确。`semantic-review.json` 保存逐题判定、原因和答案摘要哈希，便于 review。

先于高级策略运行冻结的自动基线失败清单有 12 题。逐题核对另外发现 1 个自动检查假阳性：`stress-07-11` 的主答案是 54 分钟/237 条，正确的 55 分钟/244 条只出现在“其他组”的例子中。因此自动筛选和语义核对分开报告，原冻结清单没有被事后静默改写。

- Parent-Child：自动冻结的 12 个基线失败中，完整证据恢复 11/12；原基线通过但该策略回退 1 题。
- Sentence Window：自动冻结的 12 个基线失败中，完整证据恢复 12/12；原基线通过但该策略回退 0 题。

可直接复核的案例：

| 案例 | 源条款要求 | 基线主答案 | Parent-Child | Sentence Window |
|---|---|---|---|---|
| stress-01-7 | 连续等待33分钟，且待处理数量严格低于150条，两个条件同时满足才允许恢复。 | 37分钟 / 178条（错配其他条目） | 主条件正确 | 主条件正确 |
| stress-02-11 | 连续等待40分钟，且待处理数量严格低于189条，两个条件同时满足才允许恢复。 | 40分钟 / 189条 | 拒答/未得正确条件 | 主条件正确 |
| stress-07-11 | 连续等待55分钟，且待处理数量严格低于244条，两个条件同时满足才允许恢复。 | 54分钟 / 237条（正确数值仅在附注） | 主条件正确 | 主条件正确 |

Parent-Child 的两项未通过为 `stress-02-7`、`stress-02-11`。阶段快照显示 VECTOR 候选中包含目标动作，但 Rerank 后的 top10 把该锚点裁掉，父块扩展没有机会补回它；这不是文档级 Recall 能揭示的问题。另有两条父块答案、四条句子窗口答案提示根名称或对应关系缺失：祖先标题主要保留在向量文本，扩展仍使用原节正文。这是来源展示的局限，未把“条件主答案正确”扩张成“整段回答完全无问题”。

## 固定证据的生成稳定性

对冻结的 12 个自动基线失败，原完整链路执行之外，再使用其原始生产 answerMessages 追加两次生成。以下是要点检查数；后两列没有重新检索，不是三次独立检索实验。

| 策略 | 初次完整链路 | 同prompt回放1 | 同prompt回放2 |
|---|---:|---:|---:|
| 普通分块 | 0/12 | 0/12 | 0/12 |
| Parent-Child | 11/12 | 11/12 | 11/12 |
| Sentence Window | 12/12 | 12/12 | 12/12 |

## 生命周期与失败保护

每条分支均通过 9 组真实 API / PostgreSQL 检查：角色和向量对应；5 类高级单块修改拦截；重建整体换批次；禁用后无向量和上下文；启用只恢复可检索角色；强制向量 INSERT 失败后旧块/向量 ID 完整回滚；只改配置不能绕过拦截；STANDARD 整篇重建恢复原编辑 API；高级文档删除清理所有角色与向量。

Parent-Child 还遇到一次真实的外部 embedding 超时。失败后配置已是 PARENT_CHILD，但旧的 33 个 STANDARD 块和 33 条向量仍在，证明旧索引没有先被清空。数据库证据见 `embedding-failure-preserved-batch.txt`。Sentence Window 的若干入库请求也经历上游超时，按终态重试，未重置 RUNNING 任务。

本次一致性验证覆盖 PG 关系表与 PGVector 在同一数据库事务中的落点。没有声称已经验证 Milvus / 外部图索引的跨系统事务一致性。高级模式仍通过整篇重建更新；这轮不开放单块修改。

相关 Java 断言测试每分支 33 项通过；另跑已有结构分块 fixture 导出，两条分支的 STANDARD 导出报告逐字相同。Python 指标单元测试 7 项通过。两条分支均完成 Java17 / Maven 打包。未声称已跑全仓所有外部集成测试。

## 运行故障与可复现记录

- 第一轮区域限定 chat 路由触发上游 429（网关表现为 502），102 条中有 77 条答案 ERROR；整轮隔离到 `attempt-rate-limited`，没有拿它们算分块失败。有效三组统一改用基础模型路由、单并发与相同固定改写配置。
- 隔离 RocketMQ broker 曾退出，随后事务消息停滞。保留前22篇成功的 MQ 入库，其余基线及高级策略走显式双开关控制的同步评测入口，调用同一生产文档服务/入库内核。没有把它描述为一次完整成功的 MQ 端到端验证。
- full-chain `LLMService` 只返回文本，provider 实际 token 用量和成本不可得。字符数、向量行数没有被冒充为 token 或成本。
- 本轮是文本 Markdown 与结构单元测试，未覆盖全部 PDF/OCR/MinerU 真实文件链路。合成边界手册高度模板化，不从16题结果推断普遍准确率。

题集 SHA-256：

```text
primary: d1766d2bd7eab209e908ba12c80522ce5c92a6e34a1b3a16b91e370e83c9f383
stress:  dcd3db27e912a92eb447f356789b95797cf6a618d33b2e5773e58667524ce36e
```

可直接审阅的摘要：[原题集指标](../resources/regression/indexing-evaluation/results/summary.json)、[边界集指标](../resources/regression/indexing-evaluation/results/stress/summary.json)、[逐题答案核对](../resources/regression/indexing-evaluation/results/stress/semantic-review.json)、[两轮回放](../resources/regression/indexing-evaluation/results/stress/prompt-replay-summary.json)。

可运行入口及命令见 [评测 README](../resources/regression/indexing-evaluation/README.md)；包括原 API 入库、检索+答案评测、指标分析、真实生命周期检查和捕获prompt回放。两条分支各有对应的实体/入库设计说明及可重复 SQL 迁移。摘要结果随代码提交；完整响应、原始排名、扩展状态、最终上下文、生产prompt、失败记录及运行指纹保存在本机隔离评测输出：

`/Users/a1/Documents/Code/Agent/ragent-indexing-evaluation/results`

本次隔离项目是 `ragent-index-eval`；没有修改原 `ragent-local` 容器或原工作区的业务改动。
