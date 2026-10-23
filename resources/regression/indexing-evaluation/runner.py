#!/usr/bin/env python3
"""Frozen-corpus, production-API benchmark. Credentials stay in memory, never in artifacts."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parent


class Client:
    def __init__(self, base):
        self.base, self.token = base.rstrip('/'), None
        data = self.call('/auth/login', 'POST', {
            'username': os.environ.get('EVAL_USERNAME', 'admin'),
            'password': os.environ.get('EVAL_PASSWORD', 'admin')})
        self.token = data['token']

    def call(self, path, method='GET', body=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if self.token:
            headers['Authorization'] = self.token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode()
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=240) as response:
                result = json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError('HTTP ' + str(exc.code) + ' at ' + path.split('?')[0]) from None
        if str(result.get('code')) != '0':
            raise RuntimeError('API failure at ' + path.split('?')[0] + ': ' + str(result.get('message'))[:300])
        return result.get('data')

    def upload(self, kb, key, data, spec):
        boundary = uuid.uuid4().hex
        fields = {'sourceType': 'file', 'processMode': 'chunk',
                  'ingestionSpec': json.dumps(spec)}
        parts = []
        for name, value in fields.items():
            parts.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="' + name +
                          '"\r\n\r\n' + value + '\r\n').encode())
        parts += [('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' +
                   key + '.md"\r\nContent-Type: text/markdown\r\n\r\n').encode(), data,
                  ('\r\n--' + boundary + '--\r\n').encode()]
        return self.call('/knowledge-base/' + kb + '/docs/upload', 'POST', b''.join(parts),
                         'multipart/form-data; boundary=' + boundary)


def spec(mode):
    value = {'parseProfile': 'fast', 'maxChars': 512, 'overlapChars': 64, 'rowsPerChunk': 8}
    if mode == 'parent-child':
        value.update(indexingMode='PARENT_CHILD', parentMaxChars=2048, childMaxChars=384, childOverlapChars=64)
    if mode == 'sentence-window':
        value.update(indexingMode='SENTENCE_WINDOW', windowRadius=2, maxSentenceChars=384)
    return value


def save(path, value):
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    tmp.replace(path)


def ingest(client, dataset, out, mode, synchronous=False, mapping_path=None, dataset_path=None):
    path = mapping_path or out / 'mapping.json'
    mapping = json.loads(path.read_text()) if path.exists() else {'documents': {}}
    if 'kbId' not in mapping:
        mapping['kbId'] = client.call('/knowledge-base', 'POST', {
            'name': 'Frozen indexing evaluation', 'embeddingModel': 'opays-qwen3-embedding-8b',
            'collectionName': 'frozen_indexing_eval'})
        save(path, mapping)
    for doc in dataset['documents']:
        data = (ROOT / doc['path']).read_bytes()
        if hashlib.sha256(data).hexdigest() != doc['sha256']:
            raise ValueError('Corpus hash mismatch: ' + doc['key'])
        existing = mapping['documents'].get(doc['key'])
        if existing and existing.get('mode') == mode:
            continue
        if existing:
            doc_id = existing['id']
            # The two experimental branches intentionally accept different advanced modes.
            # Convert the known completed document through its existing update API first.
            client.call('/knowledge-base/docs/' + doc_id, 'PUT', {'docName': doc['key'] + '.md', 'processMode': 'chunk', 'ingestionSpec': json.dumps(spec(mode))})
            current = client.call('/knowledge-base/docs/' + doc_id)
            if not current.get('enabled'):
                client.call('/knowledge-base/docs/' + doc_id + '/enable?value=true', 'PATCH')
                current = client.call('/knowledge-base/docs/' + doc_id)
            if current.get('status') == 'running':
                deadline = time.monotonic() + 300
                while current.get('status') == 'running' and time.monotonic() < deadline:
                    time.sleep(2)
                    current = client.call('/knowledge-base/docs/' + doc_id)
                if current.get('status') == 'running':
                    raise RuntimeError('Existing ingestion still running: ' + doc['key'])
        else:
            uploaded = client.upload(mapping['kbId'], doc['key'], data, spec(mode))
            doc_id = uploaded['id']
            mapping['documents'][doc['key']] = {'id': doc_id}
            save(path, mapping)
        route = '/rag/eval/ingest/' + doc_id if synchronous else '/knowledge-base/docs/' + doc_id + '/chunk'
        ingestion_attempts = []
        for attempt in range(4):
            client.call(route, 'POST')
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                state = client.call('/knowledge-base/docs/' + doc_id)
                status = str(state.get('status', '')).lower()
                if status in ('success', 'completed', '2', 'failed', 'failure', '3'):
                    break
                time.sleep(2)
            else:
                raise RuntimeError('Ingestion timeout: ' + doc['key'] + ' status=' + status)
            ingestion_attempts.append(status)
            if status in ('success', 'completed', '2'):
                break
            print('ingestion retry', mode, doc['key'], attempt + 1, status, flush=True)
            if attempt == 3:
                raise RuntimeError('Ingestion failed after retries: ' + doc['key'])
            time.sleep(35)
        mapping['documents'][doc['key']].update(mode=mode, chunks=state.get('chunkCount'), ingestionAttempts=ingestion_attempts)
        save(path, mapping)
        print('ingested', mode, doc['key'], state.get('chunkCount'), flush=True)
    if path != out / 'mapping.json':
        save(out / 'mapping.json', mapping)
    save(out / (mode + '-ingestion.json'), {'spec': spec(mode), 'transport': 'synchronous-production-service' if synchronous else 'rocketmq', 'datasetSha256': hashlib.sha256((dataset_path or ROOT / 'dataset.json').read_bytes()).hexdigest(), 'mapping': mapping})


def response_healthy(response):
    return response.get('answerStatus') in ('SUCCESS', 'NO_CONTEXT') and all(
        q.get('status') == 'SUCCESS' and all(s.get('status') in ('SUCCESS', 'EMPTY') for s in q.get('stages', []))
        for q in response.get('subQuestionResults', []))


def evaluate(client, dataset, out, mode, workers, repeat, only):
    path = out / (mode + ('-repeat' + str(repeat) if repeat else '') + '.jsonl')
    completed = set()
    if path.exists():
        for line in path.read_text().splitlines():
            row = json.loads(line)
            if not row.get('error') and response_healthy(row.get('response') or {}):
                completed.add(row['id'])
    cases = [c for c in dataset['cases'] if c['id'] not in completed and (only is None or c['id'] in only)]
    def one(case):
        started = time.monotonic()
        try:
            query = urllib.parse.urlencode({'question': case['question'], 'captureStages': 'true', 'generateAnswer': 'true'})
            attempts = []
            for attempt in range(4):
                response = client.call('/rag/eval?' + query)
                attempts.append({'answerStatus': response.get('answerStatus'), 'latencyMs': response.get('latencyMs')})
                if response_healthy(response):
                    break
                if attempt < 3:
                    time.sleep(35)
            time.sleep(2)
            return {'id': case['id'], 'mode': mode, 'repeat': repeat, 'response': response,
                    'attempts': attempts, 'wallSeconds': time.monotonic() - started, 'timestamp': time.time()}
        except Exception as exc:
            return {'id': case['id'], 'mode': mode, 'repeat': repeat, 'error': str(exc)[:400]}
    with path.open('a') as handle, concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for row in pool.map(one, cases):
            handle.write(json.dumps(row, ensure_ascii=False) + '\n')
            handle.flush()
            r = row.get('response') or {}
            print(mode, row['id'], row.get('error') or r.get('answerStatus'), 'docs=' + str(r.get('retrievedDocIds')), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('phase', choices=['ingest', 'eval'])
    parser.add_argument('mode', choices=['baseline', 'parent-child', 'sentence-window'])
    parser.add_argument('--base', default='http://127.0.0.1:19090/api/ragent')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--workers', type=int, default=1)
    parser.add_argument('--repeat', type=int, default=0)
    parser.add_argument('--only', type=Path)
    parser.add_argument('--synchronous', action='store_true', help='Requires both eval.enabled and eval.ingestion-enabled')
    parser.add_argument('--dataset', type=Path, default=ROOT / 'dataset.json')
    parser.add_argument('--mapping', type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    dataset = json.loads(args.dataset.read_text())
    for attempt in range(30):
        try:
            client = Client(args.base)
            break
        except (urllib.error.URLError, ConnectionError):
            if attempt == 29:
                raise
            time.sleep(2)
    if args.phase == 'ingest':
        ingest(client, dataset, args.out, args.mode, args.synchronous, args.mapping, args.dataset)
    else:
        evaluate(client, dataset, args.out, args.mode, args.workers, args.repeat,
                 set(json.loads(args.only.read_text())) if args.only else None)


if __name__ == '__main__':
    main()
