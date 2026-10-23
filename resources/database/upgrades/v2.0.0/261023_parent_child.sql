-- Additive, repeatable migration; existing documents remain STANDARD.
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS parent_id VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_chunk_parent ON t_knowledge_chunk (parent_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_type_order ON t_knowledge_chunk (doc_id, chunk_type, chunk_index);
