"""Deterministic retrieval metrics; Python 3.10+, standard library only."""
import argparse
import json
import math
from pathlib import Path

CONTRACT = {
    "version": 1,
    "dedup": "first occurrence before cutoff",
    "positive": "grade > 0",
    "precision_denominator": "k",
    "gain": "2**grade - 1",
    "discount": "log2(rank + 1)",
    "unjudged": "zero gain, separately reported",
    "no_positive": "recall/mrr/ndcg null; report separately",
}


def evaluate(ranking, qrels, k):
    if isinstance(k, bool) or not isinstance(k, int) or k <= 0:
        raise ValueError("k must be a positive integer")
    if not isinstance(qrels, dict) or any(
        not isinstance(key, str) or not key or isinstance(grade, bool)
        or not isinstance(grade, int) or not 0 <= grade <= 3
        for key, grade in qrels.items()
    ):
        raise ValueError("qrels must map nonempty identifiers to integer grades 0..3")
    if not isinstance(ranking, list) or any(not isinstance(x, str) or not x for x in ranking):
        raise ValueError("ranking must be a list of nonempty identifiers")
    unique = list(dict.fromkeys(ranking))
    prefix = unique[:k]
    grades = [qrels.get(key, 0) for key in prefix]
    positives = sum(grade > 0 for grade in qrels.values())
    hits = sum(grade > 0 for grade in grades)
    dcg = sum((2 ** grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(grades, 1))
    ideal = sorted(qrels.values(), reverse=True)[:k]
    idcg = sum((2 ** grade - 1) / math.log2(rank + 1) for rank, grade in enumerate(ideal, 1))
    first = next((rank for rank, grade in enumerate(grades, 1) if grade > 0), None)
    unjudged = [key for key in prefix if key not in qrels]
    return {
        "k": k, "returned": len(prefix), "relevant_total": positives, "hits": hits,
        "precision": hits / k,
        "recall": hits / positives if positives else None,
        "mrr": (1 / first if first else 0.0) if positives else None,
        "ndcg": dcg / idcg if idcg else None,
        "judged_fraction": (len(prefix) - len(unjudged)) / len(prefix) if prefix else None,
        "unjudged": unjudged, "duplicates_removed": len(ranking) - len(unique),
        "missing_relevant": [key for key, grade in qrels.items() if grade > 0 and key not in prefix],
    }


def aggregate(results):
    """Macro average over defined values; include each metric's sample count."""
    output = {}
    for metric in ("precision", "recall", "mrr", "ndcg", "judged_fraction"):
        values = [row[metric] for row in results if row[metric] is not None]
        output[metric] = {"mean": sum(values) / len(values) if values else None, "n": len(values)}
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="JSON array: id, ranking, qrels")
    parser.add_argument("--k", type=int, default=5)
    args = parser.parse_args()
    rows = json.loads(args.input.read_text(encoding="utf-8"))
    results = [{"id": row["id"], **evaluate(row["ranking"], row["qrels"], args.k)} for row in rows]
    print(json.dumps({"contract": CONTRACT, "cases": results, "summary": aggregate(results)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
