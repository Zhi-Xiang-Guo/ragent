# 检索评测接口

评测环境设置 `ragent.eval.enabled=true` 后，调用 `GET /rag/eval?question=...`。
遵循服务原有鉴权。该接口仍会调用改写、embedding、重排模型，不生成最终答案。

## 有序结果

`data.subQuestionResults` 与输入子问题同序，每项包含：

- `subQuestion`：实际检索的子问题。
- `status`：`SUCCESS` 或 `ERROR`；无命中是成功且列表为空。
- `rankedChunks`：检索后处理完成、意图分组和上下文排版前的不可变快照。
- 候选字段：`rank`（从 1 开始）、`collectionName`、`chunkId`、`docId`（内部 ID）、`text`、`score`、`rerankScore`。

按 collection 与 chunk key 保留首次命中并去重；无 ID 时使用正文摘要作为内部去重键。
列表顺序是排名依据，不再按 score 排序。score 可能是向量、关键词、融合或重排分，不能跨阶段或跨子问题直接比较。
`rerankScore=null` 表示没有精排分。来源不支持内部文档 ID 时 `docId=null`，不得自行用文件名猜测。

每个子问题有独立排名，不能把多个子问题拼成一个全局 TopK。该列表不是实际 LLM 输入，也不保证与上下文排版顺序一致。

## 兼容字段

现有 `retrievedDocIds`、`retrievedChunkIds`、`retrievedContexts`、`retrievedContextDocIds`、`hasKb`、`subIntents`、`intentLeafIds`、`latencyMs` 的生成逻辑保持不变。
旧 retrieved* 数组是按意图分组后摊平的历史结果，不能用于还原真实排名。排序指标应使用新增字段。
旧 retrievedDocIds 是文件名去扩展名后的业务标识，与新候选的内部 docId 不同。跨环境评测需要显式文档映射与语料版本。

失败子问题保留位置并标记 ERROR。通道内部的部分降级不在此版本的 status 中体现，不能把 SUCCESS 解释为所有后端均正常。

## 阶段采集（可选）

添加 `captureStages=true` 后，每个子问题的 `stages` 包含：

- `channel:VECTOR/KEYWORD/GRAPH/WEB_SEARCH`：各通道返回的原始顺序和分数。
- `post:Deduplication`：去重后的候选集合；拼接顺序不代表融合排名。
- `fusion:ranked`：RRF 排序后、候选截断前。
- `post:Fusion`：融合截断后，即 Rerank 输入。
- `post:Rerank`、`post:EvidenceGate`、`post:MetadataEnrichment`：相应处理器输出。

仅记录实际执行的通道和处理器。无采集参数时 stages 为空；不开启额外检索或模型请求。
快照包含 stage、status、latencyMs、rankedChunks。原始候选的 docId 可能尚未富化，阶段评测需用 manifest 的 chunkIds 映射回文档。
状态包括 SUCCESS、EMPTY、ERROR、TIMEOUT、UNSCORED。通道内部可能捕获错误并返回空集合，因此无法确认原因的通道空结果明确标记 EMPTY_OR_DEGRADED；不伪装成确定无命中。
UNSCORED 表示重排结果中没有精排分，可能是 noop 降级。失败处理器快照中的候选是降级后继续流转的集合，不应计为该处理器成功执行。

收集器按请求及子问题显式传递；阶段数据立即复制为不可变值，通道超时后迟到任务不会写入快照。

## 答案和上下文对照

`generateAnswer=true` 使用本次检索结果及 `KnowledgeSearchFacade` 的正式合成提示词生成答案，不二次检索。
新增 `finalContext`、`answerMessages`、`answer`、`answerStatus`、`contextUnits`。
`subQuestionResults` 保留小块排名；`contextUnits` 独立记录扩展结果和命中来源。
上下文总上限为 8192 Unicode 码点，含模板开销；多子问题平均分配预算，裁剪围绕首个命中证据。
`LLMService` 当前只返回文本，`usageStatus` 明确表示真实供应商 token 用量不可用。
无上下文返回 `NO_CONTEXT`，生成异常返回 `ERROR`，不将异常计为正确拒答。
