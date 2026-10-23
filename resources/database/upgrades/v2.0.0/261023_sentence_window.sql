-- Additive, repeatable migration; existing documents remain STANDARD.
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS chunk_type VARCHAR(16) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE t_knowledge_chunk ADD COLUMN IF NOT EXISTS section_id VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_chunk_doc_section_order ON t_knowledge_chunk (doc_id, section_id, chunk_index);
