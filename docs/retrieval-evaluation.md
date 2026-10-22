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
