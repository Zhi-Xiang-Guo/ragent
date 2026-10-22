"""Run, replay and compare retrieval evaluations against an existing Ragent service."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

from metrics import CONTRACT, aggregate, evaluate

ROOT = Path(__file__).resolve().parents[3]


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def load_dataset(path, repo_root=ROOT):
    data = read(path)
    ids = set()
    for key, source in data["sources"].items():
        content = (repo_root / source["path"]).read_bytes()
        if hashlib.sha256(content).hexdigest() != source["sha256"]:
            raise ValueError("source checksum mismatch: " + key)
    for case in data["cases"]:
        if not case.get("id") or case["id"] in ids or not case.get("question", "").strip():
            raise ValueError("invalid or duplicate case id/question")
        ids.add(case["id"])
        evaluate([], case["qrels"], 1)
        if not isinstance(case.get("answerable"), bool) or case["answerable"] != any(g > 0 for g in case["qrels"].values()):
            raise ValueError("answerable must agree with positive judgments: " + case["id"])
        for key in case["qrels"]:
            if key not in data["sources"]:
                raise ValueError("unknown source: " + key)
        for key, excerpt in case.get("evidence", {}).items():
            if not excerpt or excerpt not in (repo_root / data["sources"][key]["path"]).read_text(encoding="utf-8"):
                raise ValueError("evidence not present in source: " + case["id"])
    return data


def validate_manifest(manifest, dataset):
    mapping = {}
    keys = set()
    for item in manifest["documents"]:
        identity = (item["collectionName"], item["docId"])
        if not all(isinstance(x, str) and x for x in identity) or identity in mapping:
            raise ValueError("invalid/duplicate document identity")
        if item["key"] in keys:
            raise ValueError("duplicate source mapping: " + item["key"])
        mapping[identity] = item["key"]
        keys.add(item["key"])
        if item["key"] in dataset["sources"] and item.get("sha256") != dataset["sources"][item["key"]]["sha256"]:
            raise ValueError("manifest source checksum mismatch")
    if not set(dataset["sources"]).issubset(keys):
        raise ValueError("manifest does not map all dataset sources")
    return mapping


def ranking_from_response(data, mapping):
    questions = data.get("subQuestionResults")
    if not isinstance(questions, list) or len(questions) != 1:
        raise ValueError("expected one sub-question; split queries require per-sub-question judgments")
    question = questions[0]
    if question.get("status") != "SUCCESS":
        raise ValueError("sub-question failed")
    ranking, unmapped = [], []
    for expected, hit in enumerate(question["rankedChunks"], 1):
        if hit.get("rank") != expected:
            raise ValueError("invalid rank sequence")
        identity = (hit.get("collectionName"), hit.get("docId"))
        key = mapping.get(identity)
        if key is None:
            # Keep this position as unjudged; never silently drop an unknown hit.
            key = "unmapped:" + digest([identity, hit.get("chunkId"), hit.get("text")])
            unmapped.append(key)
        ranking.append(key)
    return list(dict.fromkeys(ranking)), unmapped


def score_rows(rows, dataset, manifest, k):
    mapping = validate_manifest(manifest, dataset)
    cases = {case["id"]: case for case in dataset["cases"]}
    scored = []
    for row in rows:
        result = {key: row[key] for key in ("id", "status", "latencyMs")}
        case = cases[row["id"]]
        result["tags"] = case.get("tags", [])
        result["answerable"] = case["answerable"]
        if row["status"] == "SUCCESS":
            try:
                ranking, unmapped = ranking_from_response(row["response"], mapping)
                result.update(ranking=ranking, unmapped=unmapped, metrics=evaluate(ranking, case["qrels"], k))
            except (ValueError, KeyError, TypeError) as exc:
                result.update(status="INVALID_RESULT", error=str(exc))
        else:
            result["error"] = row.get("error", row["status"])
        scored.append(result)
    return scored


def report(output, rows):
    valid = [r for r in rows if "metrics" in r]
    tags = sorted({t for r in rows for t in r["tags"]})
    summary = {"total": len(rows), "scored": len(valid), "errors": len(rows) - len(valid),
               "metrics": aggregate([r["metrics"] for r in valid if r["answerable"]]),
               "byTag": {t: aggregate([r["metrics"] for r in valid if t in r["tags"] and r["answerable"]]) for t in tags},
               "unanswerable": {"n": sum(not r["answerable"] for r in valid),
                                "withResults": sum(not r["answerable"] and bool(r["ranking"]) for r in valid)}}
    write(output / "scores.json", rows)
    write(output / "summary.json", summary)
    lines = ["# 检索评测报告", "", f"总数 {len(rows)}；计分 {len(valid)}；错误 {summary['errors']}。", "",
             "文档级排序来自已召回 chunks 的首次文档顺序，不是后端文档 TopK。", "",
             "| 用例 | 状态 | Recall | nDCG | 未命中相关文档 |", "|---|---|---|---|---|"]
    for row in rows:
        m = row.get("metrics", {})
        lines.append(f"| {row['id']} | {row['status']} | {m.get('recall', 'N/A')} | {m.get('ndcg', 'N/A')} | {', '.join(m.get('missing_relevant', []))} |")
    lines += ["", "宏平均、按标签汇总及无答案统计见 summary.json。原始响应可离线重放。"]
    (output / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return summary


def fetch(base_url, question, token, timeout, capture=False):
    query = urllib.parse.urlencode({"question": question, "captureStages": str(capture).lower()})
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = token
    request = urllib.request.Request(base_url.rstrip("/") + "/rag/eval?" + query, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.load(response)
    if str(body.get("code")) != "0" or not isinstance(body.get("data"), dict):
        raise ValueError("API returned non-success envelope")
    return body["data"]


def run(args):
    dataset = load_dataset(args.dataset)
    manifest = read(args.manifest)
    validate_manifest(manifest, dataset)
    config = read(args.config_snapshot)
    for field in ("deploymentRevision", "retrieval", "models"):
        if not config.get(field):
            raise ValueError("config snapshot requires " + field)
    cases = [c for c in dataset["cases"] if not args.tags or set(args.tags.split(",")) & set(c.get("tags", []))]
    if args.limit:
        cases = cases[:args.limit]
    if not cases:
        raise ValueError("no selected cases")
    if args.dry_run:
        print(json.dumps({"cases": [c["id"] for c in cases], "requests": len(cases)}, ensure_ascii=False))
        return
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    meta = {"datasetHash": digest(dataset), "manifestHash": digest(manifest), "caseIds": [c["id"] for c in cases],
            "contract": CONTRACT, "k": args.k, "unit": "document-from-retrieved-chunks", "variant": args.variant,
            "config": config, "captureStages": args.capture_stages, "startedAt": time.time()}
    write(output / "run.json", meta)
    write(output / "dataset.json", dataset)
    write(output / "manifest.json", manifest)
    rows = []
    with (output / "responses.jsonl").open("w", encoding="utf-8") as file:
        for case in cases:
            start = time.monotonic()
            row = {"id": case["id"], "status": "SUCCESS"}
            try:
                row["response"] = fetch(args.base_url, case["question"], os.environ.get("RAGENT_EVAL_TOKEN"), args.timeout, args.capture_stages)
            except urllib.error.HTTPError as exc:
                row.update(status="HTTP_ERROR", error="HTTP " + str(exc.code))
            except (TimeoutError, urllib.error.URLError) as exc:
                row.update(status="CONNECTION_ERROR", error=type(exc).__name__)
            except (ValueError, KeyError, TypeError) as exc:
                row.update(status="INVALID_RESPONSE", error=type(exc).__name__)
            row["latencyMs"] = round((time.monotonic() - start) * 1000)
            rows.append(row)
            file.write(json.dumps(row, ensure_ascii=False) + "\n")
            file.flush()
            print(case["id"], row["status"], flush=True)
    summary = report(output, score_rows(rows, dataset, manifest, args.k))
    if summary["errors"]:
        raise SystemExit(2)


def replay(args):
    source = Path(args.run)
    meta = read(source / "run.json")
    dataset, manifest = read(source / "dataset.json"), read(source / "manifest.json")
    if digest(dataset) != meta["datasetHash"] or digest(manifest) != meta["manifestHash"] or meta["contract"] != CONTRACT:
        raise ValueError("run fingerprints or metric contract changed")
    rows = [json.loads(line) for line in (source / "responses.jsonl").read_text(encoding="utf-8").splitlines()]
    if [r["id"] for r in rows] != meta["caseIds"]:
        raise ValueError("run incomplete or case order changed")
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    report(output, score_rows(rows, dataset, manifest, meta["k"]))


def compare(args):
    a, b = Path(args.baseline), Path(args.candidate)
    ma, mb = read(a / "run.json"), read(b / "run.json")
    for key in ("datasetHash", "manifestHash", "caseIds", "contract", "k", "unit"):
        if ma[key] != mb[key]:
            raise ValueError("incomparable runs: " + key)
    ra, rb = read(a / "scores.json"), read(b / "scores.json")
    if [r["id"] for r in ra] != ma["caseIds"] or [r["id"] for r in rb] != mb["caseIds"]:
        raise ValueError("scores incomplete or reordered")
    differences = []
    for left, right in zip(ra, rb):
        delta = {"id": left["id"], "baselineStatus": left["status"], "candidateStatus": right["status"]}
        if "metrics" in left and "metrics" in right:
            delta["delta"] = {m: right["metrics"][m] - left["metrics"][m]
                              for m in ("precision", "recall", "mrr", "ndcg")
                              if left["metrics"][m] is not None and right["metrics"][m] is not None}
            delta["lostRelevant"] = sorted(set(right["metrics"]["missing_relevant"]) - set(left["metrics"]["missing_relevant"]))
        differences.append(delta)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    write(output / "comparison.json", {"baselineConfig": ma["config"], "candidateConfig": mb["config"], "cases": differences})
    lines = ["# 检索回归对照", "", "| 用例 | 基线状态 | 候选状态 | Recall 变化 | nDCG 变化 | 丢失相关文档 |", "|---|---|---|---|---|---|"]
    for item in differences:
        d = item.get("delta", {})
        lines.append(f"| {item['id']} | {item['baselineStatus']} | {item['candidateStatus']} | {d.get('recall', 'N/A')} | {d.get('ndcg', 'N/A')} | {', '.join(item.get('lostRelevant', []))} |")
    (output / "compare.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("run")
    for option in ("dataset", "manifest", "config-snapshot", "output", "variant"):
        p.add_argument("--" + option, required=True)
    p.add_argument("--base-url", default="http://127.0.0.1:9090/api/ragent")
    p.add_argument("--k", type=int, default=5)
    p.add_argument("--timeout", type=float, default=120)
    p.add_argument("--limit", type=int)
    p.add_argument("--tags")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--capture-stages", action="store_true")
    p = sub.add_parser("replay")
    p.add_argument("--run", required=True)
    p.add_argument("--output", required=True)
    p = sub.add_parser("compare")
    for option in ("baseline", "candidate", "output"):
        p.add_argument("--" + option, required=True)
    args = parser.parse_args()
    if args.command == "run" and (args.k <= 0 or args.timeout <= 0 or (args.limit is not None and args.limit <= 0)):
        parser.error("k, timeout and limit must be positive")
    {"run": run, "replay": replay, "compare": compare}[args.command](args)


if __name__ == "__main__":
    main()
