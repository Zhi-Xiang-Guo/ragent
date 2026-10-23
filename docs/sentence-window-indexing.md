# Sentence Window：最小改动落地设计

本分支基于现有 `IngestionKernel → ChunkingService → ChunkEmbeddingService → ChunkIndexWriter` 入库链路，以及 `RetrievalEngine → ContextFormatter` 检索链路实现。没有新增文档表或 UI。

## 文档与实体

- `KnowledgeDocumentDO` 不增加列；使用已有 JSONB `ingestion_spec` 保存版本 3、解析预算与 indexing 参数。旧配置缺省为 STANDARD。
- `KnowledgeChunkDO` 仍是唯一块实体。新增 `chunk_type`，默认 STANDARD，迁移不改变旧文档。
- 同表增加 `section_id`。SENTENCE 保存句子或有序结构单元，chunkIndex 为文档内全局递增序号。
- 中英文句末与换行切句；小数中的点不切开。列表项、表格和代码沿用结构分块，再对无标点长段落做长度兜底。章节标题沿用 HeadingHandler，结构块保留图片资产和原向量正文。
- section_id 在每个标题处更新，检索窗口绝不跨节。windowRadius 是句子索引的左右半径，默认 2。

## 调用方式

先执行本分支 `resources/database/upgrades/v2.0.0/261023_sentence_window.sql`，再部署。迁移可重复执行。上传或更新接口仍使用原字段，`processMode=chunk`，`ingestionSpec` 是下列 JSON 字符串：

```json
{"parseProfile":"fast","maxChars":512,"overlapChars":64,"indexingMode":"SENTENCE_WINDOW","windowRadius":2,"maxSentenceChars":384}
```

PUT 更新文档时仍须传 `docName`。更新策略后调用原 `POST /knowledge-base/docs/{docId}/chunk` 完整重建。参数经 IngestionSpecCodec 校验，非法模式、半径/预算或高级模式加整文档配置均拒绝。

## 写入与生命周期

解析与向量生成在替换旧批次之前完成；嵌入失败不会调用索引写入。关系落点在现有事务中锁定文档，校验文档仍启用且属于同一知识库，然后整体替换。沿用 ChunkIndexWriter 扇出与删除，无新的后台任务系统。PG 关系表和 PG 向量落点参与现有事务；其他外部落点仍受现有跨系统事务限制。

高级文档的单块新增、编辑、删除和单块/批量启停均拒绝；即使先把配置改回 STANDARD，只要库里仍有高级块也拒绝。修源文件并完整重建。文档级禁用、启用、删除沿用原入口，父块不会在重新启用时进入向量库。

## 检索与上下文

原候选召回、融合、重排和排名快照不变。在最后排版前执行一次 ContextExpander：批量校验当前块、文档与知识库归属，过滤已删除/禁用/跨集合结果；在一致的关系库读取快照内回查上下文。
同一文档同一章节的重叠窗口合并，一次有界批量查询取邻句，按原序拼接；窗口携带命中句 ID、章节和起止索引。缺少章节标识时回退原句并标记原因。

全问题上下文预算为 8192 Unicode 字符，包含模板与多子问题包装；不足时优先保留更靠前的证据，对最后一个上下文单元围绕原命中位置裁剪。ContextUnit 与原检索排名分开，评测可查看 `context:Expanded` 和最终 prompt。

## 回退与范围

改为 STANDARD 后完整重建即可恢复普通分块。回退旧二进制前，应先完成普通重建；只回退二进制会停止高级上下文扩展。两条实验分支各自支持一种高级模式，后续合并时统一 IndexingOptions 与迁移字段。

本轮评测语料为真实食谱文档构造题、合成边界题与已有模拟回归题，不冒充线上用户失败日志。冻结题集后根据基线实际响应筛选失败，再对照最终上下文与答案；模型接口只返回文本，报告不伪造 provider token 用量。
