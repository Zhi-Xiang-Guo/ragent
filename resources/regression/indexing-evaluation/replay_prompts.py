"""Repeat synthesis from captured production prompts; does not pretend to repeat retrieval."""
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


def run(ops, mode):
    out = getattr(ops, 'RESULTS', ops.ROOT / 'results') / 'stress'
    failures = set(json.loads((out / 'baseline-failures.json').read_text()))
    source = {}
    for line in (out / (mode + '.jsonl')).read_text().splitlines():
        row = json.loads(line)
        source[row['id']] = row
    if hasattr(ops, 'model_key'):
        key = ops.model_key
    else:
        env = json.loads((ops.ROOT / 'compose.json').read_text())['services']['backend']['environment']
        key = env['OPAY_LLM_API_KEY'].replace('$$', '$')
    url = getattr(ops, 'chat_url', 'https://routers.opays.ai/api/v0/llm/chat/completions')
    model = getattr(ops, 'chat_model', 'deepseek-v4-flash')
    for repeat in (1, 2):
        path = out / (mode + '-prompt-replay-' + str(repeat) + '.jsonl')
        completed = set()
        if path.exists():
            completed = {json.loads(line)['id'] for line in path.read_text().splitlines() if not json.loads(line).get('error')}
        with path.open('a') as handle:
            for case_id, row in source.items():
                if case_id not in failures or case_id in completed:
                    continue
                marker = ops.ROOT / 'campaign-state.json'
                phases = json.loads(marker.read_text()) if marker.exists() else []
                if 'sentence-window-ingest' in phases and 'sentence-window-lifecycle' not in phases:
                    print('Prompt replay deferred while the main answer campaign runs', flush=True)
                    return
                messages = row['response'].get('answerMessages') or []
                assert messages, case_id
                body = {'model': model, 'messages': [
                    {'role': message['role'].lower(), 'content': message['content']} for message in messages],
                    'temperature': 0, 'top_p': 1, 'stream': False, 'thinking': {'type': 'disabled'}}
                record = {'id': case_id, 'mode': mode, 'repeat': repeat,
                          'transport': 'captured-production-prompt-replay',
                          'promptSha256': hashlib.sha256(json.dumps(messages, ensure_ascii=False, sort_keys=True).encode()).hexdigest()}
                started = time.monotonic()
                for attempt in range(4):
                    request = urllib.request.Request(url,
                        data=json.dumps(body, ensure_ascii=False).encode(),
                        headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key})
                    try:
                        with urllib.request.urlopen(request, timeout=120) as response:
                            result = json.load(response)
                        record.update(answer=result['choices'][0]['message']['content'], attempts=attempt + 1,
                                      latencySeconds=time.monotonic() - started)
                        break
                    except (urllib.error.URLError, KeyError, IndexError) as exc:
                        if attempt == 3:
                            record.update(error=type(exc).__name__, attempts=attempt + 1)
                        else:
                            time.sleep(35)
                handle.write(json.dumps(record, ensure_ascii=False) + '\n')
                handle.flush()
                print(mode, 'prompt replay', repeat, case_id, 'ERROR' if record.get('error') else 'SUCCESS', flush=True)
                time.sleep(2)


if __name__ == '__main__':
    import argparse
    import os
    from types import SimpleNamespace
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['baseline', 'parent-child', 'sentence-window', 'all'])
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--url', required=True, help='Same chat endpoint as the captured production run')
    parser.add_argument('--model', required=True, help='Same model as the captured production run')
    parser.add_argument('--api-key-env', default='EVAL_MODEL_API_KEY')
    args = parser.parse_args()
    ops = SimpleNamespace(ROOT=args.results.parent, RESULTS=args.results, model_key=os.environ[args.api_key_env],
                          chat_url=args.url, chat_model=args.model)
    for mode in (['baseline', 'parent-child', 'sentence-window'] if args.mode == 'all' else [args.mode]):
        run(ops, mode)
