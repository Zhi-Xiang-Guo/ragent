"""Real API + PostgreSQL lifecycle checks restricted to a disposable benchmark document."""
import importlib.util
import json
from pathlib import Path
import time
import urllib.parse


def run(ops, variant):
    fixture = ops.REPOS['baseline'] / 'resources/regression/indexing-evaluation/runner.py'
    module_spec = importlib.util.spec_from_file_location('indexing_runner', fixture)
    runner = importlib.util.module_from_spec(module_spec)
    module_spec.loader.exec_module(runner)
    client = runner.Client(getattr(ops, 'api_base', 'http://127.0.0.1:19090/api/ragent'))
    results = getattr(ops, 'RESULTS', ops.ROOT / 'results')
    mapping = json.loads((results / 'mapping.json').read_text())
    name = 'lifecycle-' + variant + '-' + str(int(time.time()))
    content = ('# 龙爪临时审计指南\n\n## 恢复条件\n\n' +
               '审计人员必须先核对快照、保留每轮运行报告并核实批次编号。\n\n' * 12 +
               '当龙爪临时审计出现紫色标记时，必须等待十九分钟且待处理低于八十一条，才允许恢复。\n\n' +
               '## 回滚流程\n\n' + '回滚前核验任务水位和签字，必要时联系值班人员。\n\n' * 10)
    doc = client.upload(mapping['kbId'], name, content.encode(), runner.spec(variant))
    doc_id = doc['id']
    assert doc_id.isdigit()
    base = '/knowledge-base/docs/' + doc_id
    events = []

    def record(check, **details):
        events.append({'check': check, 'passed': True, **details})
        (results / (variant + '-lifecycle.json')).write_text(
            json.dumps({'variant': variant, 'docId': doc_id, 'checks': events}, ensure_ascii=False, indent=2))
        print(check, 'PASS', flush=True)

    def ingest():
        client.call('/rag/eval/ingest/' + doc_id, 'POST')
        state = client.call(base)
        assert state['status'] == 'success', state['status']
        return state

    def snapshot():
        sql = f"""SELECT json_build_object(
          'chunks',COALESCE((SELECT json_agg(json_build_object('id',id,'type',chunk_type,'enabled',enabled) ORDER BY id)
            FROM t_knowledge_chunk WHERE doc_id='{doc_id}' AND deleted=0),'[]'::json),
          'vectors',COALESCE((SELECT json_agg(id ORDER BY id) FROM t_knowledge_vector WHERE metadata->>'doc_id'='{doc_id}'),'[]'::json))::text;"""
        result = ops.compose_sql(sql.encode()).decode()
        return next(json.loads(line.strip()) for line in result.splitlines() if line.strip().startswith('{'))

    def expected_vectors(state):
        return {c['id'] for c in state['chunks'] if c['type'] != 'PARENT' and c['enabled'] == 1}

    def rejected(path, method, body=None):
        try:
            client.call(path, method, body)
        except RuntimeError as exc:
            assert '高级分块' in str(exc), str(exc)
        else:
            raise AssertionError('Advanced chunk mutation unexpectedly accepted')

    ingest()
    initial = snapshot()
    assert set(initial['vectors']) == expected_vectors(initial)
    if variant == 'parent-child':
        assert any(c['type'] == 'PARENT' for c in initial['chunks'])
        assert all(c['chunkType'] != 'PARENT' for c in client.call(base + '/chunks?size=100')['records'])
    else:
        assert all(c['type'] == 'SENTENCE' for c in initial['chunks'])
    record('roles-and-vectors', chunks=len(initial['chunks']), vectors=len(initial['vectors']))
    chunk = initial['vectors'][0]
    rejected(base + '/chunks', 'POST', {'content': 'manual'})
    rejected(base + '/chunks/' + chunk, 'PUT', {'content': 'manual'})
    rejected(base + '/chunks/' + chunk, 'DELETE')
    rejected(base + '/chunks/' + chunk + '/enable?value=false', 'PATCH')
    rejected(base + '/chunks/batch-enable?value=false', 'PATCH', {'chunkIds': [chunk]})
    record('all-five-advanced-mutation-guards')
    ingest()
    rebuilt = snapshot()
    assert not set(c['id'] for c in initial['chunks']).intersection(c['id'] for c in rebuilt['chunks'])
    assert set(rebuilt['vectors']) == expected_vectors(rebuilt)
    record('reingestion-replaces-entire-batch')
    client.call(base + '/enable?value=false', 'PATCH')
    disabled = snapshot()
    assert not disabled['vectors'] and all(c['enabled'] == 0 for c in disabled['chunks'])
    query = urllib.parse.urlencode({'question': '龙爪临时审计出现紫色标记时，等待多久才能恢复？', 'captureStages': 'true'})
    response = client.call('/rag/eval?' + query)
    assert all(u['chunk'].get('docId') != doc_id for u in response.get('contextUnits', []))
    record('disable-removes-vectors-and-context')
    client.call(base + '/enable?value=true', 'PATCH')
    enabled = snapshot()
    assert set(enabled['vectors']) == expected_vectors(enabled)
    assert set(enabled['vectors']) == set(rebuilt['vectors'])
    record('reenable-restores-only-searchable-vectors')

    # Force a real downstream SQL sink failure after relational replacement begins.
    trigger = 'eval_fail_' + doc_id
    sql = f"""CREATE FUNCTION {trigger}() RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN IF NEW.metadata->>'doc_id' = '{doc_id}' THEN RAISE EXCEPTION 'intentional benchmark sink failure'; END IF; RETURN NEW; END $$;
      CREATE TRIGGER {trigger} BEFORE INSERT ON t_knowledge_vector FOR EACH ROW EXECUTE FUNCTION {trigger}();"""
    ops.compose_sql(sql.encode())
    try:
        client.call('/rag/eval/ingest/' + doc_id, 'POST')
        assert client.call(base)['status'] == 'failed'
        assert snapshot() == enabled
        record('downstream-sink-failure-rolls-back-old-chunks-and-vectors')
    finally:
        ops.compose_sql(f'DROP TRIGGER IF EXISTS {trigger} ON t_knowledge_vector; DROP FUNCTION IF EXISTS {trigger}();'.encode())
    ingest()
    client.call(base, 'PUT', {'docName': name + '.md', 'processMode': 'chunk', 'ingestionSpec': json.dumps(runner.spec('baseline'))})
    rejected(base + '/chunks', 'POST', {'content': 'manual'})
    record('changing-config-alone-cannot-unlock-advanced-chunks')
    ingest()
    ordinary = snapshot()
    assert all(c['type'] == 'STANDARD' for c in ordinary['chunks'])
    manual = client.call(base + '/chunks', 'POST', {'content': '标准分块手工编辑回归测试。'})
    client.call(base + '/chunks/' + manual['id'], 'PUT', {'content': '标准分块更新成功。'})
    client.call(base + '/chunks/' + manual['id'], 'DELETE')
    record('standard-rebuild-restores-existing-edit-api')
    client.call(base, 'PUT', {'docName': name + '.md', 'processMode': 'chunk', 'ingestionSpec': json.dumps(runner.spec(variant))})
    ingest()
    client.call(base, 'DELETE')
    deleted = snapshot()
    assert not deleted['chunks'] and not deleted['vectors']
    record('advanced-document-delete-cleans-all-roles-and-vectors')


if __name__ == '__main__':
    import argparse
    import subprocess
    from types import SimpleNamespace
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['parent-child', 'sentence-window'])
    parser.add_argument('--compose', type=Path, required=True)
    parser.add_argument('--project', default='ragent-index-eval')
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--base', default='http://127.0.0.1:19090/api/ragent')
    args = parser.parse_args()
    def sql(data):
        result = subprocess.run(['docker', 'compose', '-p', args.project, '-f', str(args.compose),
            'exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'ragent'],
            input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True)
        return result.stdout
    ops = SimpleNamespace(ROOT=args.results.parent, RESULTS=args.results,
        REPOS={'baseline': Path(__file__).resolve().parents[3]}, compose_sql=sql, api_base=args.base)
    run(ops, args.mode)
