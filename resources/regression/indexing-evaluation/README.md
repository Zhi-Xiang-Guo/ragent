# Indexing evaluation

`dataset.json` is the original frozen set: 45 documents, 102 questions (20 development, 60 held-out test, 22 existing simulated regression). It contains 20 unchanged cooking documents from [all-in-rag](https://github.com/datawhalechina/all-in-rag/tree/cec956e80ae28d6c0ca84c9d39dddcf61ad633fd/data/C8/cook), 20 explicitly synthetic engineering documents, and five existing repository policy documents. Upstream commit, source paths and SHA-256 hashes are recorded. `fetch_cookbook.py` defaults to that pinned commit; changing it creates a new corpus version.

The questions are constructed from documents, not production user logs. The initial valid baseline showed a ceiling on the original corpus. `stress-dataset.json` therefore provides a **separate development reproducer**: eight synthetic manuals, 16 questions, with an operation anchor followed by generic recovery conditions. It was frozen before any strategy evaluated that suite. It does not replace or modify the original held-out set. Report its results separately; they cannot establish general answer accuracy.

All arms use the same corpus bytes, query normalization/rule-based splitting, vector-only retrieval, reranking, model route, topK 10 and 8192-character rendered context budget. LLM query rewriting is disabled for this controlled experiment. Answers use the production `KnowledgeSearchFacade` prompt, temperature 0, topP 1 and thinking false, from the same retrieval response. The model gateway remains external, so this is an end-to-end Docker application test with live model dependencies.

## Run against a dedicated Docker deployment

Use a disposable PostgreSQL/pgvector, Redis, object store and backend. Apply each branch's additive migration. Never point the fixture runner at a production database. Configure `ragent.eval.enabled=true`; synchronous ingestion additionally requires `ragent.eval.ingestion-enabled=true`. Both are off by default. That transport executes the existing production document service and ingestion kernel. The ordinary RocketMQ endpoint remains available; omit `--synchronous` to exercise it. The recorded run used synchronous transport after an isolated broker/transaction-message stall.

```sh
python3 runner.py ingest baseline --synchronous --out /tmp/indexing-results
python3 runner.py eval baseline --out /tmp/indexing-results
python3 analyze.py --out /tmp/indexing-results
```

Deploy the Parent-Child or Sentence Window branch, then repeat `ingest` and `eval` with `parent-child` or `sentence-window`. Update configuration and reingest the same document IDs. The runner checks source hashes, keeps credentials in memory and saves full responses, context units and exact answer prompts. `EVAL_USERNAME` / `EVAL_PASSWORD` override the disposable database's default admin account. Use `--workers 1` for a rate-limited gateway; transient answer/stage errors retry with a 5-second first pause and 35-second subsequent pauses and retain attempt statuses. Failed ingestion is retried only after its status is terminal; an active RUNNING task is never reset by the runner. When switching between these independent experimental branches, the known document configuration is converted through the existing update API before reading its mode-specific detail.

For the stress suite, pass `--dataset stress-dataset.json`, a separate `--out`, and `--mapping` pointing to the original mapping to reuse the same knowledge base. Disable all eight stress documents during the original suite. Enable/reingest them for the stress suite. Evaluate and freeze baseline failures **before** either advanced strategy runs the stress questions. Raw outputs from a rate-limited trial belong in a separate attempt directory and do not qualify as strategy failures.

## Interpretation

- Document coverage is the union of per-subquestion top-10 document rankings. No flattened global ranking is invented. nDCG is reported only for single-subquestion cases.
- Evidence coverage checks normalized exact source quotes in both attributed context units and the final rendered context. It is conservative about paraphrases.
- Answer checks are frozen regex/number checks and an abstention heuristic, **not semantic correctness or faithfulness scores**. Cases without checks have no answer-check denominator. Review improvements, regressions and abstentions alongside reference evidence. In this run, source-and-answer inspection found a regex false positive: correct numbers occurred only in an example list while the main answer used another pair. Preserve the original automated failure list and report the semantic inspection separately; do not silently rewrite the frozen selection.
- Provider errors and degraded retrieval stages are operational failures and excluded from quality comparisons. Wall latency includes retry waits; response latency is the final request's duration.
- `LLMService` returns text only. Provider token usage and monetary cost are unavailable; context character counts and vector counts are reported without relabeling them as actual token consumption.

`python3 -m unittest test_analyze.py` verifies ranking denominators, source attribution and evidence normalization. Java tests cover structural boundaries, context expansion, guards and the pre-write embedding failure boundary. The Docker lifecycle checks additionally exercise real rollback after a deliberately rejected vector insert, whole-document reingestion, disable/enable, manual edit rejection, STANDARD fallback and deletion.


## Lifecycle and fixed-prompt replay

After quality measurements, run the disposable-document checks against the dedicated Compose project:

```sh
python3 check_lifecycle.py parent-child --compose /path/to/private-compose.json --results /tmp/indexing-results
# Deploy the other arm before selecting sentence-window.
```

The check creates its own document and a temporary PostgreSQL trigger restricted to that document. It removes the trigger in a finally block, compares the previous chunk/vector identities after the forced failure, and deletes its test document on success.

For two additional answer generations from the exact captured production prompts, use the same gateway and model and provide its key through an environment variable:

```sh
python3 replay_prompts.py all --results /tmp/indexing-results --url https://your-gateway/chat/completions --model your-captured-model
```

`EVAL_MODEL_API_KEY` is read in memory and never saved. Only the previously frozen automated baseline-failure IDs are selected. These are synthesis stability checks with fixed evidence, not three independent retrieval runs. Keep this distinction in every result table.
