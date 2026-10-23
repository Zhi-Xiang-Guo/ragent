#!/usr/bin/env python3
"""Evidence and ranking diagnostics, not an LLM correctness judge."""
import argparse
import collections
import hashlib
import html
import json
import math
from pathlib import Path
import re
import statistics

ROOT = Path(__file__).resolve().parent
MODES = ('baseline', 'parent-child', 'sentence-window')


def norm(text):
    text = html.unescape(text or '')
    text = re.sub(r'</?[A-Za-z][^>]*>', '', text)
    return ''.join(c.lower() for c in text if c.isalnum())


def unique(values):
    return list(dict.fromkeys(x for x in values if x))


def rank_metrics(ranking, qrels, k=10):
    ranking = unique(ranking)[:k]
    relevant = {key for key, grade in qrels.items() if grade > 0}
    dcg = sum((2 ** qrels.get(key, 0) - 1) / math.log2(i + 2) for i, key in enumerate(ranking))
    ideal = sum((2 ** grade - 1) / math.log2(i + 2)
                for i, grade in enumerate(sorted(qrels.values(), reverse=True)[:k]))
    return {'recall': len(relevant.intersection(ranking)) / len(relevant) if relevant else None,
            'mrr': next((1 / (i + 1) for i, key in enumerate(ranking) if key in relevant), 0) if relevant else None,
            'ndcg': dcg / ideal if ideal else None}


def latest(path):
    rows = {}
    if path.exists():
        for line in path.read_text().splitlines():
            row = json.loads(line)
            rows[row['id']] = row
    return rows


def score(case, row, id_to_key):
    response = row.get('response') or {}
    groups = response.get('subQuestionResults') or []
    bad_stages = [s['stage'] + ':' + s['status'] for q in groups for s in q.get('stages', [])
                  if s.get('status') not in ('SUCCESS', 'EMPTY')]
    bad_stages += ['subQuestion:' + str(q.get('status')) for q in groups if q.get('status') != 'SUCCESS']
    healthy = not row.get('error') and response.get('answerStatus') in ('SUCCESS', 'NO_CONTEXT') and not bad_stages
    rankings = [unique(id_to_key.get(c.get('docId')) for c in q.get('rankedChunks', [])) for q in groups]
    union = set(key for ranking in rankings for key in ranking[:10])
    qrels = case.get('qrels') or {}
    per_group = [rank_metrics(ranking, qrels) for ranking in rankings]
    units = response.get('contextUnits') or []
    by_doc = collections.defaultdict(list)
    for unit in units:
        chunk = unit.get('chunk') or {}
        by_doc[id_to_key.get(chunk.get('docId'))].append(chunk.get('text') or '')
    evidence = case.get('requiredEvidence') or []
    # Verify both attributed context units and exact final context; rank snapshots are not answer input.
    final_context = norm(response.get('finalContext'))
    coverage = [norm(e['quote']) in norm('\n'.join(by_doc[e['docKey']]))
                and norm(e['quote']) in final_context for e in evidence]
    answer = response.get('answer') or ''
    checks = case.get('answerChecks') or []
    abstained = bool(re.search(r'信息不足|不足以回答|未包含|没有关于.{0,30}(信息|说明)|未.{0,12}(提供|说明|提及|检索|找到|制定)|没有.{0,12}(信息|说明|提供|规定)|无法.{0,8}(确定|得知|回答|推断)|不清楚|不能.{0,6}(确定|推断)', answer))
    answer_proxy = all(re.search(pattern, answer, re.I) is not None for pattern in checks) if checks else None
    if not case.get('answerable', True):
        answer_proxy = abstained
    return {'id': case['id'], 'sourceKind': case['sourceKind'], 'split': case['split'],
            'healthy': healthy, 'badStages': bad_stages, 'answerStatus': response.get('answerStatus'),
            'docRecallUnionAt10': len(union.intersection(qrels)) / len(qrels) if qrels else None,
            'perSubQuestion': per_group, 'subQuestions': len(groups),
            'singleQuestionNdcgAt10': per_group[0]['ndcg'] if len(per_group) == 1 else None,
            'evidenceCoverage': sum(coverage) / len(coverage) if coverage else None,
            'allEvidence': all(coverage) if coverage else None, 'evidenceItems': coverage,
            'answerCheckProxy': answer_proxy, 'abstentionProxy': abstained,
            'answerReferenceSubstringCoverage': sum(norm(e['quote']) in norm(answer) for e in evidence) / len(evidence) if evidence else None,
            'contextChars': len(response.get('finalContext') or ''), 'latencyMs': response.get('latencyMs'),
            'contextUnits': len(units), 'truncatedUnits': sum('TRUNCATED' in u.get('status', '') for u in units),
            'attempts': len(row.get('attempts') or [1])}


