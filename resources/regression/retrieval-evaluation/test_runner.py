import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from runner import compare, digest, load_dataset, ranking_from_response, replay, report, run, score_rows, validate_manifest, write


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {"documents": [{"collectionName": "kb", "docId": "1", "key": "a", "sha256": "hash"}]}
        self.dataset = {"sources": {"a": {"sha256": "hash"}}, "cases": [
            {"id": "q", "question": "Q", "answerable": True, "qrels": {"a": 3}, "tags": ["smoke"]}]}
        self.response = {"subQuestionResults": [{"subQuestion": "Q", "status": "SUCCESS", "rankedChunks": [
            {"rank": 1, "collectionName": "kb", "docId": "1", "chunkId": "c"}]}]}

    def test_dataset_is_grounded_in_checked_sources(self):
        data = load_dataset(Path(__file__).parent / "datasets/bit-policy.json")
        self.assertEqual(22, len(data["cases"]))

    def test_mapping_and_split_guard(self):
        mapping = validate_manifest(self.manifest, self.dataset)
        self.assertEqual((["a"], []), ranking_from_response(self.response, mapping))
        self.response["subQuestionResults"].append(self.response["subQuestionResults"][0])
        with self.assertRaises(ValueError):
            ranking_from_response(self.response, mapping)

    def test_missing_mapping_retains_noise(self):
        ranking, missing = ranking_from_response(self.response, {})
        self.assertEqual(ranking, missing)
        self.assertEqual(1, len(ranking))

    def test_bad_manifest_rejected(self):
        self.manifest["documents"][0]["sha256"] = "changed"
        with self.assertRaises(ValueError):
            validate_manifest(self.manifest, self.dataset)

    def test_failed_requests_not_scored(self):
        rows = score_rows([{"id": "q", "status": "HTTP_ERROR", "latencyMs": 5}], self.dataset, self.manifest, 5)
        self.assertNotIn("metrics", rows[0])

    def test_stage_scores_use_chunk_manifest_and_preserve_failures(self):
        self.manifest["documents"][0]["chunkIds"] = ["c"]
        hit = {"rank": 1, "collectionName": "kb", "chunkId": "c"}
        self.response["subQuestionResults"][0]["stages"] = [
            {"stage": "channel:VECTOR_GLOBAL", "status": "SUCCESS", "latencyMs": 1, "rankedChunks": [hit]},
            {"stage": "post:Deduplication", "status": "SUCCESS", "latencyMs": 1, "rankedChunks": [hit]},
            {"stage": "channel:ES", "status": "TIMEOUT", "latencyMs": 10, "rankedChunks": []}]
        row = score_rows([{"id": "q", "status": "SUCCESS", "latencyMs": 5, "response": self.response}],
                         self.dataset, self.manifest, 1)[0]
        self.assertEqual(1, row["stages"][0]["candidateRecall"])
        self.assertEqual(1, row["stages"][0]["metrics"]["ndcg"])
        self.assertIsNone(row["stages"][1]["metrics"]["ndcg"])
        self.assertNotIn("metrics", row["stages"][2])
        with tempfile.TemporaryDirectory() as tmp:
            summary = report(Path(tmp), [row])
            self.assertEqual(1, summary["requestsWithStageWarnings"])
            self.assertEqual("TIMEOUT", summary["stageWarnings"][0]["status"])

    def test_invalid_stage_does_not_leave_partial_success_metrics(self):
        self.response["subQuestionResults"][0]["stages"] = [
            {"stage": "fusion:ranked", "status": "SUCCESS", "latencyMs": 1,
             "rankedChunks": [{"rank": 9}]}]
        row = score_rows([{"id": "q", "status": "SUCCESS", "latencyMs": 5, "response": self.response}],
                         self.dataset, self.manifest, 1)[0]
        self.assertEqual("INVALID_RESULT", row["status"])
        self.assertNotIn("metrics", row)
        self.manifest["documents"][0]["chunkIds"] = ["c", "c"]
        with self.assertRaises(ValueError):
            validate_manifest(self.manifest, self.dataset)

    def test_run_replay_compare_and_incomplete_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write(root / "manifest.json", self.manifest)
            write(root / "config.json", {"deploymentRevision": "abc", "retrieval": {"topK": 10}, "models": {"rerank": "test"}})
            args = argparse.Namespace(dataset="unused", manifest=root / "manifest.json", config_snapshot=root / "config.json",
                                      output=root / "baseline", variant="baseline", k=5, tags=None, limit=None,
                                      dry_run=False, base_url="http://localhost", timeout=1, capture_stages=False)
            with patch("runner.load_dataset", return_value=self.dataset), patch("runner.fetch", return_value=self.response):
                run(args)
            replay(argparse.Namespace(run=root / "baseline", output=root / "replay"))
            self.assertEqual(json.loads((root / "baseline/scores.json").read_text()), json.loads((root / "replay/scores.json").read_text()))
            compare(argparse.Namespace(baseline=root / "baseline", candidate=root / "baseline", output=root / "compare"))
            result = json.loads((root / "compare/comparison.json").read_text())
            self.assertEqual(0, result["cases"][0]["delta"]["ndcg"])
            (root / "baseline/responses.jsonl").write_text("")
            with self.assertRaises(ValueError):
                replay(argparse.Namespace(run=root / "baseline", output=root / "bad"))