def mean(rows, key):
    values = [float(r[key]) for r in rows if r.get(key) is not None]
    return round(statistics.mean(values), 4) if values else None


def summarize(rows):
    healthy = [r for r in rows if r['healthy']]
    latencies = sorted(r['latencyMs'] for r in healthy if r['latencyMs'] is not None)
    return {'n': len(rows), 'healthy': len(healthy), 'operationalFailures': len(rows) - len(healthy),
            **{key: mean(healthy, key) for key in ('docRecallUnionAt10', 'singleQuestionNdcgAt10',
                'evidenceCoverage', 'allEvidence', 'answerCheckProxy', 'contextChars', 'contextUnits', 'attempts')},
            'answerProxyN': sum(r['answerCheckProxy'] is not None for r in healthy),
            'singleQuestionNdcgN': sum(r['singleQuestionNdcgAt10'] is not None for r in healthy),
            'latencyP50Ms': statistics.median(latencies) if latencies else None,
            'latencyP95Ms': latencies[min(len(latencies)-1, math.ceil(len(latencies)*.95)-1)] if latencies else None}


def analyze(out, dataset_path=ROOT / 'dataset.json'):
    dataset = json.loads(dataset_path.read_text())
    mapping = json.loads((out / 'mapping.json').read_text())
    id_to_key = {value['id']: key for key, value in mapping['documents'].items()}
    case_map = {c['id']: c for c in dataset['cases']}
    scores, report = {}, {'datasetSha256': hashlib.sha256(dataset_path.read_bytes()).hexdigest(), 'modes': {}}
    for mode in MODES:
        rows = latest(out / (mode + '.jsonl'))
        scored = [score(case_map[key], row, id_to_key) for key, row in rows.items() if key in case_map]
        scores[mode] = {r['id']: r for r in scored}
        groups = {name: summarize([r for r in scored if r['split'] == name]) for name in ('dev', 'test', 'regression')}
        groups.update({name: summarize([r for r in scored if r['sourceKind'] == name]) for name in ('real-cookbook', 'synthetic', 'existing-simulated-regression')})
        report['modes'][mode] = {'all': summarize(scored), 'groups': groups}
        (out / (mode + '-scores.json')).write_text(json.dumps(scored, ensure_ascii=False, indent=2))
    failures = [key for key, row in scores['baseline'].items() if row['healthy'] and
                (row['allEvidence'] is False or row['answerCheckProxy'] is False)]
    failure_path = out / 'baseline-failures.json'
    if not failure_path.exists() and len(scores['baseline']) == len(case_map):
        failure_path.write_text(json.dumps(failures, ensure_ascii=False, indent=2))
    frozen = json.loads(failure_path.read_text()) if failure_path.exists() else failures
    report['baselineFailureCount'] = len(frozen)
    report['baselineFailureSetSha256'] = hashlib.sha256(json.dumps(frozen, sort_keys=True).encode()).hexdigest()
    report['pairedChanges'] = {}
    for mode in MODES[1:]:
        shared = [key for key in scores['baseline'] if key in scores[mode] and scores['baseline'][key]['healthy'] and scores[mode][key]['healthy']]
        report['pairedChanges'][mode] = {'n': len(shared), 'baselineFailures': summarize([scores[mode][key] for key in shared if key in frozen]),
            'evidenceImprovements': [key for key in shared if scores[mode][key]['allEvidence'] is True and scores['baseline'][key]['allEvidence'] is False],
            'evidenceRegressions': [key for key in shared if scores[mode][key]['allEvidence'] is False and scores['baseline'][key]['allEvidence'] is True],
            'answerProxyImprovements': [key for key in shared if scores[mode][key]['answerCheckProxy'] is True and scores['baseline'][key]['answerCheckProxy'] is False],
            'answerProxyRegressions': [key for key in shared if scores[mode][key]['answerCheckProxy'] is False and scores['baseline'][key]['answerCheckProxy'] is True]}
    (out / 'summary.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, default=ROOT / 'dataset.json')
    args = parser.parse_args()
    analyze(args.out, args.dataset)
